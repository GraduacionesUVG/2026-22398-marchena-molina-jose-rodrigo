import type { APIGatewayProxyHandler } from 'aws-lambda';
import type { SqlParameter } from '@aws-sdk/client-rds-data';
import { executeStatement, toDataApiTimestamp, getCanonicalSyncedThrough } from '../common/db';

const GRANULARITIES = ['small', 'large'];
const UNIQUE_VIOLATION_MARKER = 'uq_canonical_events_user_start';
// Hard cap: larger batches are rejected with 413, never truncated. The
// multi-row INSERT below grows ~106 chars per record and the RDS Data API
// rejects any `sql` string over 65,536 chars -- that ceiling lands at 622
// records (surfacing as an unhandled error / 502), so 500 leaves headroom.
const MAX_RECORDS_PER_BATCH = 500;

interface CanonicalRecord {
  client_record_id: string;
  start_ts: string;
  is_use: boolean;
  distinct_app_count: number;
  granularity: string;
}

function badRequest(message: string) {
  return {
    statusCode: 400,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ error: 'validation_error', message }),
  };
}

function payloadTooLarge(message: string) {
  return {
    statusCode: 413,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ error: 'batch_too_large', message }),
  };
}

function forbidden(message: string) {
  return {
    statusCode: 403,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ error: 'capture_period_ended', message }),
  };
}

interface EpochConflict {
  client_record_id: string;
  start_ts: string;
}

function epochConflict(message: string, conflicts: EpochConflict[]) {
  return {
    statusCode: 409,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ error: 'epoch_conflict', message, conflicts }),
  };
}

// RDS Data API surfaces a Postgres unique-violation as a DatabaseErrorException
// (SDK class for "there was an error in processing the SQL statement") with
// the constraint name embedded in the message -- there's no structured
// SQLSTATE field to check instead. Verified against a live call -- the SDK's
// own BadRequestException is a distinct, more generic exception class that
// this error does not use.
function isEpochConflict(error: unknown): boolean {
  return error instanceof Error && error.name === 'DatabaseErrorException' && error.message.includes(UNIQUE_VIOLATION_MARKER);
}

function recordParameters(record: CanonicalRecord, i: number): SqlParameter[] {
  return [
    { name: `client_record_id_${i}`, value: { stringValue: record.client_record_id }, typeHint: 'UUID' },
    { name: `start_ts_${i}`, value: { stringValue: toDataApiTimestamp(new Date(record.start_ts)) }, typeHint: 'TIMESTAMP' },
  ];
}

// Only runs after the INSERT already failed on uq_canonical_events_user_start,
// which rolled back the whole statement -- so nothing from this batch is
// stored, and this reconstructs which records would collide if the client
// resent it unchanged. Two sources, mirroring how the INSERT itself behaves:
//   1. against stored rows: same (user_id, start_ts) under a different
//      client_record_id;
//   2. within the batch: rows are inserted in payload order, so the first
//      record to claim an instant wins and every later one collides.
// Records whose client_record_id is already stored (or repeated earlier in
// the batch) are skipped by ON CONFLICT (client_record_id) DO NOTHING, so
// they never collide and never claim an instant.
async function findEpochConflicts(userId: string, records: CanonicalRecord[]): Promise<EpochConflict[]> {
  const valueRows = records.map((_, i) => `(:idx_${i}, :client_record_id_${i}, :start_ts_${i})`);
  const result = await executeStatement(
    `SELECT idx, already_stored, epoch_taken FROM (
       SELECT v.idx,
              EXISTS (SELECT 1 FROM canonical_events s WHERE s.client_record_id = v.client_record_id) AS already_stored,
              EXISTS (SELECT 1 FROM canonical_events s
                      WHERE s.user_id = :user_id AND s.start_ts = v.start_ts AND s.client_record_id <> v.client_record_id) AS epoch_taken
       FROM (VALUES ${valueRows.join(', ')}) AS v(idx, client_record_id, start_ts)
     ) flagged
     WHERE already_stored OR epoch_taken`,
    [
      { name: 'user_id', value: { stringValue: userId }, typeHint: 'UUID' },
      ...records.flatMap((record, i) => [{ name: `idx_${i}`, value: { longValue: i } }, ...recordParameters(record, i)]),
    ],
  );

  const alreadyStored = new Set<number>();
  const epochTaken = new Set<number>();
  for (const row of result.records ?? []) {
    const idx = row[0].longValue as number;
    if (row[1].booleanValue) alreadyStored.add(idx);
    if (row[2].booleanValue) epochTaken.add(idx);
  }

  const conflicts: EpochConflict[] = [];
  const seenIds = new Set<string>();
  const claimedInstants = new Set<number>();
  records.forEach((record, i) => {
    if (alreadyStored.has(i) || seenIds.has(record.client_record_id)) {
      return;
    }
    seenIds.add(record.client_record_id);
    const instant = Date.parse(record.start_ts);
    if (epochTaken.has(i) || claimedInstants.has(instant)) {
      conflicts.push({ client_record_id: record.client_record_id, start_ts: new Date(instant).toISOString() });
      return;
    }
    claimedInstants.add(instant);
  });
  return conflicts;
}

function validateRecord(record: unknown, index: number): { value: CanonicalRecord } | { error: string } {
  if (typeof record !== 'object' || record === null) {
    return { error: `records[${index}] must be an object` };
  }
  const r = record as Record<string, unknown>;

  if (typeof r.client_record_id !== 'string' || r.client_record_id.length === 0) {
    return { error: `records[${index}].client_record_id is required and must be a string` };
  }
  if (typeof r.start_ts !== 'string' || Number.isNaN(Date.parse(r.start_ts))) {
    return { error: `records[${index}].start_ts is required and must be a valid date-time string` };
  }
  if (typeof r.is_use !== 'boolean') {
    return { error: `records[${index}].is_use is required and must be a boolean` };
  }
  if (typeof r.distinct_app_count !== 'number' || !Number.isInteger(r.distinct_app_count)) {
    return { error: `records[${index}].distinct_app_count is required and must be an integer` };
  }
  if (typeof r.granularity !== 'string' || !GRANULARITIES.includes(r.granularity)) {
    return { error: `records[${index}].granularity is required and must be one of ${GRANULARITIES.join(', ')}` };
  }

  return {
    value: {
      client_record_id: r.client_record_id,
      start_ts: r.start_ts,
      is_use: r.is_use,
      distinct_app_count: r.distinct_app_count,
      granularity: r.granularity,
    },
  };
}

function validate(body: unknown): { value: CanonicalRecord[] } | { error: string; tooLarge?: true } {
  if (typeof body !== 'object' || body === null) {
    return { error: 'request body must be a JSON object' };
  }
  const b = body as Record<string, unknown>;
  if (!Array.isArray(b.records)) {
    return { error: 'records is required and must be an array' };
  }
  if (b.records.length > MAX_RECORDS_PER_BATCH) {
    return {
      error: `records must contain at most ${MAX_RECORDS_PER_BATCH} items (got ${b.records.length}); split the batch and resend`,
      tooLarge: true,
    };
  }

  const records: CanonicalRecord[] = [];
  for (let i = 0; i < b.records.length; i++) {
    const result = validateRecord(b.records[i], i);
    if ('error' in result) {
      return { error: result.error };
    }
    records.push(result.value);
  }

  return { value: records };
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
    return validation.tooLarge ? payloadTooLarge(validation.error) : badRequest(validation.error);
  }
  const records = validation.value;

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

  if (records.length > 0) {
    const valueRows = records.map(
      (_, i) => `(:client_record_id_${i}, :user_id, :start_ts_${i}, :is_use_${i}, :distinct_app_count_${i}, :granularity_${i})`,
    );

    const parameters: SqlParameter[] = [
      // Postgres has no text -> uuid cast (implicit or assignment) for a
      // Data API parameter that's explicitly typed as text -- only an
      // untyped string *literal* gets coerced directly against the target
      // column during parse analysis. Bound parameters need an explicit hint.
      { name: 'user_id', value: { stringValue: userId }, typeHint: 'UUID' },
      ...records.flatMap((record, i) => [
        ...recordParameters(record, i),
        { name: `is_use_${i}`, value: { booleanValue: record.is_use } },
        { name: `distinct_app_count_${i}`, value: { longValue: record.distinct_app_count } },
        { name: `granularity_${i}`, value: { stringValue: record.granularity } },
      ]),
    ];

    try {
      await executeStatement(
        `INSERT INTO canonical_events (client_record_id, user_id, start_ts, is_use, distinct_app_count, granularity)
         VALUES ${valueRows.join(', ')}
         ON CONFLICT (client_record_id) DO NOTHING`,
        parameters,
      );
    } catch (error) {
      if (isEpochConflict(error)) {
        return epochConflict(
          'one or more records in this batch collide on (user_id, start_ts); nothing from this batch was stored -- shift the listed start_ts values and resend',
          await findEpochConflicts(userId, records),
        );
      }
      throw error;
    }
  }

  const syncedThrough = await getCanonicalSyncedThrough(userId);

  return {
    statusCode: 200,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ received: records.length, synced_through: syncedThrough }),
  };
};
