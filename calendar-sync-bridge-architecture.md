# Calendar Sync Bridge — Architecture Plan

**As built:** the local UI that actually shows events and reminders is **Huawei Calendar**, not the Google Calendar app (GBox’s Google Calendar sync stays broken). Current version and behavior: [PROJECT.md](PROJECT.md). This file is the original design.

## Problem

Google Calendar is installed via **GBox** (Android virtual space app) on a tablet.
Gmail, Drive, Maps, and YouTube sync correctly through GBox, but **Google Calendar's
local sync adapter does not** — events accepted or created in the cloud never make it
down into the local Calendar app, so pre-call reminders never fire.

## Goal

Build a lightweight Android APK that acts as a **two-way sync bridge (proxy)** between:

- **Cloud Google Calendar** (source of truth — updated e.g. by accepting an invite in Gmail)
- **Local Google Calendar app** on the device (via GBox)

The bridge keeps Android's shared calendar database up to date in both directions.
The **native Calendar app remains the source of the notification** — the bridge app
has no UI of its own after initial setup and never fires notifications directly.

## Core Design

The app registers as a standard Android **Sync Adapter + Account Authenticator**,
the same mechanism apps like DAVx5/CalDAV-Sync use to add third-party calendar
sources without relying on Google Play Services' native sync.

### 1. Setup (one-time only)

- A single Activity, shown only on first install.
- "Sign in with Google" via OAuth 2.0 (Custom Tabs) — no Google Sign-In SDK dependency.
- Stores the refresh token in `EncryptedSharedPreferences`.
- Activity closes itself and never reopens; app has no further UI.

### 2. Cloud → Local (pull)

- Uses Google Calendar API v3 **incremental sync via `syncToken`**.
  - First sync: full fetch, store the returned `syncToken`.
  - Every sync after: only changed/new/deleted events are returned — cheap and accurate.
- For each change, insert/update/delete the matching row in Android's
  `CalendarContract.Events` (and `Reminders`), tagging each row's `_sync_id`
  with the Google event ID for future matching.

### 3. Local → Cloud (push)

- A `ContentObserver` watches `CalendarContract.Events.CONTENT_URI`.
- Android automatically flags any locally-edited row with `dirty = 1` —
  the standard convention sync adapters use to detect local changes.
- On trigger: query `dirty = 1` rows, push each as `POST` (new) / `PATCH` (updated) /
  `DELETE` to Calendar API v3, then clear the `dirty` flag and store the
  returned event ID/etag back into `_sync_id`.

### 4. Conflict handling

- Each event carries an `etag` / `updated` timestamp from Google.
- If both sides changed the same event since the last sync: **last-write-wins**
  by timestamp. No manual merge needed for calendar use cases.

### 5. Trigger mechanism

- Local → Cloud: `ContentObserver` fires the push immediately on local edits.
- Cloud → Local: `requestSync()` via a periodic WorkManager job (~15 min, Android's
  minimum allowed interval) or `addPeriodicSync()`.
- Real-time cloud push (Google Cloud Pub/Sub webhooks) would need a public server
  endpoint — out of scope for a personal APK, so periodic polling is the practical choice.

### 6. Runtime behavior

- Runs as a **Foreground Service** (required by Android 8+ to avoid being killed) with
  a minimal, silent, low-priority persistent notification.
- `BroadcastReceiver` on `BOOT_COMPLETED` restarts the service after every reboot —
  no manual reopening ever needed.
- App stays visible in Settings > Apps like any normal app, so it can be uninstalled
  at any time. No launcher UI beyond the one-time setup screen.

## Required Permissions

```
INTERNET
READ_CALENDAR
WRITE_CALENDAR
AUTHENTICATE_ACCOUNTS
FOREGROUND_SERVICE
RECEIVE_BOOT_COMPLETED
POST_NOTIFICATIONS
SCHEDULE_EXACT_ALARM
WAKE_LOCK
```

Plus standard sync-adapter and account-authenticator XML declarations
(`res/xml/syncadapter.xml`, `res/xml/authenticator.xml`).

## Known Caveat

Some OEM skins (Samsung, Xiaomi, Huawei) apply battery optimization beyond stock
Android and can still kill foreground services. The setup screen should prompt the
user to manually exclude the app from battery optimization.

## Data Flow Summary

```
Gmail "Accept" (or any cloud edit)
        │
        ▼
Google Calendar (cloud, source of truth)
        │  Sync Adapter — pull via syncToken (~every 15 min)
        ▼
Android CalendarProvider (local shared DB)
        │  read by
        ▼
Native Calendar app (via GBox) → fires reminder/notification itself

Local edit in native Calendar app
        │  dirty=1 flag set by Android
        ▼
ContentObserver detects change
        │  push via Calendar API v3
        ▼
Google Calendar (cloud) updated
```
