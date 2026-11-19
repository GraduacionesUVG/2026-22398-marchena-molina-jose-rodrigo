const sendMock = jest.fn();
jest.mock('@aws-sdk/client-rds-data', () => ({
  RDSDataClient: jest.fn().mockImplementation(() => ({ send: sendMock })),
  ExecuteStatementCommand: jest.fn().mockImplementation((input) => input),
}));

import { handler } from './index';

function baseRecord(overrides: Record<string, unknown> = {}) {
  return {
    client_record_id: '018f0a1e-1234-7abc-8def-1234567890ab',
    start_ts: '2026-08-29T10:00:00.000Z',
    is_use: true,
    distinct_app_count: 3,
    granularity: 'small',
    ...overrides,
  };
}

function buildEvent(body: unknown, userId = 'user-123') {
  return {
    body: JSON.stringify(body),
    requestContext: { authorizer: { user_id: userId } },
  } as any;
}

function mockNotExpired() {
  sendMock.mockResolvedValueOnce({ records: [[{ booleanValue: false }]] });
}

function mockCursor(clientRecordId: string | null, startTs?: string) {
  if (clientRecordId === null) {
    sendMock.mockResolvedValueOnce({ records: [] });
    return;
  }
  sendMock.mockResolvedValueOnce({
    records: [[{ stringValue: clientRecordId }, { stringValue: startTs }]],
  });
}

beforeEach(() => {
  sendMock.mockReset();
  process.env.CLUSTER_ARN = 'arn:aws:rds:us-east-1:111111111111:cluster:test';
  process.env.SECRET_ARN = 'arn:aws:secretsmanager:us-east-1:111111111111:secret:test';
  process.env.DATABASE_NAME = 'smartuse';
});

test('inserts a valid batch and returns the received count and synced_through', async () => {
  mockNotExpired();
  sendMock.mockResolvedValueOnce({});
  mockCursor('018f0a1e-1234-7abc-8def-1234567890ab', '2026-08-29 10:00:00');

  const result: any = await handler(buildEvent({ records: [baseRecord()] }), {} as any, () => {});

  expect(result.statusCode).toBe(200);
  expect(JSON.parse(result.body)).toEqual({
    received: 1,
    synced_through: {
      client_record_id: '018f0a1e-1234-7abc-8def-1234567890ab',
      start_ts: '2026-08-29T10:00:00.000Z',
    },
  });
  expect(sendMock).toHaveBeenCalledTimes(3);

  const [insertStatement] = sendMock.mock.calls[1];
  expect(insertStatement.sql).toMatch(/INSERT INTO canonical_events/i);
  expect(insertStatement.sql).toMatch(/ON CONFLICT \(client_record_id\) DO NOTHING/i);
  const paramNames = insertStatement.parameters.map((p: any) => p.name);
  expect(paramNames).toEqual(
    expect.arrayContaining(['user_id', 'client_record_id_0', 'start_ts_0', 'is_use_0', 'distinct_app_count_0', 'granularity_0']),
  );
});

test('inserts multiple records in a single statement with per-row parameter names', async () => {
  mockNotExpired();
  sendMock.mockResolvedValueOnce({});
  mockCursor('id-1', '2026-08-29 10:00:00');

  await handler(
    buildEvent({ records: [baseRecord({ client_record_id: 'id-0' }), baseRecord({ client_record_id: 'id-1' })] }),
    {} as any,
    () => {},
  );

  const [insertStatement] = sendMock.mock.calls[1];
  const paramNames = insertStatement.parameters.map((p: any) => p.name);
  expect(paramNames).toEqual(
    expect.arrayContaining(['client_record_id_0', 'client_record_id_1', 'granularity_0', 'granularity_1']),
  );
  expect(paramNames.filter((n: string) => n === 'user_id')).toHaveLength(1);
});

test('accepts a batch of exactly 500 records', async () => {
  mockNotExpired();
  sendMock.mockResolvedValueOnce({});
  mockCursor('id-499', '2026-08-29 10:00:00');

  const records = Array.from({ length: 500 }, (_, i) => baseRecord({ client_record_id: `id-${i}` }));
  const result: any = await handler(buildEvent({ records }), {} as any, () => {});

  expect(result.statusCode).toBe(200);
  expect(JSON.parse(result.body).received).toBe(500);
  const [insertStatement] = sendMock.mock.calls[1];
  expect(insertStatement.parameters.map((p: any) => p.name)).toContain('client_record_id_499');
  // RDS Data API rejects a `sql` string over 65,536 chars.
  expect(insertStatement.sql.length).toBeLessThanOrEqual(65536);
});

test('returns 413 batch_too_large without touching the database when more than 500 records are sent', async () => {
  const records = Array.from({ length: 501 }, (_, i) => baseRecord({ client_record_id: `id-${i}` }));
  const result: any = await handler(buildEvent({ records }), {} as any, () => {});

  expect(result.statusCode).toBe(413);
  expect(JSON.parse(result.body)).toEqual({
    error: 'batch_too_large',
    message: expect.stringContaining('500'),
  });
  expect(sendMock).not.toHaveBeenCalled();
});

test('returns 200 with received: 0 and skips the insert when records is empty', async () => {
  mockNotExpired();
  mockCursor(null);

  const result: any = await handler(buildEvent({ records: [] }), {} as any, () => {});

  expect(result.statusCode).toBe(200);
  expect(JSON.parse(result.body)).toEqual({ received: 0, synced_through: null });
  expect(sendMock).toHaveBeenCalledTimes(2);
});

test.each([
  ['client_record_id'],
  ['start_ts'],
  ['is_use'],
  ['distinct_app_count'],
  ['granularity'],
])('returns 400 when %s is missing from a record', async (field) => {
  const record = baseRecord();
  delete (record as any)[field];

  const result: any = await handler(buildEvent({ records: [record] }), {} as any, () => {});

  expect(result.statusCode).toBe(400);
  expect(JSON.parse(result.body)).toEqual({
    error: 'validation_error',
    message: expect.stringContaining(field),
  });
  expect(sendMock).not.toHaveBeenCalled();
});

test('returns 400 when granularity is not small or large', async () => {
  const result: any = await handler(buildEvent({ records: [baseRecord({ granularity: 'medium' })] }), {} as any, () => {});

  expect(result.statusCode).toBe(400);
  expect(JSON.parse(result.body).message).toContain('granularity');
  expect(sendMock).not.toHaveBeenCalled();
});

test('returns 400 when records is missing', async () => {
  const result: any = await handler(buildEvent({}), {} as any, () => {});

  expect(result.statusCode).toBe(400);
  expect(JSON.parse(result.body).message).toContain('records');
  expect(sendMock).not.toHaveBeenCalled();
});

test('returns 400 when the body is not valid JSON', async () => {
  const result: any = await handler({ body: '{not json' } as any, {} as any, () => {});

  expect(result.statusCode).toBe(400);
  expect(JSON.parse(result.body).error).toBe('validation_error');
  expect(sendMock).not.toHaveBeenCalled();
});

test('returns 403 without inserting when the capture period has ended', async () => {
  sendMock.mockResolvedValueOnce({ records: [[{ booleanValue: true }]] });

  const result: any = await handler(buildEvent({ records: [baseRecord()] }), {} as any, () => {});

  expect(result.statusCode).toBe(403);
  expect(JSON.parse(result.body).error).toBe('capture_period_ended');
  expect(sendMock).toHaveBeenCalledTimes(1);
});

function mockEpochConflictInsert() {
  const conflictError = new Error('duplicate key value violates unique constraint "uq_canonical_events_user_start"');
  conflictError.name = 'DatabaseErrorException';
  sendMock.mockRejectedValueOnce(conflictError);
}

// Rows of the conflict-detection query: [idx, already_stored, epoch_taken].
function mockConflictFlags(rows: Array<[number, boolean, boolean]>) {
  sendMock.mockResolvedValueOnce({
    records: rows.map(([idx, alreadyStored, epochTaken]) => [
      { longValue: idx },
      { booleanValue: alreadyStored },
      { booleanValue: epochTaken },
    ]),
  });
}

test('returns 409 epoch_conflict listing records that collide with stored data, without querying the cursor', async () => {
  mockNotExpired();
  mockEpochConflictInsert();
  mockConflictFlags([[1, false, true]]);

  const records = [
    baseRecord({ client_record_id: 'id-0', start_ts: '2026-08-29T10:00:00.000Z' }),
    baseRecord({ client_record_id: 'id-1', start_ts: '2026-08-29T10:05:00Z' }),
  ];
  const result: any = await handler(buildEvent({ records }), {} as any, () => {});

  expect(result.statusCode).toBe(409);
  const body = JSON.parse(result.body);
  expect(body.error).toBe('epoch_conflict');
  expect(body.message).toEqual(expect.any(String));
  expect(body.conflicts).toEqual([{ client_record_id: 'id-1', start_ts: '2026-08-29T10:05:00.000Z' }]);
  expect(sendMock).toHaveBeenCalledTimes(3);

  const [detectStatement] = sendMock.mock.calls[2];
  expect(detectStatement.sql).toMatch(/FROM \(VALUES/i);
  const paramNames = detectStatement.parameters.map((p: any) => p.name);
  expect(paramNames).toEqual(
    expect.arrayContaining(['user_id', 'idx_0', 'idx_1', 'client_record_id_0', 'client_record_id_1', 'start_ts_0', 'start_ts_1']),
  );
});

test('409 lists every later record that reuses an instant already claimed earlier in the same batch', async () => {
  mockNotExpired();
  mockEpochConflictInsert();
  mockConflictFlags([]);

  const records = [
    baseRecord({ client_record_id: 'id-0', start_ts: '2026-08-29T10:00:00.000Z' }),
    baseRecord({ client_record_id: 'id-1', start_ts: '2026-08-29T10:00:00Z' }),
    baseRecord({ client_record_id: 'id-2', start_ts: '2026-08-29T10:05:00.000Z' }),
    baseRecord({ client_record_id: 'id-3', start_ts: '2026-08-29T07:00:00-03:00' }),
  ];
  const result: any = await handler(buildEvent({ records }), {} as any, () => {});

  expect(result.statusCode).toBe(409);
  expect(JSON.parse(result.body).conflicts).toEqual([
    { client_record_id: 'id-1', start_ts: '2026-08-29T10:00:00.000Z' },
    { client_record_id: 'id-3', start_ts: '2026-08-29T10:00:00.000Z' },
  ]);
});

test('409 ignores records skipped by client_record_id idempotency (already stored or repeated in the batch)', async () => {
  mockNotExpired();
  mockEpochConflictInsert();
  // id-0 is a resend of a stored record; its instant being "taken" is by itself.
  mockConflictFlags([[0, true, false], [2, false, true]]);

  const records = [
    baseRecord({ client_record_id: 'id-0', start_ts: '2026-08-29T10:00:00.000Z' }),
    baseRecord({ client_record_id: 'id-1', start_ts: '2026-08-29T10:05:00.000Z' }),
    baseRecord({ client_record_id: 'id-2', start_ts: '2026-08-29T10:10:00.000Z' }),
    baseRecord({ client_record_id: 'id-1', start_ts: '2026-08-29T10:05:00.000Z' }),
  ];
  const result: any = await handler(buildEvent({ records }), {} as any, () => {});

  expect(JSON.parse(result.body).conflicts).toEqual([{ client_record_id: 'id-2', start_ts: '2026-08-29T10:10:00.000Z' }]);
});

test('rethrows an insert error that is not the epoch uniqueness violation', async () => {
  mockNotExpired();
  const otherError = new Error('duplicate key value violates unique constraint "canonical_events_pkey"');
  otherError.name = 'DatabaseErrorException';
  sendMock.mockRejectedValueOnce(otherError);

  await expect(handler(buildEvent({ records: [baseRecord()] }), {} as any, () => {})).rejects.toThrow(otherError);
});
