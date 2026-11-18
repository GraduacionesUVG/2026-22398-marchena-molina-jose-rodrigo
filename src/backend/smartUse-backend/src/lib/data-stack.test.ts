import { App, Stack } from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import { DataStack } from './data-stack';

function buildNetwork(app: App) {
  const networkStack = new Stack(app, 'TestNetworkForData');
  const vpc = new ec2.Vpc(networkStack, 'Vpc', { maxAzs: 2, natGateways: 0 });
  const sg = new ec2.SecurityGroup(networkStack, 'Sg', { vpc });
  return { vpc, sg };
}

test('provisions a publicly accessible Aurora Postgres cluster with Data API enabled, plus an encrypted S3 bucket', () => {
  const app = new App();
  const { vpc, sg } = buildNetwork(app);
  const stack = new DataStack(app, 'TestDataStack', { vpc, auroraSecurityGroup: sg });
  const template = Template.fromStack(stack);

  template.hasResourceProperties('AWS::RDS::DBCluster', {
    Engine: 'aurora-postgresql',
    EnableHttpEndpoint: true,
  });
  template.resourceCountIs('AWS::S3::Bucket', 1);
  template.hasResourceProperties('AWS::S3::Bucket', {
    BucketEncryption: Match.objectLike({
      ServerSideEncryptionConfiguration: Match.arrayWith([
        Match.objectLike({ ServerSideEncryptionByDefault: Match.objectLike({ SSEAlgorithm: 'AES256' }) }),
      ]),
    }),
  });
});

test('wires a custom resource that runs the schema-migration Lambda with cluster connection details', () => {
  const app = new App();
  const { vpc, sg } = buildNetwork(app);
  const stack = new DataStack(app, 'TestDataStack2', { vpc, auroraSecurityGroup: sg });
  const template = Template.fromStack(stack);

  template.resourceCountIs('AWS::CloudFormation::CustomResource', 1);
  template.hasResourceProperties('AWS::Lambda::Function', {
    Environment: Match.objectLike({
      Variables: Match.objectLike({ DATABASE_NAME: 'smartuse' }),
    }),
  });
});

test('the schema-migration Lambda runs on a Node runtime with native Web Crypto support (AWS SDK v3 needs it)', () => {
  const app = new App();
  const { vpc, sg } = buildNetwork(app);
  const stack = new DataStack(app, 'TestDataStack3', { vpc, auroraSecurityGroup: sg });
  const template = Template.fromStack(stack);

  template.hasResourceProperties('AWS::Lambda::Function', { Runtime: 'nodejs24.x' });
});

test('the schema-migration custom resource waits for the writer instance, not just the cluster', () => {
  const app = new App();
  const { vpc, sg } = buildNetwork(app);
  const stack = new DataStack(app, 'TestDataStack4', { vpc, auroraSecurityGroup: sg });
  const template = Template.fromStack(stack);

  template.hasResource('AWS::CloudFormation::CustomResource', {
    DependsOn: Match.arrayWith([Match.stringLikeRegexp('.*Writer.*')]),
  });
});
