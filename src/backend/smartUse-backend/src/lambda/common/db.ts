import { RDSDataClient, ExecuteStatementCommand, SqlParameter } from '@aws-sdk/client-rds-data';

const rdsData = new RDSDataClient({});

export function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} environment variable is required`);
  }
  return value;
}

export async function executeStatement(sql: string, parameters?: SqlParameter[]) {
  const resourceArn = requireEnv('CLUSTER_ARN');
  const secretArn = requireEnv('SECRET_ARN');
  const database = requireEnv('DATABASE_NAME');

  return rdsData.send(
    new ExecuteStatementCommand({
      resourceArn,
      secretArn,
      database,
      sql,
      parameters,
      includeResultMetadata: true,
    }),
  );
}

export function toDataApiTimestamp(date: Date): string {
  return date.toISOString().replace('T', ' ').replace('Z', '');
}

export function fromDataApiTimestamp(value: string): string {
  return new Date(`${value.replace(' ', 'T')}Z`).toISOString();
}

export interface SyncedThrough {
  client_record_id: string;
  start_ts: string;
}

// Shared by POST /sync/canonical (returned as synced_through after a
// successful batch) and GET /sync/canonical/cursor (the recovery path) --
// REQ-SYNC-24/26. Always a fresh read of stored state, never
// inferred from a request payload.
export async function getCanonicalSyncedThrough(userId: string): Promise<SyncedThrough | null> {
  const result = await executeStatement(
    'SELECT client_record_id, start_ts FROM canonical_events WHERE user_id = :user_id ORDER BY start_ts DESC LIMIT 1',
    // Postgres has no bare `uuid = text` operator -- only an assignment cast
    // (which INSERT/UPDATE target columns get for free, but a WHERE equality
    // comparison does not) -- so this comparison needs an explicit type hint.
    [{ name: 'user_id', value: { stringValue: userId }, typeHint: 'UUID' }],
  );
  const row = result.records?.[0];
  if (!row) {
    return null;
  }
  return {
    client_record_id: row[0].stringValue as string,
    start_ts: fromDataApiTimestamp(row[1].stringValue as string),
  };
}

export type OptOutStatus = 'opted_out_soft' | 'opted_out_hard';

// Only ever UPDATEs the users row -- never DELETEs it (the row is kept so
// admin participant counts stay accurate, REQ-OPT-02).
export async function recordOptOut(userId: string, status: OptOutStatus): Promise<string> {
  const result = await executeStatement(
    'UPDATE users SET capture_status = :status, opted_out_at = now() WHERE user_id = :user_id RETURNING opted_out_at',
    [
      { name: 'status', value: { stringValue: status } },
      { name: 'user_id', value: { stringValue: userId }, typeHint: 'UUID' },
    ],
  );
  return fromDataApiTimestamp(result.records![0][0].stringValue as string);
}

// Each DELETE below is a literal, hardcoded SQL string with exactly one
// bound, UUID-typed parameter -- no string concatenation and no dynamic
// table/column names, so there is no code path here that could touch a
// table or rows other than this user_id's in canonical_events/inference_metrics.
export async function deleteHardOptOutData(userId: string): Promise<void> {
  const userIdParam: SqlParameter = { name: 'user_id', value: { stringValue: userId }, typeHint: 'UUID' };
  await executeStatement('DELETE FROM canonical_events WHERE user_id = :user_id', [userIdParam]);
  await executeStatement('DELETE FROM inference_metrics WHERE user_id = :user_id', [userIdParam]);
}
