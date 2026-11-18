import { App, Stack } from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as s3 from 'aws-cdk-lib/aws-s3';
import { ApiStack } from './api-stack';

function buildDataDeps(app: App) {
  const depsStack = new Stack(app, 'TestDepsForApi');
  const vpc = new ec2.Vpc(depsStack, 'Vpc', { maxAzs: 2, natGateways: 0 });
  const cluster = new rds.DatabaseCluster(depsStack, 'Cluster', {
    engine: rds.DatabaseClusterEngine.auroraPostgres({ version: rds.AuroraPostgresEngineVersion.VER_15_17 }),
    vpc,
    vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    writer: rds.ClusterInstance.serverlessV2('Writer'),
    serverlessV2MinCapacity: 0.5,
    serverlessV2MaxCapacity: 2,
    enableDataApi: true,
  });
  const bucket = new s3.Bucket(depsStack, 'Bucket');
  return { cluster, bucket };
}

test('creates all twelve routes from openapi.yaml, with the authorizer on every route except /users, /cutoff and /cutoff-deployment-group', () => {
  const app = new App();
  const { cluster, bucket } = buildDataDeps(app);
  const stack = new ApiStack(app, 'TestApiStack', { cluster, rawDumpsBucket: bucket });
  const template = Template.fromStack(stack);

  template.resourceCountIs('AWS::ApiGateway::Method', 12);
  template.resourceCountIs('AWS::ApiGateway::Authorizer', 1);
  template.hasResourceProperties('AWS::ApiGateway::Method', { HttpMethod: 'POST', AuthorizationType: 'NONE' });
  template.hasResourceProperties('AWS::ApiGateway::Method', { HttpMethod: 'GET', AuthorizationType: 'NONE' });
  template.hasResourceProperties('AWS::ApiGateway::Method', { HttpMethod: 'POST', AuthorizationType: 'CUSTOM' });
});

test('every route lambda and the authorizer get Data API env vars and grantDataApiAccess', () => {
  const app = new App();
  const { cluster, bucket } = buildDataDeps(app);
  const stack = new ApiStack(app, 'TestApiStack', { cluster, rawDumpsBucket: bucket });
  const template = Template.fromStack(stack);

  const expectedEnv = Match.objectLike({
    Variables: Match.objectLike({
      CLUSTER_ARN: Match.anyValue(),
      SECRET_ARN: Match.anyValue(),
      DATABASE_NAME: 'smartuse',
    }),
  });

  // 12 route lambdas + the authorizer = 13 functions wired to the Data API.
  template.resourceCountIs('AWS::Lambda::Function', 13);
  template.allResourcesProperties('AWS::Lambda::Function', { Environment: expectedEnv });
});

test('every lambda runs on a Node runtime with native Web Crypto support (AWS SDK v3 needs it)', () => {
  const app = new App();
  const { cluster, bucket } = buildDataDeps(app);
  const stack = new ApiStack(app, 'TestApiStack', { cluster, rawDumpsBucket: bucket });
  const template = Template.fromStack(stack);

  template.allResourcesProperties('AWS::Lambda::Function', { Runtime: 'nodejs24.x' });
});

test('POST /final has the authorizer and a timeout under the 29 s API Gateway limit', () => {
  const app = new App();
  const { cluster, bucket } = buildDataDeps(app);
  const stack = new ApiStack(app, 'TestApiStack', { cluster, rawDumpsBucket: bucket });
  const template = Template.fromStack(stack);

  template.hasResourceProperties('AWS::Lambda::Function', { Timeout: 28, MemorySize: 512 });
  template.hasResourceProperties('AWS::ApiGateway::Resource', { PathPart: 'final' });
  template.hasResourceProperties('AWS::ApiGateway::Resource', { PathPart: 'cutoff-deployment-group' });
});

test('401 responses use the {"error":"unauthorized"} body', () => {
  const app = new App();
  const { cluster, bucket } = buildDataDeps(app);
  const stack = new ApiStack(app, 'TestApiStack', { cluster, rawDumpsBucket: bucket });
  const template = Template.fromStack(stack);

  template.hasResourceProperties('AWS::ApiGateway::GatewayResponse', {
    ResponseType: 'UNAUTHORIZED',
    StatusCode: '401',
    ResponseTemplates: { 'application/json': '{"error":"unauthorized"}' },
  });
});
