# MoneyFlow Android

Native Android MVP: Kotlin, Jetpack Compose / Material 3, ViewModel + StateFlow,
coroutines, Retrofit and Room. Minimum Android 8.0 (API 26); target / compile API 35.
The app has Russian UI and supports 11 curated currencies: KZT, USD, EUR, JPY,
KWD, RUB, GBP, CHF, AUD, CAD and CNY. This is the application's supported catalog,
not the full upstream provider catalog.

Version 0.5.0 uses a monochrome Material 3 palette in both system light and dark
themes. The aggregate balance of all accounts leads the overview, valued in KZT
by default; changing the selected account only changes its transaction list.
Individual accounts show their original and converted balances; calculation
details show each contribution and quote dates. Missing quotes never produce a
misleading complete total. Inter and Inter Display are bundled offline with
Russian/Kazakh glyphs and the tenge sign; see [font notices](app/src/main/assets/fonts/README.md).
Category fields open a searchable bottom sheet with standard/custom groups,
expandable expense subcategories and creation actions. Numbered chart segments
match the category legend, so reports do not depend on distinguishing shades.

## Run locally

1. Start the backend on port 8080 using the repository's root instructions.
2. Open this directory in Android Studio. Install Android SDK 35 and Build Tools
   35.0.0. Gradle uses JDK 17 or a compatible newer Studio JDK.
3. Run the `app` debug configuration on an Android emulator.

The emulator API address defaults to `http://10.0.2.2:8080/`.
For a real phone, use a computer reachable from the phone or USB port forwarding:

```powershell
adb reverse tcp:8080 tcp:8080
.\gradlew.bat :app:assembleDebug -PMONEYFLOW_API_URL=http://127.0.0.1:8080/
```

Or configure a local LAN URL with `-PMONEYFLOW_API_URL=http://192.168.1.10:8080/`.
The trailing slash is required. Only debug builds permit HTTP. Avoid sending real
credentials over a shared network during local development.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

With an emulator or device connected, run migration/isolation/concurrency tests:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Tests use dedicated databases and separate preferences. Gradle's connected test
runner can uninstall the debug app after testing, removing its local cache and
login session; use a test emulator, then reinstall the APK and sign in again.
Server accounts and transactions are preserved. Room migration 1 → 2 adds
preferences/portfolio caches without deleting existing accounts or transactions.
Migration 2 → 3 adds the user-scoped category cache and a nullable transaction
category ID. Existing transactions remain uncategorized until assigned by the user.
Migration 3 → 4 adds the movement flag with a default of false; existing history
is preserved. Movements affect balances and stay out of income/expense charts.
Migration 4 → 5 adds the versioned ledger caches; 5 → 6 adds user-scoped planning
snapshots without recreating existing tables. The server must apply migration
006 before this app uses budgets, schedules and forecasts.

## Planning (P1)

Open More → Planning or the dashboard's safe-to-spend card. Budgets track monthly
category spending in the original currency, including child categories. Scheduled
income/expense remains separate from the ledger until explicitly confirmed.
Weekly, monthly and yearly repeats retain their original calendar anchor.

Safe-to-spend subtracts a reserve and unpaid mandatory expenses from selected cash
and card balances. Expected income never creates spendable funds. Planning has
its own base currency and uses the existing current FX cache; incomplete quotes or
truncated history suppress aggregate totals. Cached planning is read-only.

Optional reminders use Android JobScheduler with a daily interval, network access
and Android 13 notification permission. They show no financial amounts, require
the captured user session to remain valid, and open Planning. Delivery depends on
Android scheduling and is not an exact alarm. See [P1 rules and QA](../docs/PLANNING.md).

## PDF statement import

The Import screen accepts the verified Russian native-text Kaspi Gold/Halyk
KZT layouts. PDFBox runs locally behind byte/page/text/row limits; original PDF
bytes do not leave the device. Unsupported layouts, scans, passwords, incomplete
rows and inconsistent totals block import.

Select or create a KZT account with the correct statement opening balance, then
explicitly send normalized rows for a server preview. Review duplicates,
categories and own-account/cash movements before confirming. The server saves
the selected batch atomically. Recent import history supports explicit undo.

Commit/undo parameters and their UUID are encrypted and saved before a request.
An uncertain response retains that exact request for retry. A recovery read error
retains the ciphertext and blocks new writes until the owner reviews server
history. All requests capture authorization and reject results after a session
switch. No original PDFs or extracted bank rows are persisted in Room or in the
recovery record.

Apply server migration `004_statement_imports.sql` before installing this app.
See [the import guide](../docs/STATEMENT_IMPORT.md) and [OpenAPI](../api/openapi.yaml).

The opt-in `PrivateStatementPdfReaderTest` reads local fixtures supplied through
instrumentation arguments in `files/pdf-import-private`. Real statements and
independent oracles are never packaged into either APK or committed to Git.

## Categories and monthly analytics

Expenses support standard/custom categories and one level of subcategories.
Income uses flat standard/custom categories. Create a category in the Categories
screen or while entering a transaction; select a transaction to change its category.
The server must have migration `003_categories_analytics.sql` applied.

The Analytics screen shows a monthly pie chart, amounts and percentages for
expenses or income. Expense roots can be expanded into subcategories, with direct
root spending shown separately. Reports use the device timezone and include all
server transactions, rather than the 50 cached rows shown in the account history.
Choose the native account currency: currencies are reported separately without FX
conversion, and opening balances are excluded. Categories are cached per user;
fetching a report and creating a category require a server connection.

## Release configuration

Set an HTTPS API URL when building for release:

```powershell
.\gradlew.bat :app:assembleRelease -PMONEYFLOW_RELEASE_API_URL=https://api.your-domain.com/
```

The default release address is deliberately unreachable. Configure Android Studio
release signing with your own key before distributing an APK/AAB. No signing keys
or credentials are stored in the project. Cleartext traffic is blocked in release.

## Current behavior

- Email/password registration and sign-in; password policy is 12–128 UTF-8 bytes.
- Create accounts with exact signed opening balances, then record income/expenses.
- Choose a preferred base currency. It is stored per user on the server, with a
  local per-user fallback (default KZT). Offline changes remain marked pending and
  are synchronized before the next preference read; concurrent updates serialize.
- The dashboard presents the original balances alongside a Frankfurter estimate
  in the base currency. Quotes show their provider observation dates and retrieval
  time. Missing quotes produce an explicitly incomplete estimate with a labeled
  partial amount, never a full total. Stale rates are marked; cached rates older
  than the default 7-day observation/retrieval window are hidden.
- The converter calls the server and displays its exact rounded result. It is a
  reference calculation: it does not exchange money or create ledger operations.
  Rates are decimal strings parsed with BigDecimal, with no client floating point
  or recomputation from the rounded display rate. Same-currency conversion needs
  no external quote.
- Financial input uses `BigDecimal` → exact signed 64-bit minor units. USD/EUR/KZT
  use 2 digits, JPY 0, KWD 3. Neither parsing nor formatting uses floating point.
  Each submitted amount is bounded to ±1,000,000,000,000,000 minor units, matching
  the server's write limit; derived balances can span the full signed 64-bit range.
- Each user's accounts and latest 50 transactions per selected account are cached
  in app-private Room tables and displayed from Room. Cached reads remain available
  while an unexpired local session exists. Writes require server confirmation.
  Portfolio snapshots are cached per user/base only and are shown only while their
  account IDs, currencies and balances match the current Room records. Writes
  invalidate snapshots, and older in-flight reads cannot repopulate them. Explicit
  logout deletes financial caches/snapshots; per-user currency choices remain local.
- Access tokens and pending transaction requests are AES-GCM encrypted with a
  key held by Android Keystore. Passwords are not persisted. Android backup is off.
- Before sending a transaction, the exact payload and its idempotency key are
  encrypted and persisted. After an uncertain network result or an app restart,
  the user can retry that same request. No background outbox or automatic retries
  run in this MVP. A pending request must be resolved before creating another one.
- Explicit sign-out immediately removes the local token and that user's Room cache,
  then attempts server revocation with the captured token. If revocation fails, the
  app reports that the server session remains valid until expiry. Both sign-out and
  expiry preserve unresolved encrypted requests separately per user, for recovery
  after that same user signs in again. Signing into another account cannot overwrite
  the first user's pending request. Confirmed/definitively rejected requests are removed.

Screens use semantic text labels and test tags (`auth_email`, `auth_password`,
`auth_submit`, `auth_switch`, `add_account`, `account_name`, `account_opening`,
`account_submit`, `add_transaction`, `transaction_amount`, `transaction_note`,
`transaction_submit`, `retry_pending`, `refresh`, `logout`).

Refresh tokens and queued offline writes remain future milestones. The combined balance is an informational valuation; account
balances are never automatically converted or rewritten.
