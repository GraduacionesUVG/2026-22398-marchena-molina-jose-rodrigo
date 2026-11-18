import * as cdk from 'aws-cdk-lib';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as lambdaNode from 'aws-cdk-lib/aws-lambda-nodejs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as path from 'path';
import { Construct } from 'constructs';
import { DATABASE_NAME } from './data-stack';

export interface ApiStackProps extends cdk.StackProps {
  cluster: rds.DatabaseCluster;
  rawDumpsBucket: s3.Bucket;
}

export class ApiStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: ApiStackProps) {
    super(scope, id, props);

    const api = new apigateway.RestApi(this, 'SmartUseApi', {
      restApiName: 'smartuse-backend',
    });

    const dataApiEnv = {
      CLUSTER_ARN: props.cluster.clusterArn,
      SECRET_ARN: props.cluster.secret!.secretArn,
      DATABASE_NAME,
    };

    const authorizerFn = new lambdaNode.NodejsFunction(this, 'AuthorizerFn', {
      entry: path.join(__dirname, '..', 'lambda', 'authorizer', 'index.ts'),
      runtime: lambda.Runtime.NODEJS_24_X,
      environment: dataApiEnv,
    });
    props.cluster.grantDataApiAccess(authorizerFn);
    const authorizer = new apigateway.TokenAuthorizer(this, 'BearerAuthorizer', {
      handler: authorizerFn,
      identitySource: apigateway.IdentitySource.header('Authorization'),
    });

    const buildRouteFn = (id: string, dirName: string, overrides: Partial<lambdaNode.NodejsFunctionProps> = {}) => {
      const fn = new lambdaNode.NodejsFunction(this, id, {
        entry: path.join(__dirname, '..', 'lambda', dirName, 'index.ts'),
        runtime: lambda.Runtime.NODEJS_24_X,
        environment: dataApiEnv,
        ...overrides,
      });
      props.cluster.grantDataApiAccess(fn);
      return fn;
    };

    const registerFn = buildRouteFn('RegisterFn', 'register');
    const cutoffFn = buildRouteFn('CutoffFn', 'cutoff');
    const cutoffDeploymentGroupFn = buildRouteFn('CutoffDeploymentGroupFn', 'cutoff-deployment-group');
    // A full POST /final (10 000+ metrics) has to commit inside API Gateway's
    // 29 s integration limit (REQ-DEP-16).
    const finalFn = buildRouteFn('FinalFn', 'final', { timeout: cdk.Duration.seconds(28), memorySize: 512 });
    const syncCanonicalFn = buildRouteFn('SyncCanonicalFn', 'sync-canonical');
    const syncCanonicalCursorFn = buildRouteFn('SyncCanonicalCursorFn', 'sync-canonical-cursor');
    const syncRawFn = buildRouteFn('SyncRawFn', 'sync-raw');
    syncRawFn.addEnvironment('RAW_BUCKET_NAME', props.rawDumpsBucket.bucketName);
    props.rawDumpsBucket.grantWrite(syncRawFn);
    const syncInferenceFn = buildRouteFn('SyncInferenceFn', 'sync-inference');
    const syncResourceMetricsFn = buildRouteFn('SyncResourceMetricsFn', 'sync-resource-metrics');
    const syncInterventionsFn = buildRouteFn('SyncInterventionsFn', 'sync-interventions');
    const optOutSoftFn = buildRouteFn('OptOutSoftFn', 'opt-out-soft');
    const optOutHardFn = buildRouteFn('OptOutHardFn', 'opt-out-hard');
    optOutHardFn.addEnvironment('RAW_BUCKET_NAME', props.rawDumpsBucket.bucketName);
    props.rawDumpsBucket.grantRead(optOutHardFn);
    props.rawDumpsBucket.grantDelete(optOutHardFn);

    const users = api.root.addResource('users');
    users.addMethod('POST', new apigateway.LambdaIntegration(registerFn));

    const cutoff = api.root.addResource('cutoff');
    cutoff.addMethod('GET', new apigateway.LambdaIntegration(cutoffFn));

    api.root.addResource('cutoff-deployment-group').addMethod('GET', new apigateway.LambdaIntegration(cutoffDeploymentGroupFn));
    api.root.addResource('final').addMethod('POST', new apigateway.LambdaIntegration(finalFn), { authorizer });

    // REQ-DEP-18: the deployment app expects {"error":"unauthorized"} rather
    // than API Gateway's default {"message":"Unauthorized"}. API-wide.
    api.addGatewayResponse('UnauthorizedResponse', {
      type: apigateway.ResponseType.UNAUTHORIZED,
      statusCode: '401',
      templates: { 'application/json': '{"error":"unauthorized"}' },
    });

    const sync = api.root.addResource('sync');
    const syncCanonical = sync.addResource('canonical');
    syncCanonical.addMethod('POST', new apigateway.LambdaIntegration(syncCanonicalFn), { authorizer });
    syncCanonical.addResource('cursor').addMethod('GET', new apigateway.LambdaIntegration(syncCanonicalCursorFn), { authorizer });
    sync.addResource('raw').addMethod('POST', new apigateway.LambdaIntegration(syncRawFn), { authorizer });
    sync.addResource('inference').addMethod('POST', new apigateway.LambdaIntegration(syncInferenceFn), { authorizer });
    sync.addResource('resource-metrics').addMethod('POST', new apigateway.LambdaIntegration(syncResourceMetricsFn), { authorizer });
    sync.addResource('interventions').addMethod('POST', new apigateway.LambdaIntegration(syncInterventionsFn), { authorizer });

    const optOut = api.root.addResource('opt-out');
    optOut.addResource('soft').addMethod('POST', new apigateway.LambdaIntegration(optOutSoftFn), { authorizer });
    optOut.addResource('hard').addMethod('POST', new apigateway.LambdaIntegration(optOutHardFn), { authorizer });
  }
}
