import type { APIGatewayProxyHandler } from 'aws-lambda';
import { recordOptOut } from '../common/db';

export const handler: APIGatewayProxyHandler = async (event) => {
  const userId = event.requestContext.authorizer?.user_id as string;

  const optedOutAt = await recordOptOut(userId, 'opted_out_soft');

  return {
    statusCode: 200,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ capture_status: 'opted_out_soft', opted_out_at: optedOutAt }),
  };
};
