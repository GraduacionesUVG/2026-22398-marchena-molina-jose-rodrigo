import { notImplementedHandler } from './not-implemented';

test('returns a 501 with a structured error body', async () => {
  const handler = notImplementedHandler('register');
  const result: any = await handler({} as any, {} as any, () => {});

  expect(result.statusCode).toBe(501);
  expect(JSON.parse(result.body)).toEqual({
    error: 'not_implemented',
    message: 'register is not implemented yet',
  });
});
