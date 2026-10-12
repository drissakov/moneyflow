# Encrypted PostgreSQL backups

For the free test deployment, `.github/workflows/free-backup.yml` uses
`Dockerfile.free` and `free-backup.sh`. It dumps a PostgreSQL 18 database over
verified TLS, checks a real isolated PostgreSQL restore with the same table
fingerprints, then encrypts the archive to an **offline age identity's public
recipient**. Only ciphertext, its SHA-256 and a receipt without financial data
are uploaded as assets of a **private draft GitHub release** with tag
`moneyflow-encrypted-backups`. This release stays a draft and is visible to
authorized repository collaborators. The workflow never publishes it. Plaintext
exists only in a container tmpfs.
Daily jobs cannot decrypt that archive: the private recovery identity must stay
outside GitHub. Run offline recovery drills to test decryption as well.

Enable with `MONEYFLOW_FREE_BACKUPS_ENABLED=true` only after configuring secret
`MONEYFLOW_BACKUP_DATABASE_URL` (direct PostgreSQL 18 provider URI) and variable
`MONEYFLOW_BACKUP_AGE_RECIPIENT` (standard `age1…` recipient). The maximum
encrypted archive size is 20 MiB. After a completed upload, retention preserves
the newest seven complete backups and removes backup groups older than seven
days. The checksum uploads after ciphertext; the JSON receipt uploads last and
marks completion. An incomplete current backup cannot trigger retention.
Unrelated releases and assets are never removed. A matching published release
causes the job to fail closed. GitHub release assets do not consume the Actions
artifact storage quota; [GitHub documents no total release-asset storage or
bandwidth limit](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases).
See [the free hosting guide](../../docs/FREE_HOSTING.md) for setup and recovery.

The encrypted archive contains `moneyflow.dump`, `manifest.jsonl` and
`format-version`. Download a complete draft release asset group, verify its
SHA-256, decrypt the `moneyflow-backup-RUN_ID-ATTEMPT.tar.age` file with
`age --decrypt -i your-offline-key.txt`, extract into a
private temporary directory, and restore with PostgreSQL 18 `pg_restore` into an
independent database. The free integration fixture exercises encryption,
decryption, restore, fingerprint comparison, wrong-key denial and cleanup:

```sh
docker compose -f infra/backup/tests/compose.free.yaml up --build --abort-on-container-exit --exit-code-from test
docker compose -f infra/backup/tests/compose.free.yaml down --volumes
python -B -m unittest discover -s infra/backup/tests -p 'test_*.py' -v
```

The remaining instructions describe the optional VPS/S3 restic deployment.

This image contains PostgreSQL 17 clients and restic 0.19.1. It accepts `init`,
`backup`, `verify [full-snapshot-id]`, `schedule` and `health`. It does not need the
Docker socket and never restores into the production database.

## Runtime contract

Mount `/work` as **tmpfs**, `/tmp` as tmpfs, `/state` as a persistent writable
metadata volume, and `/metrics` as a writable textfile metrics volume. The image
works with a read-only root filesystem, all Linux capabilities dropped and
`no-new-privileges`. `/state` holds only timestamps and the process lock. It does
not hold dumps, passwords or financial data. `/work` must have capacity for a
compressed database dump and restic's temporary work; use 768 MiB initially and
monitor memory as data grows. Plaintext files are deleted on success or failure.

| Variable | Meaning / default |
| --- | --- |
| `PGHOST`, `PGDATABASE`, `PGUSER` | Production database connection |
| `PGPORT` | `5432` |
| `PGPASSWORD_FILE` | Mounted production DB password secret |
| `RESTIC_REPOSITORY` | `s3:https://endpoint/bucket/moneyflow` |
| `RESTIC_PASSWORD_FILE` | Mounted, non-empty encryption password secret |
| `AWS_ACCESS_KEY_ID_FILE` | Mounted S3 access key secret |
| `AWS_SECRET_ACCESS_KEY_FILE` | Mounted S3 secret key secret |
| `AWS_DEFAULT_REGION` | Provider region; use `auto` for Cloudflare R2 |
| `RESTORE_PGHOST`, `RESTORE_PGUSER` | Separate verifier PostgreSQL host and role |
| `RESTORE_PGPORT` | `5432` |
| `RESTORE_PGPASSWORD_FILE` | Independent verifier password secret |
| `BACKUP_UTC_TIME` | Daily `HH:MM`, default `03:00` UTC |
| `BACKUP_RUN_ON_START` | `true` |
| `BACKUP_START_MAX_AGE_SECONDS` | Skip startup backup if success younger than `21600` seconds |
| `BACKUP_MAX_AGE_SECONDS` | `health` staleness threshold, `90000` seconds |
| `BACKUP_HOST` | Stable snapshot group host, `moneyflow-production` |
| `KEEP_DAILY`, `KEEP_WEEKLY`, `KEEP_MONTHLY` | `7`, `4`, `6` |
| `BACKUP_MAX_REPACK_SIZE` | Maximum data rewritten in one prune, `256M` |

Keep the verifier on its own network, without the production password or access
to the production database. Its `PGDATA` is tmpfs. The backup container joins the
production private network, verifier network and outbound network. The verifier
needs permission to create/drop databases only on that disposable instance.

## Initialization and lifecycle

Initialize the repository explicitly once with `init`. The command authenticates
an existing repository and skips initialization. Only restic exit code `10`
(missing repository) permits `restic init`. Wrong passwords, denied credentials,
connection failures and unexpected exit codes stop the command. Scheduled jobs
never initialize repositories and never automatically run `restic unlock`.

`backup` exports one live PostgreSQL repeatable-read snapshot, creates a custom
archive and per-table row-count/content fingerprints against that same snapshot,
then encrypts both in restic. It checks all stored repository data with
`restic check --read-data`, downloads the exact completed snapshot, restores it
using `pg_restore --exit-on-error` into a uniquely named verifier database, and
compares all restored table fingerprints. Only after verification does it forget
old snapshots and prune. Every verification database is dropped after the job.

The scheduler runs once a day in UTC and retries failed startup/scheduled jobs
after five minutes. A successful backup within the past six hours suppresses an
extra backup at container restart. A shared `flock` covers dump, upload, check,
restore and prune. Restic also uses remote repository locks across hosts.
Manual jobs must use this entrypoint and the same `/state` volume. Do not prune
the same repository through another scheduler or remove locks during active jobs.

`verify` restores a chosen completed snapshot, or the latest matching host/tag.
It does not need production DB credentials. Use it regularly in addition to the
automatic daily restore test. Deploy upgrades run `backup` before migrations.
On first installation, migrate an empty database first, then take and verify the
first backup before starting the public API. These are daily logical backups,
with a recovery point objective of up to 24 hours; they do not provide PITR/WAL.

The production S3 bucket must be private, with narrowly scoped credentials.
Keep an offline copy of the restic password and object-storage access recovery
information, outside the VPS. A backup cannot be decrypted without its password.
Infrastructure configuration and credentials must be recoverable separately;
this job backs up application database contents and schema, not the whole VPS.

## Monitoring

Atomic `/metrics/moneyflow_backup.prom` exposes these gauges for node_exporter's
textfile collector. Timestamps are Unix seconds, zero until first success.

- `moneyflow_backup_last_success_timestamp_seconds`
- `moneyflow_backup_last_run_success`
- `moneyflow_backup_duration_seconds`
- `moneyflow_backup_check_last_success_timestamp_seconds`
- `moneyflow_backup_check_last_run_success`
- `moneyflow_backup_restore_last_success_timestamp_seconds`
- `moneyflow_backup_restore_last_run_success`

A success means dump, upload, full repository check, actual database restore,
fingerprint comparison and retention all finished. A failure preserves historical
success timestamps and sets the relevant last-run status to zero.

## Real integration test

From the repository root on a machine with Docker Compose:

```sh
docker compose -f infra/backup/tests/compose.yaml up --build --abort-on-container-exit --exit-code-from test
docker compose -f infra/backup/tests/compose.yaml down --volumes
```

This fixture uses the real migrations, PostgreSQL 17 and restic, with a temporary
local encrypted repository. It checks successful restore/fingerprints, absence
of a plaintext marker in repository files, wrong-password denial without config
overwrite, source-dump failure, process locking, altered-manifest rejection and
temporary database cleanup. It never reads production secrets. This verifies
local encryption and restore behavior; credentials, TLS and permissions for the
chosen offsite provider must also be exercised on the actual deployment.

References: [restic S3 and passwords](https://restic.readthedocs.io/en/stable/030_preparing_a_new_repo.html),
[restic scripting and exit codes](https://restic.readthedocs.io/en/stable/075_scripting.html),
[restic repository checks](https://restic.readthedocs.io/en/stable/045_working_with_repos.html),
[PostgreSQL consistent dump snapshots](https://www.postgresql.org/docs/17/app-pgdump.html).
