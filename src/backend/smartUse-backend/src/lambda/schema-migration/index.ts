import type { CdkCustomResourceHandler } from 'aws-lambda';
import { RDSDataClient, ExecuteStatementCommand } from '@aws-sdk/client-rds-data';
import { splitSqlStatements } from './sql-statements';
// esbuild's `text` loader (configured in DataStack's NodejsFunction bundling
// options for the .sql extension) inlines this file's contents as a string.
import schemaSql from '../../db/schema.sql';

const rdsData = new RDSDataClient({});

export const handler: CdkCustomResourceHandler = async (event) => {
  if (event.RequestType === 'Delete') {
    return { PhysicalResourceId: event.PhysicalResourceId };
  }

  const resourceArn = requireEnv('CLUSTER_ARN');
  const secretArn = requireEnv('SECRET_ARN');
  const database = requireEnv('DATABASE_NAME');

  for (const statement of splitSqlStatements(schemaSql)) {
    await rdsData.send(new ExecuteStatementCommand({ resourceArn, secretArn, database, sql: statement }));
  }

  return { PhysicalResourceId: 'schema-migration' };
};

function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} environment variable is required`);
  }
  return value;
}
