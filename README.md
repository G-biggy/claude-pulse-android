# Claude Pulse · Android

A minimal Android home screen widget that shows your Claude subscription usage at a glance. No app to open — the widget lives on your home screen and updates automatically.

![License: MIT](https://img.shields.io/badge/license-MIT-green)
![Platform: Android](https://img.shields.io/badge/platform-Android%208%2B-brightgreen)
![Kotlin](https://img.shields.io/badge/language-Kotlin-purple)

---

<p align="center">
  <img src="docs/images/widget.png" width="400" alt="Claude Pulse widget on home screen" />
</p>

## What It Does

A resizable home screen widget that displays:

- **Session** — your current 5-hour session utilization
- **Weekly** — 7-day rolling usage across all models
- **Sonnet** — weekly Sonnet-specific usage
- **Plan badge** — shows your subscription tier (Free / Pro / Max 5x / Max 20x)
- **Reset countdowns** — time remaining until each window resets
- **Color-coded** — brand green `#6ee7b7` under 50%, then yellow / orange / red as usage climbs
- **Refresh Now** — tap to force an immediate data refresh from the widget itself
- **Auto-refresh** — updates every 10 minutes in the background

Percentage text changes color as usage climbs. Tap the widget body to open your Claude usage page in the browser.

### Color Thresholds

| Utilization | Color | Hex |
|-------------|-------|-----|
| 0–49% | 🟢 Green (brand) | `#6ee7b7` |
| 50–74% | 🟡 Yellow | `#FF9800` |
| 75–89% | 🟠 Orange | `#FF5722` |
| 90–100% | 🔴 Red | `#F44336` |

### Widget Sizes

The widget is fully resizable. Drag it wider or taller and the layout adapts:

| Size | Layout |
|------|--------|
| **Compact** | Three progress bars with percentages only |
| **Standard** | Full layout with labels, reset timers, plan badge, and refresh button |

The three bar rows distribute evenly across whatever height you give the widget — compact when small, relaxed when tall.

## How It Works

The app connects directly to Anthropic’s usage API from your phone. No server, no proxy, no middleman.

### Setup Flow

1. On your computer, run a helper script to extract your OAuth refresh token from Claude Code
2. Open Claude Pulse on your phone and paste the token
3. Tap **Connect** — the app verifies the token and starts fetching usage data
4. Add the widget to your home screen from your launcher’s widget picker

You can reopen the app at any time to update your token if needed.

### Why a Refresh Token?

Claude’s usage API uses OAuth authentication tied to Claude Code. There’s no standalone API key for usage data. The refresh token is a long-lived credential that lets the app obtain short-lived access tokens without re-authenticating. You extract it once, and the app maintains the session indefinitely.

## Prerequisites

| Requirement | Details |
|------------|--------|
| Android 8.0+ (API 26) | Covers 95%+ of active devices |
| Claude Code on a computer | Needed once, to extract the refresh token |
| Sideloading enabled | The APK isn’t on Google Play — you install it directly |

## Install

### 1. Get Your Refresh Token

On your Mac or Linux machine with Claude Code installed and signed in:

```bash
# macOS — reads from Keychain
security find-generic-password -s 'Claude Code-credentials' -w | python3 -c "
import sys, json
creds = json.load(sys.stdin)
print(creds.get('claudeAiOauth', {}).get('refreshToken', 'NOT FOUND'))
"
```

```bash
# Linux / alternative — reads from credentials file
python3 -c "
import json
with open('$HOME/.claude/.credentials.json') as f:
    creds = json.load(f)
print(creds.get('claudeAiOauth', {}).get('refreshToken', 'NOT FOUND'))
"
```

Copy the output. You’ll paste it into the app in a moment.

### 2. Install the APK

Download the latest APK from the [Releases](../../releases) page, or build from source (see below).

Transfer it to your phone and install. Android will ask you to allow installation from unknown sources — this is normal for sideloaded apps.

### 3. First Launch

<p align="center">
  <img src="docs/images/setup.png" width="280" alt="Claude Pulse setup screen" />
</p>

Open Claude Pulse → paste your refresh token → tap **Connect**. The app will verify the token and start fetching usage data. Add the widget to your home screen from your launcher’s widget picker.

## Build from Source

```bash
git clone https://github.com/G-biggy/claude-pulse-android.git
cd claude-pulse-android

# Requires Android SDK and Java 17+
# If you don’t have the SDK:
#   brew install --cask android-commandlinetools   (macOS)
#   sdkmanager "platforms;android-34" "build-tools;34.0.0"

./gradlew assembleRelease
```

The APK will be at `app/build/outputs/apk/release/app-release-unsigned.apk`.

To sign it for your device:

```bash
# Generate a keystore (one-time)
keytool -genkey -v -keystore release.keystore -alias pulse -keyalg RSA -keysize 2048 -validity 10000

# Sign
apksigner sign --ks release.keystore --ks-key-alias pulse app/build/outputs/apk/release/app-release-unsigned.apk
```

## API Details

Same as the [Mac version](https://github.com/G-biggy/claude-pulse-mac) — this app uses **undocumented** Anthropic endpoints:

**Usage data:**
```
GET https://api.anthropic.com/api/oauth/usage
Authorization: Bearer <access_token>
anthropic-beta: oauth-2025-04-20
```

**Token refresh:**
```
POST https://console.anthropic.com/v1/oauth/token
Content-Type: application/json

{
  "grant_type": "refresh_token",
  "refresh_token": "<refresh_token>",
  "client_id": "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
}
```

The `client_id` is Claude Code’s official OAuth client ID.

> **These endpoints are undocumented and may change without notice.** If the API changes, the widget will show an error state. Check this repo for updates.

## Project Structure

```
claude-pulse-android/
├── app/
│   └── src/main/
│       ├── java/com/.../claudepulse/
│       │   ├── ApiClient.kt        # Anthropic API + token refresh
│       │   ├── TokenManager.kt     # Secure token storage + auto-refresh
│       │   ├── PulseWidget.kt      # Widget rendering and update logic
│       │   ├── UsageData.kt        # Data models + plan detection
│       │   └── SetupActivity.kt    # Token setup screen (reopenable)
│       └── res/
│           ├── layout/             # Widget layouts (compact + full)
│           └── xml/                # Widget provider metadata
├── build.gradle.kts
├── LICENSE
└── README.md
```

## Security Notes

- Your refresh token is stored locally on your device
- The token is never transmitted anywhere except directly to Anthropic’s servers over HTTPS
- No analytics, no tracking, no telemetry — the app talks to exactly one domain: `anthropic.com`
- The app requests only `INTERNET` and `RECEIVE_BOOT_COMPLETED` permissions

## Troubleshooting

**Widget shows "Offline"**
Check your internet connection. The widget retries on the next 10-minute cycle, or tap **Refresh Now**.

**Widget shows "Auth Error"**
Your refresh token may have been revoked. Open the app from your launcher and paste a new token.

**Widget doesn’t appear in widget picker**
After installing the APK, you may need to restart your launcher. On most phones: long-press home screen → Widgets → search "Pulse".

## Acknowledgments

Companion project to [Claude Pulse · macOS](https://github.com/G-biggy/claude-pulse-mac).

Inspired by [Blimp-Labs/claude-usage-bar](https://github.com/Blimp-Labs/claude-usage-bar).

## Disclaimer

This project is not affiliated with, endorsed by, or officially supported by Anthropic. It uses undocumented API endpoints that may change at any time. Use at your own discretion.

## License

MIT — do whatever you want with it. See [LICENSE](LICENSE) for details.
