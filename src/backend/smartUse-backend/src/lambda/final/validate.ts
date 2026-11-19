// POST /final body rules (REQ-DEP-10..12). Pure: no database,
// so every 400 case and every skipped-metric rule is unit-tested directly.

export type EndReason = 'completed' | 'opt_out';
export type DecisionPoint = 'screen_on' | 'active_use';

export interface FinalForm {
  version: number;
  answers: Record<string, number | string>;
}

export interface FinalSend {
  end_reason: EndReason;
  forms: Record<string, FinalForm>;
  metrics: unknown[];
}

export interface AnswerRow {
  form_id: string;
  form_version: number;
  question_id: string;
  value_int: number | null;
  value_text: string | null;
}

export interface MetricRow {
  decision_point: DecisionPoint;
  duration_ms: number;
  battery_delta_pct: number | null;
  current_mean: number | null;
  peak_rss_kb: number | null;
}

const END_REASONS = ['completed', 'opt_out'];
const DECISION_POINTS = ['screen_on', 'active_use'];
const INT32_MIN = -2147483648;
const INT32_MAX = 2147483647;

// Anything that would overflow an INTEGER column has to be caught here: a
// failed insert is a 5xx, and the app retries 5xx forever (REQ-DEP-10).
function isInt32(v: unknown): v is number {
  return Number.isInteger(v) && (v as number) >= INT32_MIN && (v as number) <= INT32_MAX;
}

// Postgres jsonb rejects \u0000 and lone UTF-16 surrogates (e.g. an emoji the
// app cut in half when trimming to 1000 chars). Either would fail the store
// as a 5xx the app retries forever, so they're replaced with U+FFFD rather
// than rejected -- the participant's answers are kept.
const NUL_OR_LONE_SURROGATE = /\u0000|[\ud800-\udbff](?![\udc00-\udfff])|(?<![\ud800-\udbff])[\udc00-\udfff]/g;

function cleanText(s: string): string {
  return s.replace(NUL_OR_LONE_SURROGATE, '�');
}

function cleanForms(forms: Record<string, FinalForm>): Record<string, FinalForm> {
  return Object.fromEntries(
    Object.entries(forms).map(([formId, form]) => [
      cleanText(formId),
      {
        version: form.version,
        answers: Object.fromEntries(
          Object.entries(form.answers).map(([questionId, answer]) => [
            cleanText(questionId),
            typeof answer === 'string' ? cleanText(answer) : answer,
          ]),
        ),
      },
    ]),
  );
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function validateForm(formId: string, form: unknown): string | null {
  if (!isPlainObject(form)) {
    return `forms.${formId} must be an object`;
  }
  if (!isInt32(form.version) || form.version < 1) {
    return `forms.${formId}.version must be an integer >= 1`;
  }
  if (!isPlainObject(form.answers)) {
    return `forms.${formId}.answers must be an object`;
  }
  for (const [questionId, answer] of Object.entries(form.answers)) {
    if (typeof answer !== 'string' && !isInt32(answer)) {
      return `forms.${formId}.answers.${questionId} must be an integer or a string`;
    }
  }
  return null;
}

export function parseFinalSend(body: unknown): { value: FinalSend } | { error: string } {
  if (!isPlainObject(body)) {
    return { error: 'request body must be a JSON object' };
  }
  if (typeof body.end_reason !== 'string' || !END_REASONS.includes(body.end_reason)) {
    return { error: 'end_reason must be "completed" or "opt_out"' };
  }
  if (!isPlainObject(body.forms)) {
    return { error: 'forms is required and must be an object' };
  }
  if (body.forms.usability === undefined) {
    return { error: 'forms.usability is required' };
  }
  for (const [formId, form] of Object.entries(body.forms)) {
    const error = validateForm(formId, form);
    if (error) {
      return { error };
    }
  }
  const metrics = body.metrics ?? [];
  if (!Array.isArray(metrics)) {
    return { error: 'metrics must be an array when present' };
  }

  return {
    value: {
      end_reason: body.end_reason as EndReason,
      forms: cleanForms(body.forms as Record<string, FinalForm>),
      metrics,
    },
  };
}

export function flattenAnswers(forms: Record<string, FinalForm>): AnswerRow[] {
  return Object.entries(forms).flatMap(([formId, form]) =>
    Object.entries(form.answers).map(([questionId, answer]) => ({
      form_id: formId,
      form_version: form.version,
      question_id: questionId,
      value_int: typeof answer === 'number' ? answer : null,
      value_text: typeof answer === 'string' ? answer : null,
    })),
  );
}

// Returns the name of the first field that disqualifies the record, or null
// if it's storable. Absent and null optional fields are both fine (-> NULL).
function metricSkipReason(record: unknown): string | null {
  if (!isPlainObject(record)) {
    return 'not_object';
  }
  if (typeof record.decision_point !== 'string' || !DECISION_POINTS.includes(record.decision_point)) {
    return 'decision_point';
  }
  if (!isInt32(record.duration_ms) || record.duration_ms < 0) {
    return 'duration_ms';
  }
  for (const field of ['battery_delta_pct', 'peak_rss_kb']) {
    if (record[field] != null && !isInt32(record[field])) {
      return field;
    }
  }
  if (record.current_mean != null && !Number.isSafeInteger(record.current_mean)) {
    return 'current_mean';
  }
  return null;
}

// Bad metric records are skipped and counted, never a 400 (REQ-DEP-12).
export function filterMetrics(raw: unknown[]): { valid: MetricRow[]; skipped: number; reasons: Record<string, number> } {
  const valid: MetricRow[] = [];
  const reasons: Record<string, number> = {};
  let skipped = 0;

  for (const record of raw) {
    const reason = metricSkipReason(record);
    if (reason) {
      skipped++;
      reasons[reason] = (reasons[reason] ?? 0) + 1;
      continue;
    }
    const r = record as Record<string, unknown>;
    valid.push({
      decision_point: r.decision_point as DecisionPoint,
      duration_ms: r.duration_ms as number,
      battery_delta_pct: (r.battery_delta_pct as number | null | undefined) ?? null,
      current_mean: (r.current_mean as number | null | undefined) ?? null,
      peak_rss_kb: (r.peak_rss_kb as number | null | undefined) ?? null,
    });
  }

  return { valid, skipped, reasons };
}
