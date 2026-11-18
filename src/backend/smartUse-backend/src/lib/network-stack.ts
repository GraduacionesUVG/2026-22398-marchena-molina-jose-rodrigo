import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import { Construct } from 'constructs';

export class NetworkStack extends cdk.Stack {
  public readonly vpc: ec2.Vpc;
  public readonly auroraSecurityGroup: ec2.SecurityGroup;

  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    this.vpc = new ec2.Vpc(this, 'SmartUseVpc', {
      maxAzs: 2,
      natGateways: 0,
      subnetConfiguration: [
        {
          name: 'public',
          subnetType: ec2.SubnetType.PUBLIC,
          cidrMask: 24,
        },
      ],
    });

    // Inbound rules are deliberately NOT declared here: the Grafana IP
    // allowlist is managed by hand in the AWS console (EC2 -> Security
    // Groups -> this group -> Inbound rules, PostgreSQL 5432 from <ip>/32).
    // Because this resource never declares SecurityGroupIngress,
    // CloudFormation never touches those rules on deploy. Don't add
    // addIngressRule() calls, or the next deploy will overwrite them.
    this.auroraSecurityGroup = new ec2.SecurityGroup(this, 'AuroraSecurityGroup', {
      vpc: this.vpc,
      description: 'Allows Postgres access to Aurora for the local Grafana instance',
      allowAllOutbound: false,
    });
  }
}
