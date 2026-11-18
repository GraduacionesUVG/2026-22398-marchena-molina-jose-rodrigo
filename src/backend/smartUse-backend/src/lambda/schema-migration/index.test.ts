const sendMock = jest.fn().mockResolvedValue({});
jest.mock('@aws-sdk/client-rds-data', () => ({
  RDSDataClient: jest.fn().mockImplementation(() => ({ send: sendMock })),
  ExecuteStatementCommand: jest.fn().mockImplementation((input) => input),
}));

import { handler } from './index';

const baseEvent = {
  ResourceType: 'Custom::SchemaMigration',
  ServiceToken: 'arn:aws:lambda:us-east-1:111111111111:function:test',
  ResponseURL: 'https://example.com',
  StackId: 'stack',
  RequestId: 'req',
  LogicalResourceId: 'logical',
};

beforeEach(() => {
  sendMock.mockClear();
  process.env.CLUSTER_ARN = 'arn:aws:rds:us-east-1:111111111111:cluster:test';
  process.env.SECRET_ARN = 'arn:aws:secretsmanager:us-east-1:111111111111:secret:test';
  process.env.DATABASE_NAME = 'smartuse';
});

test('runs every statement in schema.sql on Create', async () => {
  const event = { ...baseEvent, RequestType: 'Create' } as any;
  await handler(event, {} as any, {} as any);
  expect(sendMock.mock.calls.length).toBeGreaterThanOrEqual(8);
});

test('runs every statement again on Update', async () => {
  const event = { ...baseEvent, RequestType: 'Update', PhysicalResourceId: 'schema-migration' } as any;
  await handler(event, {} as any, {} as any);
  expect(sendMock).toHaveBeenCalled();
});

test('does nothing on Delete', async () => {
  const event = { ...baseEvent, RequestType: 'Delete', PhysicalResourceId: 'schema-migration' } as any;
  const result = await handler(event, {} as any, {} as any);
  expect(sendMock).not.toHaveBeenCalled();
  expect(result).toEqual({ PhysicalResourceId: 'schema-migration' });
});

test('throws when a required env var is missing', async () => {
  delete process.env.CLUSTER_ARN;
  const event = { ...baseEvent, RequestType: 'Create' } as any;
  await expect(handler(event, {} as any, {} as any)).rejects.toThrow('CLUSTER_ARN');
});
