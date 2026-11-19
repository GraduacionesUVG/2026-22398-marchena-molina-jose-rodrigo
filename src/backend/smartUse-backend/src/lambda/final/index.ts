import type { APIGatewayProxyHandler } from 'aws-lambda';
import { executeStatement } from '../common/db';
import { parseFinalSend, flattenAnswers, filterMetrics } from './validate';

// One statement, so it's all or nothing without Data API transaction
// plumbing (REQ-DEP-13). Answers and metrics arrive as
// JSON parameters expanded by jsonb_to_recordset, so the SQL text stays the
// same size at any record count -- the Data API rejects sql over 64 KB,
// which is what capped /sync/canonical at 500 records (REQ-DEP-15).
//
// The child tables' foreign keys to final_sends are checked at end of
// statement, so the sibling CTE inserts satisfy them. If final_sends already
// has this user_id (a lost race), `s` is empty and nothing else is written.
const STORE_SQL = `
WITH s AS (
  INSERT INTO final_sends (user_id, end_reason, forms, metrics_stored, metrics_skipped)
  VALUES (:user_id, :end_reason, CAST(:forms AS jsonb), :metrics_stored, :metrics_skipped)
  ON CONFLICT (user_id) DO NOTHING
  RETURNING user_id, received_at
), a AS (
  INSERT INTO final_answers (user_id, form_id, form_version, question_id, value_int, value_text)
  SELECT s.user_id, x.form_id, x.form_version, x.question_id, x.value_int, x.value_text
  FROM s, jsonb_to_recordset(CAST(:answers AS jsonb))
       AS x(form_id text, form_version integer, question_id text, value_int integer, value_text text)
), m AS (
  INSERT INTO final_metrics (user_id, decision_point, duration_ms, battery_delta_pct, current_mean, peak_rss_kb)
  SELECT s.user_id, x.decision_point, x.duration_ms, x.battery_delta_pct, x.current_mean, x.peak_rss_kb
  FROM s, jsonb_to_recordset(CAST(:metrics AS jsonb))
       AS x(decision_point text, duration_ms integer, battery_delta_pct integer, current_mean bigint, peak_rss_kb integer)
), u AS (
  UPDATE users SET finished_at = s.received_at FROM s WHERE users.user_id = s.user_id
)
SELECT user_id FROM s`;

function json(statusCode: number, body: unknown) {
  return {
    statusCode,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

const alreadyReceived = () => json(200, { status: 'already_received' });
const invalidBody = (message: string) => json(400, { error: 'invalid_body', message });

export const handler: APIGatewayProxyHandler = async (event) => {
  const userId = event.requestContext.authorizer?.user_id as string;
  const userIdParam = { name: 'user_id', value: { stringValue: userId }, typeHint: 'UUID' as const };

  // Checked before the body (REQ-DEP-09): once a send is stored, the only
  // useful answer is the one that stops the app's retry loop.
  const existing = await executeStatement('SELECT 1 FROM final_sends WHERE user_id = :user_id', [userIdParam]);
  if (existing.records?.length) {
    return alreadyReceived();
  }

  let parsedBody: unknown;
  try {
    parsedBody = JSON.parse(event.body ?? '');
  } catch {
    return invalidBody('request body must be valid JSON');
  }
  const parsed = parseFinalSend(parsedBody);
  if ('error' in parsed) {
    return invalidBody(parsed.error);
  }
  const send = parsed.value;

  const { valid, skipped, reasons } = filterMetrics(send.metrics);
  if (skipped > 0) {
    console.warn('POST /final skipped metric records', { user_id: userId, skipped, reasons });
  }

  // No try/catch: a database error must surface as a 5xx, which the app
  // treats as "try again" -- nothing was stored (REQ-DEP-13).
  const stored = await executeStatement(STORE_SQL, [
    userIdParam,
    { name: 'end_reason', value: { stringValue: send.end_reason } },
    { name: 'forms', value: { stringValue: JSON.stringify(send.forms) } },
    { name: 'answers', value: { stringValue: JSON.stringify(flattenAnswers(send.forms)) } },
    { name: 'metrics', value: { stringValue: JSON.stringify(valid) } },
    { name: 'metrics_stored', value: { longValue: valid.length } },
    { name: 'metrics_skipped', value: { longValue: skipped } },
  ]);

  return stored.records?.length ? json(201, { status: 'stored' }) : alreadyReceived();
};
