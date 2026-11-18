import type { APIGatewayProxyHandler } from 'aws-lambda';

export function notImplementedHandler(endpointName: string): APIGatewayProxyHandler {
  return async () => ({
    statusCode: 501,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      error: 'not_implemented',
      message: `${endpointName} is not implemented yet`,
    }),
  });
}
