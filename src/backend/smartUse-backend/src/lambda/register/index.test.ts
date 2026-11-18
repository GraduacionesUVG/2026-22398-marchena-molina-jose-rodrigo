const sendMock = jest.fn();
jest.mock('@aws-sdk/client-rds-data', () => ({
  RDSDataClient: jest.fn().mockImplementation(() => ({ send: sendMock })),
  ExecuteStatementCommand: jest.fn().mockImplementation((input) => input),
}));

import { handler } from './index';

function baseBody(overrides: Record<string, unknown> = {}) {
  return {
    age_at_signup: 21,
    gender: 'female',
    university_major: 'Computer Science',
    test_score: 7.5,
    experiment_group: 'control',
    ...overrides,
  };
}

function buildEvent(body: unknown) {
  return { body: JSON.stringify(body) } as any;
}

beforeEach(() => {
  sendMock.mockReset();
  process.env.CLUSTER_ARN = 'arn:aws:rds:us-east-1:111111111111:cluster:test';
  process.env.SECRET_ARN = 'arn:aws:secretsmanager:us-east-1:111111111111:secret:test';
  process.env.DATABASE_NAME = 'smartuse';
});

test('registers a valid participant and returns the generated user_id and auth_token', async () => {
  sendMock.mockResolvedValue({
    records: [[{ stringValue: 'a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1' }, { stringValue: 'generated-token' }]],
  });

  const result: any = await handler(buildEvent(baseBody()), {} as any, () => {});

  expect(result.statusCode).toBe(201);
  expect(JSON.parse(result.body)).toEqual({
    user_id: 'a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1',
    auth_token: 'generated-token',
  });
});

test('sends the INSERT with the fields from the request body', async () => {
  sendMock.mockResolvedValue({
    records: [[{ stringValue: 'user-id' }, { stringValue: 'token' }]],
  });

  await handler(buildEvent(baseBody({ gender: 'male', experiment_group: 'rate', experiment_config: { intervention_probability: 0.5 } })), {} as any, () => {});

  const [sentStatement] = sendMock.mock.calls[0];
  const paramNames = sentStatement.parameters.map((p: any) => p.name);
  expect(sentStatement.sql).toMatch(/INSERT INTO users/i);
  expect(paramNames).toEqual(
    expect.arrayContaining(['age_at_signup', 'gender', 'university_major', 'test_score', 'experiment_group', 'experiment_config', 'signup_ts', 'capture_end_ts', 'auth_token']),
  );
});

test.each([
  ['age_at_signup'],
  ['gender'],
  ['university_major'],
  ['test_score'],
  ['experiment_group'],
])('returns 400 when %s is missing', async (field) => {
  const body = baseBody();
  delete (body as any)[field];

  const result: any = await handler(buildEvent(body), {} as any, () => {});

  expect(result.statusCode).toBe(400);
  expect(JSON.parse(result.body)).toEqual({
    error: 'validation_error',
    message: expect.stringContaining(field),
  });
  expect(sendMock).not.toHaveBeenCalled();
});

test('returns 400 when experiment_group is not one of control/rate/inference', async () => {
  const result: any = await handler(buildEvent(baseBody({ experiment_group: 'bogus' })), {} as any, () => {});

  expect(result.statusCode).toBe(400);
  expect(JSON.parse(result.body)).toEqual({
    error: 'validation_error',
    message: expect.stringContaining('experiment_group'),
  });
  expect(sendMock).not.toHaveBeenCalled();
});

test('returns 400 when the body is not valid JSON', async () => {
  const result: any = await handler({ body: '{not json' } as any, {} as any, () => {});

  expect(result.statusCode).toBe(400);
  expect(JSON.parse(result.body).error).toBe('validation_error');
  expect(sendMock).not.toHaveBeenCalled();
});

function mockInsertOk() {
  sendMock.mockResolvedValue({
    records: [[{ stringValue: 'user-id' }, { stringValue: 'token' }]],
  });
}

test.each([9, 61, 37.5])('rejects inference test_score %p with 400 (SAS-SV must be an integer 10-60)', async (score) => {
  const result: any = await handler(buildEvent(baseBody({ experiment_group: 'inference', test_score: score })), {} as any, () => {});

  expect(result.statusCode).toBe(400);
  expect(JSON.parse(result.body)).toEqual({
    error: 'validation_error',
    message: expect.stringContaining('test_score'),
  });
  expect(sendMock).not.toHaveBeenCalled();
});

test.each([10, 60, 37])('accepts inference test_score %p', async (score) => {
  mockInsertOk();

  const result: any = await handler(buildEvent(baseBody({ experiment_group: 'inference', test_score: score })), {} as any, () => {});

  expect(result.statusCode).toBe(201);
});

test('accepts a non-integer test_score for control (data-collection app unchanged)', async () => {
  mockInsertOk();

  const result: any = await handler(buildEvent(baseBody({ test_score: 7.5, experiment_group: 'control' })), {} as any, () => {});

  expect(result.statusCode).toBe(201);
});
