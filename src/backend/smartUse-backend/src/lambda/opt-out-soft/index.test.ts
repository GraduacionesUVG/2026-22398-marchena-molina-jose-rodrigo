const rdsSendMock = jest.fn();
jest.mock('@aws-sdk/client-rds-data', () => ({
  RDSDataClient: jest.fn().mockImplementation(() => ({ send: rdsSendMock })),
  ExecuteStatementCommand: jest.fn().mockImplementation((input) => input),
}));

import { handler } from './index';

function buildEvent(userId = 'user-123') {
  return { requestContext: { authorizer: { user_id: userId } } } as any;
}

beforeEach(() => {
  rdsSendMock.mockReset();
  process.env.CLUSTER_ARN = 'arn:aws:rds:us-east-1:111111111111:cluster:test';
  process.env.SECRET_ARN = 'arn:aws:secretsmanager:us-east-1:111111111111:secret:test';
  process.env.DATABASE_NAME = 'smartuse';
});

test('marks the caller opted_out_soft and returns the ack, without touching any other table', async () => {
  rdsSendMock.mockResolvedValueOnce({ records: [[{ stringValue: '2026-08-29 10:00:00' }]] });

  const result: any = await handler(buildEvent('user-123'), {} as any, () => {});

  expect(result.statusCode).toBe(200);
  expect(JSON.parse(result.body)).toEqual({
    capture_status: 'opted_out_soft',
    opted_out_at: '2026-08-29T10:00:00.000Z',
  });

  expect(rdsSendMock).toHaveBeenCalledTimes(1);
  const [input] = rdsSendMock.mock.calls[0];
  expect(input.sql).toMatch(/UPDATE users SET capture_status = :status, opted_out_at = now\(\)/i);
  expect(input.parameters).toEqual([
    { name: 'status', value: { stringValue: 'opted_out_soft' } },
    { name: 'user_id', value: { stringValue: 'user-123' }, typeHint: 'UUID' },
  ]);
});
