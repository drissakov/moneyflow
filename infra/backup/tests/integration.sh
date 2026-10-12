#!/usr/bin/env bash
set -Eeuo pipefail
source /opt/moneyflow-backup/common.sh
mkdir -p /work/secrets
printf '%s\n' source-test-password >/work/secrets/postgres_password
printf '%s\n' restore-test-password >/work/secrets/restore_db_password
printf '%s\n' encrypted-fixture-password-23abcdef >/work/secrets/restic_password
printf '%s\n' wrong-password >/work/secrets/wrong_password
load_source
for migration in /migrations/*.sql; do psql -X -q -v ON_ERROR_STOP=1 -f "$migration"; done
psql -X -q -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE schema_migrations(version text PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now());
INSERT INTO schema_migrations VALUES ('fixture', 'test', '2026-10-12T03:00:00Z');
INSERT INTO users(id,email,password_hash,created_at) VALUES
('11111111-1111-4111-8111-111111111111','PLAINTEXT_MUST_NOT_APPEAR_IN_BACKUP@example.com','fixture-password-hash','2026-10-12T01:00:00Z');
INSERT INTO accounts(id,user_id,name,currency,opening_balance_minor,created_at) VALUES
('22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111','Fixture Account','KZT',1234500,'2026-10-12T01:00:00Z');
INSERT INTO transactions(id,user_id,account_id,kind,amount_minor,currency,note,occurred_at,created_at,category_id) VALUES
('33333333-3333-4333-8333-333333333333','11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222','expense',34900,'KZT','Кофе и завтрак','2026-10-12T02:00:00Z','2026-10-12T02:00:00Z','30000000-0000-4000-8000-000000000003');
SQL

runner=/opt/moneyflow-backup/entrypoint.sh
"$runner" init
"$runner" backup
"$runner" health
grep -q '^moneyflow_backup_last_run_success 1$' /metrics/moneyflow_backup.prom
grep -q '^moneyflow_backup_restore_last_run_success 1$' /metrics/moneyflow_backup.prom
grep -q '^moneyflow_backup_check_last_run_success 1$' /metrics/moneyflow_backup.prom
[[ ! -e /work/backup/moneyflow.dump && ! -e /work/restore ]] || die 'Plaintext archive survived a completed job.'
if grep -R -a -q 'PLAINTEXT_MUST_NOT_APPEAR_IN_BACKUP' /repository; then die 'Plaintext financial marker was found in the encrypted repository.'; fi
printf 'PASS: real encrypted backup and full isolated PostgreSQL restore\n'

config_before=$(sha256sum /repository/config)
if RESTIC_PASSWORD_FILE=/work/secrets/wrong_password "$runner" init; then die 'Wrong password was accepted by init.'; fi
[[ "$config_before" == "$(sha256sum /repository/config)" ]] || die 'Wrong-password init changed repository config.'
if RESTIC_PASSWORD_FILE=/work/secrets/wrong_password restic_cmd snapshots >/dev/null 2>&1; then die 'Wrong password could read repository.'; fi
printf 'PASS: wrong encryption password fails closed and cannot read repository\n'

snapshots_before=$(restic_cmd snapshots --json | jq length)
if PGHOST=unresolvable-fixture-db "$runner" backup; then die 'Failed pg_dump was accepted.'; fi
[[ "$snapshots_before" == "$(restic_cmd snapshots --json | jq length)" ]] || die 'Failed source dump created a snapshot.'
grep -q '^moneyflow_backup_last_run_success 0$' /metrics/moneyflow_backup.prom
grep -Eq '^moneyflow_backup_last_success_timestamp_seconds [1-9][0-9]*$' /metrics/moneyflow_backup.prom
printf 'PASS: failed dump produces no snapshot and preserves last success metric\n'

# The process lock protects the entire verification/prune window, not just dump.
flock /state/job.lock -c 'sleep 3' &
lock_pid=$!
sleep 1
if "$runner" backup; then die 'Overlapping backup was accepted.'; fi
wait "$lock_pid"
printf 'PASS: overlapping jobs are refused\n'

# Deliberately alter the expected fingerprint, then make a real encrypted
# snapshot. Restore must reject it and remove its temporary database.
snapshot=$(restic_cmd snapshots --json | jq -er '.[0].id')
mkdir -p /work/tampered
restic_cmd restore "$snapshot" --target /work/tampered >/dev/null
mkdir -p /work/backup
cp /work/tampered/work/backup/* /work/backup/
printf '["users", "999999", "bad-fingerprint"]\n' >/work/backup/manifest.jsonl
restic_cmd backup --host "$BACKUP_HOST" --tag "$BACKUP_TAG" --json /work/backup >/work/tampered-result.jsonl
tampered=$(jq -er 'select(.message_type == "summary") | .snapshot_id' /work/tampered-result.jsonl)
if "$runner" verify "$tampered"; then die 'Tampered verification manifest was accepted.'; fi
grep -q '^moneyflow_backup_restore_last_run_success 0$' /metrics/moneyflow_backup.prom
restored_count=$(PGHOST=$RESTORE_PGHOST PGUSER=$RESTORE_PGUSER PGDATABASE=postgres PGPASSWORD=restore-test-password \
    psql -X -qAt -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM pg_database WHERE datname LIKE 'moneyflow_verify_%';")
[[ "$restored_count" == 0 ]] || die 'A verification database survived cleanup.'
printf 'PASS: mismatched restored manifest rejected and temporary database removed\n'
printf 'All real PostgreSQL/restic integration checks passed.\n'
