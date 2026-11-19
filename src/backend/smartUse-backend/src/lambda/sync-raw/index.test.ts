const rdsSendMock = jest.fn();
jest.mock('@aws-sdk/client-rds-data', () => ({
  RDSDataClient: jest.fn().mockImplementation(() => ({ send: rdsSendMock })),
  ExecuteStatementCommand: jest.fn().mockImplementation((input) => input),
}));

const s3SendMock = jest.fn();
jest.mock('@aws-sdk/client-s3', () => ({
  S3Client: jest.fn().mockImplementation(() => ({ send: s3SendMock })),
  PutObjectCommand: jest.fn().mockImplementation((input) => input),
}));

import { handler } from './index';

function buildEvent(body: unknown, userId = 'user-123') {
  return {
    body: JSON.stringify(body),
    requestContext: { authorizer: { user_id: userId } },
  } as any;
}

function mockNotExpired() {
  rdsSendMock.mockResolvedValueOnce({ records: [[{ booleanValue: false }]] });
}

beforeEach(() => {
  rdsSendMock.mockReset();
  s3SendMock.mockReset();
  process.env.CLUSTER_ARN = 'arn:aws:rds:us-east-1:111111111111:cluster:test';
  process.env.SECRET_ARN = 'arn:aws:secretsmanager:us-east-1:111111111111:secret:test';
  process.env.DATABASE_NAME = 'smartuse';
  process.env.RAW_BUCKET_NAME = 'test-raw-bucket';
});

test('writes the batch to S3 under a per-user prefix and returns the entry count', async () => {
  mockNotExpired();
  s3SendMock.mockResolvedValueOnce({});

  const result: any = await handler(
    buildEvent({ batch_id: 'batch-1', entries: [{ pkg: 'com.app', ts: 1 }, { pkg: 'com.other', ts: 2 }] }, 'user-123'),
    {} as any,
    () => {},
  );

  expect(result.statusCode).toBe(200);
  expect(JSON.parse(result.body)).toEqual({ received: 2 });

  const [putInput] = s3SendMock.mock.calls[0];
  expect(putInput.Bucket).toBe('test-raw-bucket');
  expect(putInput.Key).toBe('raw/user-123/batch-1.json');
  expect(JSON.parse(putInput.Body)).toMatchObject({
    user_id: 'user-123',
    batch_id: 'batch-1',
    entries: [{ pkg: 'com.app', ts: 1 }, { pkg: 'com.other', ts: 2 }],
  });
});

test('accepts non-object entries verbatim (e.g. XML strings) without shape validation', async () => {
  mockNotExpired();
  s3SendMock.mockResolvedValueOnce({});

  const result: any = await handler(
    buildEvent({ batch_id: 'batch-2', entries: ['<event ts="1"/>', '<event ts="2"/>'] }),
    {} as any,
    () => {},
  );

  expect(result.statusCode).toBe(200);
  const [putInput] = s3SendMock.mock.calls[0];
  expect(JSON.parse(putInput.Body).entries).toEqual(['<event ts="1"/>', '<event ts="2"/>']);
});

test('a retried batch_id overwrites the same key', async () => {
  mockNotExpired();
  s3SendMock.mockResolvedValueOnce({});

  await handler(buildEvent({ batch_id: 'batch-1', entries: [{ a: 1 }] }, 'user-123'), {} as any, () => {});

  mockNotExpired();
  s3SendMock.mockResolvedValueOnce({});

  await handler(buildEvent({ batch_id: 'batch-1', entries: [{ a: 1 }] }, 'user-123'), {} as any, () => {});

  expect(s3SendMock.mock.calls[0][0].Key).toBe(s3SendMock.mock.calls[1][0].Key);
});

test('returns 400 when batch_id is missing', async () => {
  const result: any = await handler(buildEvent({ entries: [] }), {} as any, () => {});

  expect(result.statusCode).toBe(400);
  expect(JSON.parse(result.body)).toEqual({
    error: 'validation_error',
    message: expect.stringContaining('batch_id'),
  });
  expect(s3SendMock).not.toHaveBeenCalled();
  expect(rdsSendMock).not.toHaveBeenCalled();
});

test('returns 400 when entries is missing or not an array', async () => {
  const result: any = await handler(buildEvent({ batch_id: 'batch-1', entries: 'oops' }), {} as any, () => {});

  expect(result.statusCode).toBe(400);
  expect(JSON.parse(result.body).message).toContain('entries');
  expect(s3SendMock).not.toHaveBeenCalled();
});

test('returns 400 when the body is not valid JSON', async () => {
  const result: any = await handler({ body: '{not json' } as any, {} as any, () => {});

  expect(result.statusCode).toBe(400);
  expect(JSON.parse(result.body).error).toBe('validation_error');
  expect(s3SendMock).not.toHaveBeenCalled();
  expect(rdsSendMock).not.toHaveBeenCalled();
});

test('returns 403 without writing to S3 when the capture period has ended', async () => {
  rdsSendMock.mockResolvedValueOnce({ records: [[{ booleanValue: true }]] });

  const result: any = await handler(buildEvent({ batch_id: 'batch-1', entries: [] }), {} as any, () => {});

  expect(result.statusCode).toBe(403);
  expect(JSON.parse(result.body).error).toBe('capture_period_ended');
  expect(s3SendMock).not.toHaveBeenCalled();
});
