import type { APIGatewayProxyHandler } from 'aws-lambda';

// Hardcoded study cutoff date (YYYY-MM-DD), or null for "no cutoff".
export const CUTOFF: string | null = '2026-10-19';

export const handler: APIGatewayProxyHandler = async () => ({
  statusCode: 200,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ cutoff: CUTOFF }),
});
