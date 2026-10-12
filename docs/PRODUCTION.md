# Optional VPS deployment

This runbook is an optional paid VPS path. No server, domain, storage account or public deployment has been created. For the free testing deployment requested for MoneyFlow, use [FREE_HOSTING.md](FREE_HOSTING.md).

## Requirements

Use Ubuntu 24.04 LTS, preferably 2 vCPU / 4 GiB RAM / 40 GiB disk, a public API domain, and an offsite S3-compatible bucket for encrypted backups. Deployment preflight requires Docker Engine 24+, Docker Compose plugin 2.24+, Python 3, Git, curl, flock, at least 2 GiB RAM and 6 GiB free disk. Use a dedicated deploy account and SSH key. Docker access grants administrative control of the VPS.

Configure the domain's A record to the VPS IPv4 address. Create an AAAA record only when IPv6 routes correctly. Allow inbound TCP 80/443 for Caddy and TCP 22 from your administrator IP; optionally allow UDP 443 for HTTP/3. Keep PostgreSQL, the API's internal ports and exporters private. Grafana, Prometheus and Alertmanager bind to loopback. A provider firewall is appropriate because [Docker-published ports can bypass UFW rules](https://docs.docker.com/engine/install/ubuntu/).

Install Docker Engine from its [official Ubuntu apt repository](https://docs.docker.com/engine/install/ubuntu/), including `docker-compose-plugin`; install `git python3 curl util-linux`. Do not run an unreviewed convenience installer. Authenticate to SSH with your local key; no private key or provider credentials belong in the repository.

## Checkout and secrets

Run on the VPS as the deploy account:

```bash
sudo install -d -m 0750 -o "$USER" -g "$(id -gn)" /opt/moneyflow
git clone --branch main https://github.com/drissakov/moneyflow.git /opt/moneyflow
cd /opt/moneyflow
bash scripts/production/bootstrap.sh
nano .env.production
bash scripts/production/bootstrap.sh
```

The first bootstrap creates `.env.production` from the example and exits. Fill in a real `DOMAIN`, `ACME_EMAIL`, `RESTIC_REPOSITORY`, region, database identifiers and the backup schedule before the second run. It generates missing local passwords and a matching private PostgreSQL connection string. Existing secret files are always preserved. It rejects legacy inline database credentials; migrate those manually without changing an existing database password.

Create the backup bucket at your chosen provider. Supply an access key limited to this bucket/prefix, with list/get/put/delete permissions needed by restic. Enter credentials interactively; these commands do not print them:

```bash
read -r -s -p 'S3 access key: ' task_access_key
printf '\n'
printf '%s\n' "$task_access_key" > secrets/backup_s3_access_key
unset task_access_key
read -r -s -p 'S3 secret key: ' task_secret_key
printf '\n'
printf '%s\n' "$task_secret_key" > secrets/backup_s3_secret_key
unset task_secret_key
chmod 0444 secrets/backup_s3_access_key secrets/backup_s3_secret_key
```

The deploy account owns `.env.production` with mode `0600`, `secrets/` with mode `0700`, and secret files with mode `0444`. The directory prevents other host users from opening its files. Read permissions on each file allow the non-root API user to read the individual [Compose secret bind mounts](https://docs.docker.com/compose/how-tos/use-secrets/) granted to its service. Local Compose does not encrypt these host files; offsite restic snapshots are encrypted. Do not relax the directory permissions or commit/copy secret files into the build context. Preserve `secrets/restic_password` in an independent password manager or encrypted offline location: losing both the VPS and this password loses access to backups.

## First deployment

Choose a reviewed, CI-passing full commit SHA from `main`. Commands below run from `/opt/moneyflow`:

```bash
DC=(docker compose --project-name moneyflow-production --project-directory "$PWD/infra" --env-file "$PWD/.env.production" -f "$PWD/infra/compose.production.yaml")
"${DC[@]}" build backup
"${DC[@]}" run --rm --no-deps backup init
bash scripts/production/deploy.sh FULL_40_CHARACTER_MAIN_COMMIT_SHA --first-install
bash scripts/production/verify.sh
```

`init` explicitly creates the restic repository only if it is missing; incorrect credentials or unreachable storage fail. Deployment fetches `main`, verifies that its commit matches the supplied SHA and fast-forwards the clean local `main` checkout. It builds the API with a commit-specific image tag, starts the private database and isolated restore database, verifies that the first-install database is empty, applies migrations, then creates and restores an encrypted backup before starting the public API. Caddy obtains and renews a trusted TLS certificate automatically when [DNS and ports 80/443 are ready](https://caddyserver.com/docs/automatic-https).

## Updates

```bash
bash scripts/production/deploy.sh FULL_40_CHARACTER_MAIN_COMMIT_SHA --check
bash scripts/production/deploy.sh FULL_40_CHARACTER_MAIN_COMMIT_SHA
```

The deployment lock prevents concurrent deployments. On every update, an offsite backup and isolated restore verification must succeed **before** migrations run. A backup or migration failure prevents replacement of the API container. The backup scheduler shares a lock with manual jobs; a busy backup also stops the deployment. Retry after the existing job finishes.

Use backward-compatible database migrations while the existing API is serving traffic. A successful migration followed by a failed API startup requires investigation and a compatible forward fix or a deliberate maintenance recovery. The script does not automatically reverse database migrations. Never run `docker compose down -v` on the production stack. Successful deployment records its SHA and persists the exact API image tag in `.env.production`.

From Windows, first obtain the server's SSH fingerprint through the provider console and verify your local known-hosts entry against it. Use an SSH agent containing your private key:

```powershell
.\scripts\production\deploy-via-ssh.ps1 `
  -Server YOUR_VPS_IP -User YOUR_DEPLOY_USER `
  -Commit FULL_40_CHARACTER_MAIN_COMMIT_SHA `
  -KnownHosts "$env:USERPROFILE\.ssh\moneyflow_known_hosts"
```

Add `-FirstInstall` for the first deploy or `-CheckOnly` for preflight. SSH uses `StrictHostKeyChecking=yes` and batch authentication. Do not bypass host-key checks.

## Android against the public API

Open `android/` in Android Studio and set this in your local Gradle properties, or pass it to Gradle:

```properties
MONEYFLOW_API_URL=https://YOUR_API_DOMAIN/
MONEYFLOW_RELEASE_API_URL=https://YOUR_API_DOMAIN/
```

The URL must end with `/`. Use the debug build to install and test immediately:

```powershell
cd android
.\gradlew.bat :app:installDebug -PMONEYFLOW_API_URL=https://YOUR_API_DOMAIN/
```

`assembleRelease -PMONEYFLOW_RELEASE_API_URL=https://YOUR_API_DOMAIN/` produces an unsigned release APK with the current project configuration. Configure a private release signing key before distribution; do not commit a keystore.

## Monitoring and backup operations

Open an SSH tunnel from your own computer:

```powershell
ssh -N -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$env:USERPROFILE\.ssh\moneyflow_known_hosts" `
  -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 -L 9093:127.0.0.1:9093 YOUR_DEPLOY_USER@YOUR_VPS_IP
```

Visit `http://localhost:3000` for Grafana, `http://localhost:9090/targets` for Prometheus and `http://localhost:9093` for Alertmanager. Grafana's username is `admin`; its password is in `secrets/grafana_admin_password`. Use the MoneyFlow operations dashboard. Verify all targets are up, backup/check/restore timestamps are fresh, API readiness works, and disk capacity remains available. Monitoring running on the same VPS cannot report a complete VPS outage by itself; configure an independent external HTTPS readiness check.

See [monitoring configuration](../infra/monitoring/README.md) for alert rules and optional receiver setup. The default receiver retains alerts in the local UI; it does not send notifications. A supplied webhook must accept Alertmanager's JSON format. Do not publish the administration endpoints.

Manual encrypted backup and restore drill:

```bash
"${DC[@]}" run --rm --no-deps backup backup
"${DC[@]}" run --rm --no-deps backup verify
"${DC[@]}" logs --tail 100 backup
```

Every successful backup is consistent, uploaded, checked and restored to the isolated private verifier. Retention pruning follows successful verification. The verifier uses disposable storage and never restores into the live application database. Test disaster recovery on a separate new PostgreSQL target: obtain a selected restic snapshot with its password, restore its archive with verification, then run `pg_restore --exit-on-error` against the new target and validate data before switching the application. Credentials, object storage, encryption-password escrow and the independent target must remain available outside the failed VPS.
