import type { APIGatewayProxyHandler } from 'aws-lambda';

// Hardcoded deployment-group study cutoff date (YYYY-MM-DD), or null for "no
// cutoff". Independent of GET /cutoff, whose date belongs to the closed
// data-collection study (REQ-DEP-07).
export const CUTOFF_DEPLOYMENT_GROUP: string | null = '2026-11-05';

export const handler: APIGatewayProxyHandler = async () => ({
  statusCode: 200,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ cutoff: CUTOFF_DEPLOYMENT_GROUP }),
});
