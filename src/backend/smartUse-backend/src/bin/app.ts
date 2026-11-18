#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { NetworkStack } from '../lib/network-stack';
import { DataStack } from '../lib/data-stack';
import { ApiStack } from '../lib/api-stack';

const app = new cdk.App();

const network = new NetworkStack(app, 'SmartUseNetworkStack');
const data = new DataStack(app, 'SmartUseDataStack', {
  vpc: network.vpc,
  auroraSecurityGroup: network.auroraSecurityGroup,
});
new ApiStack(app, 'SmartUseApiStack', {
  cluster: data.cluster,
  rawDumpsBucket: data.rawDumpsBucket,
});
