"""Export canonical_events (usage epochs) from Aurora to CSV via the RDS Data API.

Uses the cluster's master secret, so it works from any network -- no security
group allowlisting needed (unlike Grafana).

Usage:  python scripts/export_canonical.py <out.csv>
Needs:  pip install boto3, and AWS credentials for the deployed account
        (profile from AWS_PROFILE, default `smartuse`; region from AWS_REGION,
        default us-east-1).
"""
import csv, os, sys, boto3

if len(sys.argv) != 2:
    sys.exit("usage: python scripts/export_canonical.py <out.csv>")
out = sys.argv[1]

s = boto3.Session(profile_name=os.environ.get("AWS_PROFILE", "smartuse"),
                  region_name=os.environ.get("AWS_REGION", "us-east-1"))
rds, sm, rd = s.client("rds"), s.client("secretsmanager"), s.client("rds-data")
cluster = next(c for c in rds.describe_db_clusters()["DBClusters"]
               if c["DBClusterIdentifier"].startswith("smartusedatastack-auroracluster"))
secret = sm.list_secrets(Filters=[{"Key": "name", "Values": ["AuroraClusterSecret"]}])["SecretList"][0]["ARN"]


def cell(v):
    if v.get("isNull"):
        return ""
    return next(iter(v.values()))


cols = ["client_record_id", "user_id", "start_ts", "distinct_app_count", "granularity", "is_use"]
# Keyset pagination on (start_ts, client_record_id): stable and fast, unlike OFFSET.
page, last = 5000, None
rows = []
while True:
    where = "" if last is None else "WHERE (start_ts, client_record_id) > (:ts::timestamptz, :id::uuid)"
    params = [] if last is None else [{"name": "ts", "value": {"stringValue": last[0]}},
                                      {"name": "id", "value": {"stringValue": last[1]}}]
    sql = (f"SELECT client_record_id::text, user_id::text, "
           f"to_char(start_ts AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.MS'), "
           f"distinct_app_count, granularity, is_use FROM canonical_events {where} "
           f"ORDER BY start_ts, client_record_id LIMIT {page}")
    r = rd.execute_statement(resourceArn=cluster["DBClusterArn"], secretArn=secret,
                             database="smartuse", sql=sql, parameters=params)
    batch = [[cell(v) for v in rec] for rec in r["records"]]
    rows += batch
    print(f"fetched {len(rows)}", file=sys.stderr)
    if len(batch) < page:
        break
    last = (batch[-1][2] + "+00", batch[-1][0])

with open(out, "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(cols)
    w.writerows(rows)
print(f"wrote {len(rows)} rows to {out} (start_ts in UTC)")
