import type { APIGatewayProxyHandler } from 'aws-lambda';
import { getCanonicalSyncedThrough } from '../common/db';

// REQ-SYNC-26 -- recovery path for a frontend that has lost
// its local canonical-sync watermark. Not on the happy path (that's the
// synced_through field on a successful POST /sync/canonical); this exists
// purely to re-derive the same value on demand.
export const handler: APIGatewayProxyHandler = async (event) => {
  const userId = event.requestContext.authorizer?.user_id as string;
  const syncedThrough = await getCanonicalSyncedThrough(userId);

  return {
    statusCode: 200,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(
      syncedThrough ?? { client_record_id: null, start_ts: null },
    ),
  };
};
