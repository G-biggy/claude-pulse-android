# Claude Pulse Android — Direct API Refactor

## Goal
Remove VPS proxy dependency. The app should call Anthropic’s API directly from the phone, handle OAuth token refresh on-device, and store credentials securely.

## Current State
- `ApiClient.kt` calls `BuildConfig.PULSE_API_URL` (G’s VPS at bridge.ghayyath.com) with an `X-Pulse-Token` header
- The VPS acts as a proxy: it holds the OAuth refresh token, refreshes access tokens, and forwards usage data
- This works but ties every user to G’s server

## Target State
- `ApiClient.kt` calls Anthropic directly:
  - Usage: `GET https://api.anthropic.com/api/oauth/usage` with `Authorization: Bearer <access_token>` and `anthropic-beta: oauth-2025-04-20`
  - Token refresh: `POST https://console.anthropic.com/v1/oauth/token` with `{"grant_type":"refresh_token","refresh_token":"<rt>","client_id":"9d1c250a-e61b-44d9-88ed-5944d1962f5e"}`
- Refresh token stored in EncryptedSharedPreferences on-device
- New `SetupActivity` for one-time token input
- Token refresh happens automatically every ~8 hours

---

## Build Steps

### Step 1 — Add Security Dependency
In `app/build.gradle.kts`, add:
```kotlin
implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

### Step 2 — Create TokenManager.kt
New file: `app/src/main/java/com/ghayyath/claudepulse/TokenManager.kt`

Responsibilities:
- Read/write refresh token + access token + expiry to EncryptedSharedPreferences
- `getAccessToken()`: returns current token if not expired, otherwise calls `refreshAccessToken()`
- `refreshAccessToken()`: POSTs to console.anthropic.com, stores new access token + new refresh token + expiry
- `hasCredentials()`: returns true if refresh token exists
- `clearCredentials()`: wipes everything (for re-setup)

EncryptedSharedPreferences setup:
```kotlin
val prefs = EncryptedSharedPreferences.create(
    "pulse_credentials",
    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
    context,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
```

Keys: `refresh_token`, `access_token`, `expires_at` (epoch millis)

### Step 3 — Rewrite ApiClient.kt
Remove:
- `BuildConfig.PULSE_API_URL` reference
- `BuildConfig.PULSE_API_TOKEN` reference
- `X-Pulse-Token` header

Add:
- Takes a `Context` parameter (needed for TokenManager)
- Calls `TokenManager.getAccessToken()` to get a valid token
- Hits `https://api.anthropic.com/api/oauth/usage` directly
- Adds headers: `Authorization: Bearer <token>`, `anthropic-beta: oauth-2025-04-20`
- On 401/403 response: calls `TokenManager.refreshAccessToken()`, retries once
- Returns same `UsageData` model (no changes to data layer)

### Step 4 — Create SetupActivity.kt
New file: `app/src/main/java/com/ghayyath/claudepulse/SetupActivity.kt`

Simple screen:
- Title: "Claude Pulse Setup"
- Subtitle: "Paste your refresh token from Claude Code"
- Multi-line text input field
- "Connect" button
- On tap: store token via TokenManager, attempt a test API call, show success/error
- On success: finish activity, trigger first widget update

Register in AndroidManifest.xml as the MAIN/LAUNCHER activity.

Layout: Dark theme matching widget (#1A1A1A background, white text).

### Step 5 — Update PulseWidget.kt
- Pass context to ApiClient for token management
- On auth errors: show "Tap to re-setup" instead of just "Offline"
- Tap action when auth error: open SetupActivity

### Step 6 — Remove BuildConfig Fields
Remove `PULSE_API_URL` and `PULSE_API_TOKEN` from `app/build.gradle.kts` (the `buildConfigField` entries).

### Step 7 — Update AndroidManifest.xml
- Add SetupActivity as MAIN/LAUNCHER
- Keep PulseWidget receiver as-is

### Step 8 — Build and Test
```bash
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
cp app/build/outputs/apk/debug/app-debug.apk ~/Desktop/claude-pulse-direct.apk
```

Test checklist:
- [ ] App opens to SetupActivity on first launch
- [ ] Pasting a valid refresh token + tapping Connect succeeds
- [ ] Widget shows real usage data after setup
- [ ] Widget auto-refreshes on schedule
- [ ] Token refresh works silently (wait for expiry or force by clearing access_token)
- [ ] Invalid token shows error state with re-setup prompt

---

## What NOT to Do
- Do NOT add a full app UI beyond SetupActivity. This is a widget-only app.
- Do NOT use Jetpack Compose for the widget (RemoteViews only). SetupActivity can use Compose or XML — doesn't matter.
- Do NOT change the visual design of the widget itself. Same dark theme, same colors, same bars.
- Do NOT add any analytics, crash reporting, or network calls to anywhere other than anthropic.com.

## After Refactor
- Commit: `feat: direct API access — remove VPS proxy dependency`
- Copy APK to `~/Desktop/claude-pulse-direct.apk` for G to test
- The old VPS-based APK stays on G's phone as-is until he verifies the new one works
