"""Export the study's participant data from Aurora to three CSVs via the RDS Data API.

Writes, into <out_dir>:
  users.csv          every registered user, except auth_token (a live credential)
  final_answers.csv  one row per answered question of the final forms
  final_metrics.csv  one row per performance record of the final send

Timestamps are in UTC. Everything is exported as stored, including the
check_deployment.py users (university_major = "QA-BACKEND-CHECK"): join on
user_id to filter them out of the other two files. A participant who sent no
answers still gets one row in final_answers.csv, with empty question columns.

Usage:  python scripts/export_study_data.py <out_dir>
Needs:  pip install boto3, and AWS credentials for the deployed account
        (profile from AWS_PROFILE, default `smartuse`; region from AWS_REGION,
        default us-east-1).
"""
import csv, os, sys, boto3

if len(sys.argv) != 2:
    sys.exit("usage: python scripts/export_study_data.py <out_dir>")
out_dir = sys.argv[1]

s = boto3.Session(profile_name=os.environ.get("AWS_PROFILE", "smartuse"),
                  region_name=os.environ.get("AWS_REGION", "us-east-1"))
rds, sm, rd = s.client("rds"), s.client("secretsmanager"), s.client("rds-data")
cluster = next(c for c in rds.describe_db_clusters()["DBClusters"]
               if c["DBClusterIdentifier"].startswith("smartusedatastack-auroracluster"))
secret = sm.list_secrets(Filters=[{"Key": "name", "Values": ["AuroraClusterSecret"]}])["SecretList"][0]["ARN"]


def utc(col):
    return f"to_char({col} AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.MS')"


# file -> (csv columns, SELECT list matching them, FROM clause, total ORDER BY).
EXPORTS = {
    "users.csv": (
        ["user_id", "age_at_signup", "gender", "university_major", "test_score", "signup_ts",
         "experiment_group", "experiment_config", "capture_status", "opted_out_at",
         "capture_end_ts", "finished_at"],
        f"user_id::text, age_at_signup, gender, university_major, test_score::text, {utc('signup_ts')}, "
        f"experiment_group, experiment_config::text, capture_status, {utc('opted_out_at')}, "
        f"{utc('capture_end_ts')}, {utc('finished_at')}",
        "users",
        "users.signup_ts, users.user_id",
    ),
    "final_answers.csv": (
        ["user_id", "end_reason", "received_at", "form_id", "form_version", "question_id",
         "value_int", "value_text"],
        f"s.user_id::text, s.end_reason, {utc('s.received_at')}, a.form_id, a.form_version, "
        f"a.question_id, a.value_int, a.value_text",
        "final_sends s LEFT JOIN final_answers a ON a.user_id = s.user_id",
        "s.user_id, a.form_id, a.question_id",
    ),
    "final_metrics.csv": (
        ["user_id", "decision_point", "duration_ms", "battery_delta_pct", "current_mean", "peak_rss_kb"],
        "user_id::text, decision_point, duration_ms, battery_delta_pct, current_mean, peak_rss_kb",
        "final_metrics",
        # No primary key, and it has to stay that way (no order or time on
        # purpose), so sort on every column: rows that still tie are identical.
        "user_id, decision_point, duration_ms, battery_delta_pct, current_mean, peak_rss_kb",
    ),
}

# Small enough to stay under the Data API's 1 MB response limit: an open answer
# can be 1000 characters. OFFSET rather than keyset because final_metrics has no
# unique key to page on, and these tables are small enough for it not to matter.
PAGE = 500


def cell(v):
    if v.get("isNull"):
        return ""
    return next(iter(v.values()))


def fetch(select, source, order):
    rows = []
    while True:
        sql = f"SELECT {select} FROM {source} ORDER BY {order} LIMIT {PAGE} OFFSET {len(rows)}"
        r = rd.execute_statement(resourceArn=cluster["DBClusterArn"], secretArn=secret,
                                 database="smartuse", sql=sql)
        batch = [[cell(v) for v in rec] for rec in r["records"]]
        rows += batch
        print(f"fetched {len(rows)}", file=sys.stderr)
        if len(batch) < PAGE:
            return rows


os.makedirs(out_dir, exist_ok=True)
for name, (cols, select, source, order) in EXPORTS.items():
    rows = fetch(select, source, order)
    path = os.path.join(out_dir, name)
    # utf-8-sig so Excel reads accents and emoji in open answers correctly.
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(cols)
        w.writerows(rows)
    print(f"wrote {len(rows)} rows to {path}")
