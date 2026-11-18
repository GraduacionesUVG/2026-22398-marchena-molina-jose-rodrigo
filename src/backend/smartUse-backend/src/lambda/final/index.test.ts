const sendMock = jest.fn();
jest.mock('@aws-sdk/client-rds-data', () => ({
  RDSDataClient: jest.fn().mockImplementation(() => ({ send: sendMock })),
  ExecuteStatementCommand: jest.fn().mockImplementation((input) => input),
}));

import { handler } from './index';

function validBody(overrides: Record<string, unknown> = {}): any {
  return {
    end_reason: 'completed',
    forms: { usability: { version: 2, answers: { d1_1: 5, d8_3: 'texto libre' } } },
    metrics: [
      { decision_point: 'screen_on', duration_ms: 412, battery_delta_pct: 0, current_mean: -310000, peak_rss_kb: 98304 },
      { decision_point: 'active_use', duration_ms: 35 },
    ],
    ...overrides,
  };
}

function buildEvent(body: unknown, userId = 'user-123') {
  return {
    body: typeof body === 'string' ? body : JSON.stringify(body),
    requestContext: { authorizer: { user_id: userId } },
  } as any;
}

function mockNoExistingSend() {
  sendMock.mockResolvedValueOnce({ records: [] });
}

function mockExistingSend() {
  sendMock.mockResolvedValueOnce({ records: [[{ longValue: 1 }]] });
}

function mockStored(userId = 'user-123') {
  sendMock.mockResolvedValueOnce({ records: [[{ stringValue: userId }]] });
}

function storeCall() {
  return sendMock.mock.calls[1][0];
}

function param(statement: any, name: string) {
  return statement.parameters.find((p: any) => p.name === name).value;
}

beforeEach(() => {
  sendMock.mockReset();
  process.env.CLUSTER_ARN = 'arn:aws:rds:us-east-1:111111111111:cluster:test';
  process.env.SECRET_ARN = 'arn:aws:secretsmanager:us-east-1:111111111111:secret:test';
  process.env.DATABASE_NAME = 'smartuse';
});

test('stores a valid send and answers 201', async () => {
  mockNoExistingSend();
  mockStored();

  const result: any = await handler(buildEvent(validBody()), {} as any, () => {});

  expect(result.statusCode).toBe(201);
  expect(JSON.parse(result.body)).toEqual({ status: 'stored' });
  expect(sendMock).toHaveBeenCalledTimes(2);
});

test('returns already_received without validating when a send already exists', async () => {
  mockExistingSend();

  const result: any = await handler(buildEvent({ end_reason: 'nope' }), {} as any, () => {});

  expect(result.statusCode).toBe(200);
  expect(JSON.parse(result.body)).toEqual({ status: 'already_received' });
  expect(sendMock).toHaveBeenCalledTimes(1);
});

test('answers already_received when the insert loses a race', async () => {
  mockNoExistingSend();
  sendMock.mockResolvedValueOnce({ records: [] });

  const result: any = await handler(buildEvent(validBody()), {} as any, () => {});

  expect(result.statusCode).toBe(200);
  expect(JSON.parse(result.body)).toEqual({ status: 'already_received' });
});

test('rejects an unusable body with 400 invalid_body', async () => {
  mockNoExistingSend();

  const result: any = await handler(buildEvent({ end_reason: 'nope', forms: {} }), {} as any, () => {});

  expect(result.statusCode).toBe(400);
  expect(JSON.parse(result.body)).toEqual({ error: 'invalid_body', message: expect.stringContaining('end_reason') });
  expect(sendMock).toHaveBeenCalledTimes(1);
});

test('rejects non-JSON with 400 invalid_body', async () => {
  mockNoExistingSend();

  const result: any = await handler(buildEvent('{not json'), {} as any, () => {});

  expect(result.statusCode).toBe(400);
  expect(JSON.parse(result.body).error).toBe('invalid_body');
});

test('stores valid metrics and counts skipped ones', async () => {
  mockNoExistingSend();
  mockStored();
  const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});

  const body = validBody();
  body.metrics.push({ decision_point: 'x', duration_ms: 1 });
  const result: any = await handler(buildEvent(body), {} as any, () => {});

  expect(result.statusCode).toBe(201);
  expect(param(storeCall(), 'metrics_stored')).toEqual({ longValue: 2 });
  expect(param(storeCall(), 'metrics_skipped')).toEqual({ longValue: 1 });
  expect(JSON.parse(param(storeCall(), 'metrics').stringValue)).toHaveLength(2);
  expect(warn).toHaveBeenCalled();
  warn.mockRestore();
});

test('passes answers and metrics as JSON parameters, never in the SQL text', async () => {
  mockNoExistingSend();
  mockStored();
  const hostile = "'; DROP TABLE users; --";
  const body = validBody({
    metrics: Array.from({ length: 10000 }, (_, i) => ({ decision_point: 'screen_on', duration_ms: i })),
  });
  body.forms.usability.answers.d8_3 = hostile;

  await handler(buildEvent(body), {} as any, () => {});

  const statement = storeCall();
  expect(statement.sql.length).toBeLessThan(4000);
  expect(statement.sql).not.toContain('DROP');
  expect(param(statement, 'answers').stringValue).toContain(JSON.stringify(hostile).slice(1, -1));
  expect(JSON.parse(param(statement, 'metrics').stringValue)).toHaveLength(10000);
});

test('does not check capture_end_ts or capture_status', async () => {
  mockNoExistingSend();
  mockStored();

  await handler(buildEvent(validBody()), {} as any, () => {});

  for (const [statement] of sendMock.mock.calls) {
    expect(statement.sql).not.toMatch(/capture_end_ts|capture_status/);
  }
});

test('lets a database error surface as a 5xx', async () => {
  mockNoExistingSend();
  sendMock.mockRejectedValueOnce(new Error('boom'));

  await expect(handler(buildEvent(validBody()), {} as any, () => {})).rejects.toThrow('boom');
});
