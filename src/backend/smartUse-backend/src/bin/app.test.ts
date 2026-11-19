import * as cdk from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { NetworkStack } from '../lib/network-stack';
import { DataStack } from '../lib/data-stack';
import { ApiStack } from '../lib/api-stack';

test('the full app wires Network -> Data -> Api and synthesizes all three stacks', () => {
  const app = new cdk.App();
  const network = new NetworkStack(app, 'SmartUseNetworkStack');
  const data = new DataStack(app, 'SmartUseDataStack', {
    vpc: network.vpc,
    auroraSecurityGroup: network.auroraSecurityGroup,
  });
  const api = new ApiStack(app, 'SmartUseApiStack', {
    cluster: data.cluster,
    rawDumpsBucket: data.rawDumpsBucket,
  });

  const assembly = app.synth();
  expect(assembly.stacks.map((s) => s.stackName).sort()).toEqual([
    'SmartUseApiStack',
    'SmartUseDataStack',
    'SmartUseNetworkStack',
  ]);

  const apiTemplate = Template.fromStack(api);
  apiTemplate.resourceCountIs('AWS::ApiGateway::Method', 12);
});
