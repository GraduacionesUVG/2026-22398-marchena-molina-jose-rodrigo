#!/usr/bin/env bash
# One-off (safe to re-run): via the RDS Data API, as the cluster's master
# secret,
#   - creates/updates the read-only `grafana_ro` Postgres role,
#   - sets the study goals in study_config (REQ-ADM-02),
#   - writes grafana/.env for grafana/run-grafana.ps1.
#
# Usage:  bash grafana/setup-db-access.sh
# Override goals: PARTICIPANT_GOAL=15 VOLUME_GOAL_DAYS=150 bash grafana/setup-db-access.sh
set -euo pipefail
cd "$(dirname "$0")"

export AWS_PROFILE="${AWS_PROFILE:-smartuse}"
PARTICIPANT_GOAL="${PARTICIPANT_GOAL:-15}"
VOLUME_GOAL_DAYS="${VOLUME_GOAL_DAYS:-150}"
DB_NAME=smartuse
ROLE=grafana_ro

read -r CLUSTER_ARN AURORA_HOST < <(aws rds describe-db-clusters \
  --query "DBClusters[?starts_with(DBClusterIdentifier, 'smartusedatastack-auroracluster')] | [0].[DBClusterArn, Endpoint]" \
  --output text | tr -d '\r')
SECRET_ARN=$(aws secretsmanager list-secrets \
  --filters Key=name,Values=AuroraClusterSecret \
  --query "SecretList[0].ARN" --output text | tr -d '\r')

if [[ -z "$CLUSTER_ARN" || "$CLUSTER_ARN" == "None" || -z "$SECRET_ARN" || "$SECRET_ARN" == "None" ]]; then
  echo "Could not find the SmartUse Aurora cluster/secret with profile $AWS_PROFILE" >&2
  exit 1
fi

# Reuse the existing password on re-runs so a running Grafana keeps working.
if [[ -f .env ]] && grep -q '^GRAFANA_DB_PASSWORD=' .env; then
  PASSWORD=$(grep '^GRAFANA_DB_PASSWORD=' .env | cut -d= -f2-)
else
  PASSWORD=$(openssl rand -hex 24)
fi

run_sql() {
  aws rds-data execute-statement \
    --resource-arn "$CLUSTER_ARN" --secret-arn "$SECRET_ARN" --database "$DB_NAME" \
    --sql "$1" > /dev/null
}

CREATE_ROLE=$(cat <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '__ROLE__') THEN
    CREATE ROLE __ROLE__ LOGIN PASSWORD '__PASSWORD__';
  ELSE
    ALTER ROLE __ROLE__ LOGIN PASSWORD '__PASSWORD__';
  END IF;
END
$$;
SQL
)
CREATE_ROLE=${CREATE_ROLE//__ROLE__/$ROLE}
CREATE_ROLE=${CREATE_ROLE//__PASSWORD__/$PASSWORD}

echo "Creating/updating role $ROLE..."
run_sql "$CREATE_ROLE"
# Belt and braces: even if a grant is ever widened by mistake, this role
# can't write.
run_sql "ALTER ROLE $ROLE SET default_transaction_read_only = on"
run_sql "ALTER ROLE $ROLE SET statement_timeout = '30s'"
run_sql "GRANT CONNECT ON DATABASE $DB_NAME TO $ROLE"
run_sql "GRANT USAGE ON SCHEMA public TO $ROLE"
run_sql "GRANT SELECT ON canonical_events, interventions, resource_metrics, inference_metrics, study_config, v_participant_hours, v_volume_vs_goal TO $ROLE"
# users: every column except auth_token. v_active_participants is deliberately
# not granted -- it's SELECT * over users, so it would leak auth_token.
run_sql "GRANT SELECT (user_id, age_at_signup, gender, university_major, test_score, signup_ts, experiment_group, experiment_config, capture_status, opted_out_at, capture_end_ts) ON users TO $ROLE"

echo "Setting goals: participant_goal=$PARTICIPANT_GOAL, volume_goal_hours=$((VOLUME_GOAL_DAYS * 24)) ($VOLUME_GOAL_DAYS days)..."
run_sql "INSERT INTO study_config (key, value) VALUES ('participant_goal', $PARTICIPANT_GOAL), ('volume_goal_hours', $((VOLUME_GOAL_DAYS * 24))) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value"

umask 077
cat > .env <<EOF
AURORA_HOST=$AURORA_HOST
GRAFANA_DB_USER=$ROLE
GRAFANA_DB_PASSWORD=$PASSWORD
EOF

echo "Done. Wrote grafana/.env (gitignored). Next: powershell -ExecutionPolicy Bypass -File grafana/run-grafana.ps1"
