import type { APIGatewayProxyHandler } from 'aws-lambda';
import { S3Client, ListObjectsV2Command, DeleteObjectsCommand } from '@aws-sdk/client-s3';
import { requireEnv, recordOptOut, deleteHardOptOutData } from '../common/db';

const s3 = new S3Client({});

// Only ever touches keys it just listed under this exact per-user prefix --
// never a bucket-wide or wildcard delete.
async function deleteRawDumps(userId: string): Promise<void> {
  const bucket = requireEnv('RAW_BUCKET_NAME');
  const prefix = `raw/${userId}/`;
  let continuationToken: string | undefined;

  do {
    const listed = await s3.send(
      new ListObjectsV2Command({ Bucket: bucket, Prefix: prefix, ContinuationToken: continuationToken }),
    );
    const keys = (listed.Contents ?? []).flatMap((obj) => (obj.Key ? [obj.Key] : []));

    if (keys.length > 0) {
      await s3.send(
        new DeleteObjectsCommand({
          Bucket: bucket,
          Delete: { Objects: keys.map((Key) => ({ Key })) },
        }),
      );
    }

    continuationToken = listed.IsTruncated ? listed.NextContinuationToken : undefined;
  } while (continuationToken);
}

export const handler: APIGatewayProxyHandler = async (event) => {
  const userId = event.requestContext.authorizer?.user_id as string;

  // DB rows and S3 objects first; the users row flips to opted_out_hard only
  // once the underlying deletes have actually run. Every step here is
  // idempotent, so a retry after a partial failure is safe.
  await deleteHardOptOutData(userId);
  await deleteRawDumps(userId);
  const optedOutAt = await recordOptOut(userId, 'opted_out_hard');

  return {
    statusCode: 200,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ capture_status: 'opted_out_hard', opted_out_at: optedOutAt }),
  };
};
