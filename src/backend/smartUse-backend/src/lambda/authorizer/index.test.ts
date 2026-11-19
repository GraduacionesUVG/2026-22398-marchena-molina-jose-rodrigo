const sendMock = jest.fn();
jest.mock('@aws-sdk/client-rds-data', () => ({
  RDSDataClient: jest.fn().mockImplementation(() => ({ send: sendMock })),
  ExecuteStatementCommand: jest.fn().mockImplementation((input) => input),
}));

import { handler } from './index';

function buildEvent(authorizationToken: string) {
  return {
    type: 'TOKEN',
    authorizationToken,
    methodArn: 'arn:aws:execute-api:us-east-1:111111111111:abc123/prod/POST/sync/canonical',
  } as any;
}

beforeEach(() => {
  sendMock.mockReset();
  process.env.CLUSTER_ARN = 'arn:aws:rds:us-east-1:111111111111:cluster:test';
  process.env.SECRET_ARN = 'arn:aws:secretsmanager:us-east-1:111111111111:secret:test';
  process.env.DATABASE_NAME = 'smartuse';
});

test('allows and attaches the resolved user_id when the token matches a user', async () => {
  sendMock.mockResolvedValue({ records: [[{ stringValue: 'user-123' }]] });

  const result = await handler(buildEvent('Bearer valid-token'), {} as any, () => {});

  expect(result?.principalId).toBe('user-123');
  expect(result?.policyDocument.Statement[0]).toMatchObject({
    Effect: 'Allow',
    Resource: 'arn:aws:execute-api:us-east-1:111111111111:abc123/prod/*/*',
  });
  expect(result?.context?.user_id).toBe('user-123');

  const [sentStatement] = sendMock.mock.calls[0];
  expect(sentStatement.sql).toMatch(/SELECT user_id FROM users WHERE auth_token/i);
  expect(sentStatement.parameters).toEqual([{ name: 'auth_token', value: { stringValue: 'valid-token' } }]);
});

test('throws Unauthorized when no user matches the token', async () => {
  sendMock.mockResolvedValue({ records: [] });

  await expect(handler(buildEvent('Bearer unknown-token'), {} as any, () => {})).rejects.toThrow('Unauthorized');
});

test('throws Unauthorized when the header is missing the Bearer scheme', async () => {
  await expect(handler(buildEvent('unknown-token'), {} as any, () => {})).rejects.toThrow('Unauthorized');
  expect(sendMock).not.toHaveBeenCalled();
});

test('throws Unauthorized when the token is empty', async () => {
  await expect(handler(buildEvent('Bearer '), {} as any, () => {})).rejects.toThrow('Unauthorized');
  expect(sendMock).not.toHaveBeenCalled();
});

test('the allowed resource covers every route, not just the one that triggered this invocation', async () => {
  // API Gateway caches a TOKEN authorizer's response by the raw bearer
  // token alone, and reuses it for every route called with that same
  // token. A Resource scoped to only the invoking methodArn would leave
  // every other route denied for the life of the cache entry.
  sendMock.mockResolvedValue({ records: [[{ stringValue: 'user-123' }]] });

  const event = buildEvent('Bearer valid-token');
  event.methodArn = 'arn:aws:execute-api:us-east-1:111111111111:abc123/prod/GET/sync/canonical/cursor';
  const result = await handler(event, {} as any, () => {});

  expect(result?.policyDocument.Statement[0]).toMatchObject({
    Resource: 'arn:aws:execute-api:us-east-1:111111111111:abc123/prod/*/*',
  });
});
