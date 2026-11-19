const rdsSendMock = jest.fn();
jest.mock('@aws-sdk/client-rds-data', () => ({
  RDSDataClient: jest.fn().mockImplementation(() => ({ send: rdsSendMock })),
  ExecuteStatementCommand: jest.fn().mockImplementation((input) => input),
}));

const s3SendMock = jest.fn();
jest.mock('@aws-sdk/client-s3', () => ({
  S3Client: jest.fn().mockImplementation(() => ({ send: s3SendMock })),
  ListObjectsV2Command: jest.fn().mockImplementation((input) => ({ __cmd: 'list', ...input })),
  DeleteObjectsCommand: jest.fn().mockImplementation((input) => ({ __cmd: 'delete', ...input })),
}));

import { handler } from './index';

function buildEvent(userId = 'user-123') {
  return { requestContext: { authorizer: { user_id: userId } } } as any;
}

function mockDbDeletesThenAck() {
  rdsSendMock
    .mockResolvedValueOnce({ records: [] }) // DELETE canonical_events
    .mockResolvedValueOnce({ records: [] }) // DELETE inference_metrics
    .mockResolvedValueOnce({ records: [[{ stringValue: '2026-08-29 10:00:00' }]] }); // UPDATE users
}

beforeEach(() => {
  rdsSendMock.mockReset();
  s3SendMock.mockReset();
  process.env.CLUSTER_ARN = 'arn:aws:rds:us-east-1:111111111111:cluster:test';
  process.env.SECRET_ARN = 'arn:aws:secretsmanager:us-east-1:111111111111:secret:test';
  process.env.DATABASE_NAME = 'smartuse';
  process.env.RAW_BUCKET_NAME = 'test-raw-bucket';
});

test('deletes canonical_events, inference_metrics, and the raw S3 objects for this user, then marks opted_out_hard last', async () => {
  mockDbDeletesThenAck();
  s3SendMock
    .mockResolvedValueOnce({
      Contents: [{ Key: 'raw/user-123/batch-1.json' }, { Key: 'raw/user-123/batch-2.json' }],
      IsTruncated: false,
    })
    .mockResolvedValueOnce({});

  const result: any = await handler(buildEvent('user-123'), {} as any, () => {});

  expect(result.statusCode).toBe(200);
  expect(JSON.parse(result.body)).toEqual({
    capture_status: 'opted_out_hard',
    opted_out_at: '2026-08-29T10:00:00.000Z',
  });

  // exactly these 3 DB statements, in this order, each scoped to user-123 --
  // never a bare DELETE and never a statement touching the users table's rows
  expect(rdsSendMock).toHaveBeenCalledTimes(3);
  expect(rdsSendMock.mock.calls[0][0]).toMatchObject({ sql: 'DELETE FROM canonical_events WHERE user_id = :user_id' });
  expect(rdsSendMock.mock.calls[1][0]).toMatchObject({ sql: 'DELETE FROM inference_metrics WHERE user_id = :user_id' });
  expect(rdsSendMock.mock.calls[2][0].sql).toMatch(/UPDATE users SET capture_status = :status/i);

  const listInput = s3SendMock.mock.calls[0][0];
  expect(listInput.Bucket).toBe('test-raw-bucket');
  expect(listInput.Prefix).toBe('raw/user-123/');

  const deleteInput = s3SendMock.mock.calls[1][0];
  expect(deleteInput.Bucket).toBe('test-raw-bucket');
  expect(deleteInput.Delete.Objects).toEqual([
    { Key: 'raw/user-123/batch-1.json' },
    { Key: 'raw/user-123/batch-2.json' },
  ]);
});

test('does not call DeleteObjects when the user has no raw S3 objects', async () => {
  mockDbDeletesThenAck();
  s3SendMock.mockResolvedValueOnce({ Contents: [], IsTruncated: false });

  await handler(buildEvent('user-123'), {} as any, () => {});

  expect(s3SendMock).toHaveBeenCalledTimes(1);
});

test('pages through ListObjectsV2 when the raw dump listing is truncated', async () => {
  mockDbDeletesThenAck();
  s3SendMock
    .mockResolvedValueOnce({ Contents: [{ Key: 'raw/user-123/batch-1.json' }], IsTruncated: true, NextContinuationToken: 'token-1' })
    .mockResolvedValueOnce({}) // delete page 1
    .mockResolvedValueOnce({ Contents: [{ Key: 'raw/user-123/batch-2.json' }], IsTruncated: false })
    .mockResolvedValueOnce({}); // delete page 2

  await handler(buildEvent('user-123'), {} as any, () => {});

  expect(s3SendMock).toHaveBeenCalledTimes(4);
  expect(s3SendMock.mock.calls[0][0].ContinuationToken).toBeUndefined();
  expect(s3SendMock.mock.calls[2][0].ContinuationToken).toBe('token-1');
});

test('only ever lists/deletes under this user_id prefix, never a bucket-wide operation', async () => {
  mockDbDeletesThenAck();
  s3SendMock.mockResolvedValueOnce({ Contents: [{ Key: 'raw/user-123/batch-1.json' }], IsTruncated: false }).mockResolvedValueOnce({});

  await handler(buildEvent('user-123'), {} as any, () => {});

  for (const call of s3SendMock.mock.calls) {
    const input = call[0];
    if (input.__cmd === 'list') {
      expect(input.Prefix).toBe('raw/user-123/');
    }
    if (input.__cmd === 'delete') {
      for (const obj of input.Delete.Objects) {
        expect(obj.Key.startsWith('raw/user-123/')).toBe(true);
      }
    }
  }
});
