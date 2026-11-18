import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as lambdaNode from 'aws-cdk-lib/aws-lambda-nodejs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as cr from 'aws-cdk-lib/custom-resources';
import * as crypto from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import { Construct } from 'constructs';

export interface DataStackProps extends cdk.StackProps {
  vpc: ec2.Vpc;
  auroraSecurityGroup: ec2.SecurityGroup;
}

export const DATABASE_NAME = 'smartuse';

export class DataStack extends cdk.Stack {
  public readonly cluster: rds.DatabaseCluster;
  public readonly rawDumpsBucket: s3.Bucket;

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);

    this.cluster = new rds.DatabaseCluster(this, 'AuroraCluster', {
      engine: rds.DatabaseClusterEngine.auroraPostgres({
        version: rds.AuroraPostgresEngineVersion.VER_15_17,
      }),
      vpc: props.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      securityGroups: [props.auroraSecurityGroup],
      defaultDatabaseName: DATABASE_NAME,
      serverlessV2MinCapacity: 0.5,
      serverlessV2MaxCapacity: 2,
      writer: rds.ClusterInstance.serverlessV2('Writer', { publiclyAccessible: true }),
      enableDataApi: true,
      storageEncrypted: true,
    });

    this.rawDumpsBucket = new s3.Bucket(this, 'RawDumpsBucket', {
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
    });

    const schemaSql = fs.readFileSync(path.join(__dirname, '..', 'db', 'schema.sql'), 'utf-8');
    const schemaHash = crypto.createHash('sha256').update(schemaSql).digest('hex');

    const migrationFn = new lambdaNode.NodejsFunction(this, 'SchemaMigrationFn', {
      entry: path.join(__dirname, '..', 'lambda', 'schema-migration', 'index.ts'),
      runtime: lambda.Runtime.NODEJS_24_X,
      bundling: { loader: { '.sql': 'text' } },
      environment: {
        CLUSTER_ARN: this.cluster.clusterArn,
        SECRET_ARN: this.cluster.secret!.secretArn,
        DATABASE_NAME,
      },
      timeout: cdk.Duration.minutes(2),
    });
    this.cluster.grantDataApiAccess(migrationFn);

    const provider = new cr.Provider(this, 'SchemaMigrationProvider', {
      onEventHandler: migrationFn,
    });

    const schemaMigrationResource = new cdk.CustomResource(this, 'SchemaMigrationResource', {
      serviceToken: provider.serviceToken,
      properties: { SchemaHash: schemaHash },
    });
    // The Lambda's env vars only reference the DBCluster resource, so CloudFormation
    // has no implicit ordering against the writer DBInstance — without this, the
    // Data API call can run before the writer is actually available.
    schemaMigrationResource.node.addDependency(this.cluster);
  }
}
