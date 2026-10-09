# MoneyFlow

An Android personal finance app backed by Go and PostgreSQL. This first release supports email/password sign-in, accounts, income, expenses, exact balances, and cached browsing.

## Architecture

```text
Kotlin / Compose -> ViewModel / StateFlow -> Repository / Room + Retrofit
  -> REST /api/v1 -> Go / Gin application services / GORM -> PostgreSQL
```

The server is one deployable application. PostgreSQL owns accepted financial records; Room supplies the Android UI with cached data. Writes require a server response. Transaction requests retain their payload and idempotency key on the device until their result is known, allowing safe retries after process restarts.

## Run the server

Requirements: a working Docker Engine with Compose, or Docker Desktop. From the repository root:

```powershell
Copy-Item .env.example .env
docker compose --env-file .env -f infra/compose.yaml up --build -d
Invoke-RestMethod http://localhost:8080/readyz
```

Compose starts PostgreSQL, applies embedded versioned SQL migrations once, then starts the API. Data persists in the `postgres_data` volume. PostgreSQL binds to loopback; API port 8080 is available to devices on your network. Stop with `docker compose --env-file .env -f infra/compose.yaml down`. Adding `-v` erases this stack's database volume.

If PostgreSQL is already available, configure `DATABASE_URL` in `.env` and run `./scripts/start-api.ps1` from PowerShell. This script loads the environment file, runs migrations, and starts the server. Go 1.25 or later is required. The API does not silently change the schema on startup.

## Run Android

Requirements: JDK 17 or 21, Android SDK 35, and build-tools 35.0.0. Set `ANDROID_HOME` or put your SDK location in the ignored `android/local.properties` file.

```powershell
cd android
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On Linux/macOS use `bash ./gradlew` with the same tasks. APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

The debug build uses `http://10.0.2.2:8080/`, the emulator's address for the development computer. For a physical device, supply your computer's LAN address:

```powershell
./gradlew.bat :app:assembleDebug -PMONEYFLOW_API_URL=http://192.168.1.10:8080/
```

Allow the server through your local firewall if needed. Cleartext HTTP is enabled only for debug builds. For a release supply `-PMONEYFLOW_RELEASE_API_URL=https://your-api.example.com/` and configure signing. API URLs must end with a slash. The default release URL is a nonfunctional placeholder.

Register, create an account, select it, and add income or an expense. The app displays the latest 50 transactions for the selected account. Supported currencies: USD, EUR, KZT, JPY, KWD with scales 2, 2, 2, 0, 3. Room data is scoped to the signed-in user. Sessions and unconfirmed requests are encrypted with an Android Keystore key; application backups are disabled. Logout removes the token and cached financial records immediately. An unresolved request stays encrypted for that user, allowing confirmation after re-login with the same key.

## API and financial rules

The complete contract is in `api/openapi.yaml`. Health probes: `/healthz`, `/readyz`.

- Passwords contain 12–128 UTF-8 bytes and use salted Argon2id hashes. Opaque bearer sessions are hashed on the server, expire, and are revoked on logout.
- Session identity determines ownership. Account lookups and financial mutations check the authenticated owner.
- Amounts are canonical integer strings in minor units: USD 12.99 is `"1299"`. Entry amounts and opening balances are capped at 10^15 minor units. Derived balances are checked against signed 64-bit bounds.
- Transaction creation requires a UUID `Idempotency-Key`. Identical retries return the original result; conflicting reuse returns 409. The transaction and deduplication record commit together.
- Balances are derived from opening balances and recorded income/expense. The client never submits an independently editable balance.

## Verify

With the API running, use PowerShell 7 from the repository root:

```powershell
./scripts/smoke.ps1
```

This creates two disposable users and checks an expense, exact balance, duplicate retry, user isolation, conflicting reuse, and logout. It leaves test records; use a development database.

```powershell
cd backend
go test ./...
go vet ./...
# Integration tests require a separate disposable database.
$env:TEST_DATABASE_URL = 'postgres://user:password@localhost:5432/moneyflow_test?sslmode=disable'
go test -count=1 ./...
```

Integration tests may reset tables in the test database. Never point them at real data. CI runs backend/PostgreSQL tests and Android build, lint, and unit tests.

Verified locally on October 9, 2026: Go tests/static analysis and Linux builds; real PostgreSQL 18.1 integration tests; an API smoke test; backup restoration; Android debug build, 14 unit/contract tests and lint with no errors. The app completed registration, account creation and expense entry on an API 36.1 emulator; database inspection confirmed one expense and the exact balance. The final APK restored its encrypted session after installation. Lint still reports dependency/target-version suggestions; update the target SDK against current store requirements before a Play release.

Both Compose configurations validate. Container execution was not tested locally because Docker Desktop failed during startup; the server was exercised against a separate native PostgreSQL instance instead.

## Deployment foundation

`infra/compose.production.yaml` is a standalone stack with a private API/database network and Caddy exposing HTTPS. Copy `.env.production.example` to `.env.production` and replace every placeholder. The database hostname in `DATABASE_URL` is `db`; URL-encode special password characters. Configure DNS before starting Caddy.

```sh
docker compose --env-file .env.production -f infra/compose.production.yaml up --build -d
```

Use the production file alone. It publishes neither PostgreSQL nor the Go API. The API trusts only Caddy's configured private IP. Change `CADDY_PRIVATE_IP` and `PRIVATE_NETWORK_SUBNET` together if this subnet conflicts with your host.

Before storing real data, configure encrypted off-server backups and verify a restore. Public deployment, signed releases, email verification/password recovery, categories, transfers, monthly reports, and offline writes are subsequent milestones; they are not implemented in this release.
