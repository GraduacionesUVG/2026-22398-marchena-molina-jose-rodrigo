const sendMock = jest.fn().mockResolvedValue({ records: [] });
jest.mock('@aws-sdk/client-rds-data', () => ({
  RDSDataClient: jest.fn().mockImplementation(() => ({ send: sendMock })),
  ExecuteStatementCommand: jest.fn().mockImplementation((input) => input),
}));

import { requireEnv, executeStatement, toDataApiTimestamp, fromDataApiTimestamp, getCanonicalSyncedThrough, recordOptOut, deleteHardOptOutData } from './db';

beforeEach(() => {
  sendMock.mockClear();
  process.env.CLUSTER_ARN = 'arn:aws:rds:us-east-1:111111111111:cluster:test';
  process.env.SECRET_ARN = 'arn:aws:secretsmanager:us-east-1:111111111111:secret:test';
  process.env.DATABASE_NAME = 'smartuse';
});

test('requireEnv returns the value when the env var is set', () => {
  process.env.FOO = 'bar';
  expect(requireEnv('FOO')).toBe('bar');
});

test('requireEnv throws naming the missing env var', () => {
  delete process.env.FOO;
  expect(() => requireEnv('FOO')).toThrow('FOO');
});

test('executeStatement sends resourceArn/secretArn/database from env plus the given sql and parameters', async () => {
  const parameters = [{ name: 'token', value: { stringValue: 'abc' } }];

  await executeStatement('SELECT 1', parameters);

  expect(sendMock).toHaveBeenCalledWith(
    expect.objectContaining({
      resourceArn: process.env.CLUSTER_ARN,
      secretArn: process.env.SECRET_ARN,
      database: process.env.DATABASE_NAME,
      sql: 'SELECT 1',
      parameters,
    }),
  );
});

test('executeStatement rejects when a required env var is missing', async () => {
  delete process.env.CLUSTER_ARN;
  await expect(executeStatement('SELECT 1')).rejects.toThrow('CLUSTER_ARN');
});

test('toDataApiTimestamp strips the T/Z from an ISO date', () => {
  expect(toDataApiTimestamp(new Date('2026-08-29T10:00:00.000Z'))).toBe('2026-08-29 10:00:00.000');
});

test('fromDataApiTimestamp is the inverse of toDataApiTimestamp', () => {
  expect(fromDataApiTimestamp('2026-08-29 10:00:00.000')).toBe('2026-08-29T10:00:00.000Z');
});

test('getCanonicalSyncedThrough returns null when the subject has no canonical rows', async () => {
  sendMock.mockResolvedValueOnce({ records: [] });

  await expect(getCanonicalSyncedThrough('user-123')).resolves.toBeNull();
});

test('getCanonicalSyncedThrough returns the newest row by start_ts, converted back to ISO', async () => {
  sendMock.mockResolvedValueOnce({
    records: [[{ stringValue: 'rec-1' }, { stringValue: '2026-08-29 10:00:00' }]],
  });

  await expect(getCanonicalSyncedThrough('user-123')).resolves.toEqual({
    client_record_id: 'rec-1',
    start_ts: '2026-08-29T10:00:00.000Z',
  });
  expect(sendMock).toHaveBeenCalledWith(
    expect.objectContaining({
      sql: expect.stringMatching(/ORDER BY start_ts DESC LIMIT 1/i),
      parameters: [{ name: 'user_id', value: { stringValue: 'user-123' }, typeHint: 'UUID' }],
    }),
  );
});

test('recordOptOut sets capture_status and opted_out_at, returning the converted timestamp', async () => {
  sendMock.mockResolvedValueOnce({ records: [[{ stringValue: '2026-08-29 10:00:00' }]] });

  await expect(recordOptOut('user-123', 'opted_out_soft')).resolves.toBe('2026-08-29T10:00:00.000Z');

  expect(sendMock).toHaveBeenCalledWith(
    expect.objectContaining({
      sql: expect.stringMatching(/UPDATE users SET capture_status = :status, opted_out_at = now\(\) WHERE user_id = :user_id/i),
      parameters: [
        { name: 'status', value: { stringValue: 'opted_out_soft' } },
        { name: 'user_id', value: { stringValue: 'user-123' }, typeHint: 'UUID' },
      ],
    }),
  );
});

test('deleteHardOptOutData issues exactly one literal, parameterized DELETE per table, scoped to user_id', async () => {
  sendMock.mockResolvedValue({ records: [] });

  await deleteHardOptOutData('user-123');

  const userIdParam = { name: 'user_id', value: { stringValue: 'user-123' }, typeHint: 'UUID' };
  expect(sendMock).toHaveBeenCalledTimes(2);
  expect(sendMock).toHaveBeenNthCalledWith(
    1,
    expect.objectContaining({ sql: 'DELETE FROM canonical_events WHERE user_id = :user_id', parameters: [userIdParam] }),
  );
  expect(sendMock).toHaveBeenNthCalledWith(
    2,
    expect.objectContaining({ sql: 'DELETE FROM inference_metrics WHERE user_id = :user_id', parameters: [userIdParam] }),
  );
});
