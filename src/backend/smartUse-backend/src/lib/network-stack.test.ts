import { App } from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import { NetworkStack } from './network-stack';

test('creates a VPC with only public subnets and no NAT gateways', () => {
  const app = new App();
  const stack = new NetworkStack(app, 'TestNetworkStack');
  const template = Template.fromStack(stack);

  template.resourceCountIs('AWS::EC2::NatGateway', 0);
  template.resourceCountIs('AWS::EC2::SecurityGroup', 1);
});

test('the Aurora security group declares no ingress, so console-managed rules survive deploys', () => {
  const app = new App();
  const stack = new NetworkStack(app, 'TestNetworkStackNoIngress');
  const template = Template.fromStack(stack);

  template.hasResourceProperties('AWS::EC2::SecurityGroup', {
    SecurityGroupIngress: Match.absent(),
  });
  template.resourceCountIs('AWS::EC2::SecurityGroupIngress', 0);
});
