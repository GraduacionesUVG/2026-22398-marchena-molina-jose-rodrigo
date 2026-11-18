import type { APIGatewayProxyHandler } from 'aws-lambda';
import { S3Client, PutObjectCommand } from '@aws-sdk/client-s3';
import { executeStatement, requireEnv } from '../common/db';

const s3 = new S3Client({});

interface RawSyncRequest {
  batch_id: string;
  entries: unknown[];
}

function badRequest(message: string) {
  return {
    statusCode: 400,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ error: 'validation_error', message }),
  };
}

function forbidden(message: string) {
  return {
    statusCode: 403,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ error: 'capture_period_ended', message }),
  };
}

function validate(body: unknown): { value: RawSyncRequest } | { error: string } {
  if (typeof body !== 'object' || body === null) {
    return { error: 'request body must be a JSON object' };
  }
  const b = body as Record<string, unknown>;

  if (typeof b.batch_id !== 'string' || b.batch_id.length === 0) {
    return { error: 'batch_id is required and must be a string' };
  }
  // entries are stored verbatim (shape/encoding intentionally unspecified, see
  // docs/api/openapi.yaml RawSyncRequest) — only the array wrapper is checked.
  if (!Array.isArray(b.entries)) {
    return { error: 'entries is required and must be an array' };
  }

  return { value: { batch_id: b.batch_id, entries: b.entries } };
}

export const handler: APIGatewayProxyHandler = async (event) => {
  let parsedBody: unknown;
  try {
    parsedBody = JSON.parse(event.body ?? '');
  } catch {
    return badRequest('request body must be valid JSON');
  }

  const validation = validate(parsedBody);
  if ('error' in validation) {
    return badRequest(validation.error);
  }
  const { batch_id: batchId, entries } = validation.value;

  const userId = event.requestContext.authorizer?.user_id as string;

  const captureCheck = await executeStatement(
    'SELECT (now() > capture_end_ts) AS expired FROM users WHERE user_id = :user_id',
    // Postgres has no bare `uuid = text` operator -- only an assignment cast
    // (which INSERT/UPDATE target columns get for free, but a WHERE equality
    // comparison does not) -- so this comparison needs an explicit type hint.
    [{ name: 'user_id', value: { stringValue: userId }, typeHint: 'UUID' }],
  );
  if (captureCheck.records?.[0]?.[0]?.booleanValue) {
    return forbidden('capture period has ended for this user');
  }

  await s3.send(
    new PutObjectCommand({
      Bucket: requireEnv('RAW_BUCKET_NAME'),
      // per-user prefix keeps raw dumps recoverable even if a canonical sync
      // for the same window never lands; batch_id as the key suffix makes a
      // retried batch overwrite rather than duplicate (REQ-SYNC-12).
      Key: `raw/${userId}/${batchId}.json`,
      Body: JSON.stringify({ user_id: userId, batch_id: batchId, entries, received_at: new Date().toISOString() }),
      ContentType: 'application/json',
    }),
  );

  return {
    statusCode: 200,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ received: entries.length }),
  };
};
