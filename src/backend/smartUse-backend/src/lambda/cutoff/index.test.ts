import { handler, CUTOFF } from './index';

test('returns the hardcoded cutoff (or null) as JSON', async () => {
  const result: any = await handler({} as any, {} as any, () => {});

  expect(result.statusCode).toBe(200);
  expect(result.headers['Content-Type']).toBe('application/json');
  expect(JSON.parse(result.body)).toEqual({ cutoff: CUTOFF });
});
