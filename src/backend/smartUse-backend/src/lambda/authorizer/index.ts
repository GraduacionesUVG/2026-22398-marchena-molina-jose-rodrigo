import type { APIGatewayTokenAuthorizerHandler, APIGatewayAuthorizerResult } from 'aws-lambda';
import { executeStatement } from '../common/db';

// API Gateway caches a TOKEN authorizer's response by the raw bearer token
// alone (not per resource/method), and one auth_token is meant to be reused
// across every authenticated route. Scoping Resource to the single
// methodArn that triggered this invocation would authorize only that one
// route: every other route called with the same (cached) token would be
// denied with "no identity-based policy allows the execute-api:Invoke
// action" until the cache entry expires. Wildcarding to the whole
// api-id/stage makes the cached Allow valid for any route.
function wildcardResource(methodArn: string): string {
  // arn:aws:execute-api:{region}:{account}:{restApiId}/{stage}/{verb}/{resource...}
  const parts = methodArn.split(':');
  const [restApiId, stage] = parts[5].split('/');
  parts[5] = `${restApiId}/${stage}/*/*`;
  return parts.join(':');
}

function allow(userId: string, methodArn: string): APIGatewayAuthorizerResult {
  return {
    principalId: userId,
    policyDocument: {
      Version: '2012-10-17',
      Statement: [
        {
          Action: 'execute-api:Invoke',
          Effect: 'Allow',
          Resource: wildcardResource(methodArn),
        },
      ],
    },
    context: { user_id: userId },
  };
}

export const handler: APIGatewayTokenAuthorizerHandler = async (event) => {
  const match = /^Bearer (.+)$/.exec(event.authorizationToken ?? '');
  const token = match?.[1];
  if (!token) {
    throw new Error('Unauthorized');
  }

  const result = await executeStatement('SELECT user_id FROM users WHERE auth_token = :auth_token', [
    { name: 'auth_token', value: { stringValue: token } },
  ]);

  const userId = result.records?.[0]?.[0]?.stringValue;
  if (!userId) {
    throw new Error('Unauthorized');
  }

  return allow(userId, event.methodArn);
};
