const sendMock = jest.fn();
jest.mock('@aws-sdk/client-rds-data', () => ({
  RDSDataClient: jest.fn().mockImplementation(() => ({ send: sendMock })),
  ExecuteStatementCommand: jest.fn().mockImplementation((input) => input),
}));

import { handler } from './index';

function buildEvent(userId = 'user-123') {
  return {
    requestContext: { authorizer: { user_id: userId } },
  } as any;
}

beforeEach(() => {
  sendMock.mockReset();
  process.env.CLUSTER_ARN = 'arn:aws:rds:us-east-1:111111111111:cluster:test';
  process.env.SECRET_ARN = 'arn:aws:secretsmanager:us-east-1:111111111111:secret:test';
  process.env.DATABASE_NAME = 'smartuse';
});

test('returns the subject high-water mark when canonical rows exist', async () => {
  sendMock.mockResolvedValueOnce({
    records: [[{ stringValue: '018f0a1e-1234-7abc-8def-1234567890ab' }, { stringValue: '2026-08-29 10:00:00' }]],
  });

  const result: any = await handler(buildEvent(), {} as any, () => {});

  expect(result.statusCode).toBe(200);
  expect(JSON.parse(result.body)).toEqual({
    client_record_id: '018f0a1e-1234-7abc-8def-1234567890ab',
    start_ts: '2026-08-29T10:00:00.000Z',
  });
  const [statement] = sendMock.mock.calls[0];
  expect(statement.sql).toMatch(/FROM canonical_events/i);
  expect(statement.parameters).toEqual([{ name: 'user_id', value: { stringValue: 'user-123' }, typeHint: 'UUID' }]);
});

test('returns null fields when the subject has no canonical rows yet', async () => {
  sendMock.mockResolvedValueOnce({ records: [] });

  const result: any = await handler(buildEvent(), {} as any, () => {});

  expect(result.statusCode).toBe(200);
  expect(JSON.parse(result.body)).toEqual({ client_record_id: null, start_ts: null });
});
