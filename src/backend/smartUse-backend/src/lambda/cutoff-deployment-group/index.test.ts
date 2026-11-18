import { handler, CUTOFF_DEPLOYMENT_GROUP } from './index';

test('returns the hardcoded deployment-group cutoff (or null) as JSON', async () => {
  const result: any = await handler({} as any, {} as any, () => {});

  expect(result.statusCode).toBe(200);
  expect(result.headers['Content-Type']).toBe('application/json');
  expect(JSON.parse(result.body)).toEqual({ cutoff: CUTOFF_DEPLOYMENT_GROUP });
});

test('the cutoff is null or a YYYY-MM-DD date', () => {
  expect(CUTOFF_DEPLOYMENT_GROUP === null || /^\d{4}-\d{2}-\d{2}$/.test(CUTOFF_DEPLOYMENT_GROUP)).toBe(true);
});
