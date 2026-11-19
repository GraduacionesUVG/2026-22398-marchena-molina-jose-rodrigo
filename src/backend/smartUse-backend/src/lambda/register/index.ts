import type { APIGatewayProxyHandler } from 'aws-lambda';
import * as crypto from 'crypto';
import { executeStatement, toDataApiTimestamp } from '../common/db';

const EXPERIMENT_GROUPS = ['control', 'rate', 'inference'];
const CAPTURE_WINDOW_DAYS = 21;

interface RegisterRequest {
  age_at_signup: number;
  gender: string;
  university_major: string;
  test_score: number;
  experiment_group: string;
  experiment_config?: Record<string, unknown> | null;
}

function badRequest(message: string) {
  return {
    statusCode: 400,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ error: 'validation_error', message }),
  };
}

function validate(body: unknown): { value: RegisterRequest } | { error: string } {
  if (typeof body !== 'object' || body === null) {
    return { error: 'request body must be a JSON object' };
  }

  const b = body as Record<string, unknown>;

  if (typeof b.age_at_signup !== 'number') {
    return { error: 'age_at_signup is required and must be a number' };
  }
  if (typeof b.gender !== 'string' || b.gender.length === 0) {
    return { error: 'gender is required and must be a string' };
  }
  if (typeof b.university_major !== 'string' || b.university_major.length === 0) {
    return { error: 'university_major is required and must be a string' };
  }
  if (typeof b.test_score !== 'number') {
    return { error: 'test_score is required and must be a number' };
  }
  if (typeof b.experiment_group !== 'string' || !EXPERIMENT_GROUPS.includes(b.experiment_group)) {
    return { error: `experiment_group is required and must be one of ${EXPERIMENT_GROUPS.join(', ')}` };
  }
  // Deployment group only (REQ-DEP-02): test_score is
  // the SAS-SV screening score. Other groups keep accepting any number.
  if (b.experiment_group === 'inference' && !(Number.isInteger(b.test_score) && b.test_score >= 10 && b.test_score <= 60)) {
    return { error: 'test_score must be an integer 10-60 for experiment_group inference' };
  }
  if (b.experiment_config !== undefined && b.experiment_config !== null && typeof b.experiment_config !== 'object') {
    return { error: 'experiment_config must be an object when present' };
  }

  return {
    value: {
      age_at_signup: b.age_at_signup,
      gender: b.gender,
      university_major: b.university_major,
      test_score: b.test_score,
      experiment_group: b.experiment_group,
      experiment_config: (b.experiment_config as Record<string, unknown> | null | undefined) ?? null,
    },
  };
}

export const handler: APIGatewayProxyHandler = async (event) => {
  let parsedBody: unknown;
  try {
    parsedBody = JSON.parse(event.body ?? '');
  } catch {
    return badRequest('request body must be valid JSON');
  }

  const validation = validate(parsedBody);
  if ('error' in validation) {
    return badRequest(validation.error);
  }
  const request = validation.value;

  const authToken = crypto.randomBytes(32).toString('base64url');
  const signupTs = new Date();
  const captureEndTs = new Date(signupTs.getTime() + CAPTURE_WINDOW_DAYS * 24 * 60 * 60 * 1000);

  const result = await executeStatement(
    `INSERT INTO users (age_at_signup, gender, university_major, test_score, experiment_group, experiment_config, signup_ts, capture_end_ts, auth_token)
     VALUES (:age_at_signup, :gender, :university_major, :test_score, :experiment_group, :experiment_config, :signup_ts, :capture_end_ts, :auth_token)
     RETURNING user_id, auth_token`,
    [
      { name: 'age_at_signup', value: { longValue: request.age_at_signup } },
      { name: 'gender', value: { stringValue: request.gender } },
      { name: 'university_major', value: { stringValue: request.university_major } },
      { name: 'test_score', value: { doubleValue: request.test_score } },
      { name: 'experiment_group', value: { stringValue: request.experiment_group } },
      request.experiment_config
        ? { name: 'experiment_config', value: { stringValue: JSON.stringify(request.experiment_config) }, typeHint: 'JSON' }
        : { name: 'experiment_config', value: { isNull: true } },
      { name: 'signup_ts', value: { stringValue: toDataApiTimestamp(signupTs) }, typeHint: 'TIMESTAMP' },
      { name: 'capture_end_ts', value: { stringValue: toDataApiTimestamp(captureEndTs) }, typeHint: 'TIMESTAMP' },
      { name: 'auth_token', value: { stringValue: authToken } },
    ],
  );

  const [userIdField, authTokenField] = result.records![0];

  return {
    statusCode: 201,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      user_id: userIdField.stringValue,
      auth_token: authTokenField.stringValue,
    }),
  };
};
