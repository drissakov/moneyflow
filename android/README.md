# MoneyFlow Android

Native Android MVP: Kotlin, Jetpack Compose / Material 3, ViewModel + StateFlow,
coroutines, Retrofit and Room. Minimum Android 8.0 (API 26); target / compile API 35.
The app has Russian UI and supports KZT, USD, EUR, JPY and KWD.

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
- Financial input uses `BigDecimal` → exact signed 64-bit minor units. USD/EUR/KZT
  use 2 digits, JPY 0, KWD 3. Neither parsing nor formatting uses floating point.
  Each submitted amount is bounded to ±1,000,000,000,000,000 minor units, matching
  the server's write limit; derived balances can span the full signed 64-bit range.
- Each user's accounts and latest 50 transactions per selected account are cached
  in app-private Room tables and displayed from Room. Cached reads remain available
  while an unexpired local session exists. Writes require server confirmation.
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

Transfers, category management, edits, refresh tokens and queued offline writes
are future milestones. There is no automatic currency conversion or combined
balance across currencies.
