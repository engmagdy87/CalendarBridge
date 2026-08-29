# Calendar Bridge

A silent Android app that two-way syncs **cloud Google Calendar** into the tablet’s shared calendar database so **Huawei Calendar** can show events and fire reminders.

GBox’s built-in Google Calendar sync does not work on this tablet. Gmail, Drive, and other Google apps sync; Calendar does not. This app talks to **Google Calendar API v3** over OAuth and writes into Android `CalendarContract`. It is not a calendar UI.

Current version: **1.1.5**. See [PROJECT.md](PROJECT.md) for the goal and what actually shipped. See [calendar-sync-bridge-architecture.md](calendar-sync-bridge-architecture.md) for the original design.

## What to use on the tablet

- **Huawei Calendar** — events and reminders. Turn on the Gmail calendar in the calendar list.
- **Google Calendar (the Google app)** — stays empty. It talks to Google’s cloud through GBox, which is the broken path.

## Sync

- **Automatic:** about every 15 minutes while the **Calendar sync running** notification is showing (including with the screen off; Honor may delay it).
- **Manual:** tap the Calendar Bridge icon, or tap that notification. You should see **Syncing now…**
- **Local → cloud:** edits in Huawei Calendar should push within a few seconds.

After a reboot, the service should start on its own if Honor **Auto-launch** / **Run in background** is allowed for Calendar Bridge, and battery is **Unrestricted**.

## Secrets (`.env`)

OAuth client IDs are **not** in source. Copy the example and fill in your Android OAuth client ID:

```bash
cp .env.example .env
```

`.env`:

```
OAUTH_CLIENT_ID=1234567890-abc123.apps.googleusercontent.com
```

Gradle reads `.env` at build time and injects `oauth_client_id`, the AppAuth redirect URI, and the manifest redirect scheme. `.env` is gitignored.

## One-time Google Cloud setup

1. [Google Cloud Console](https://console.cloud.google.com/) → create or pick a project.
2. Enable **Google Calendar API**.
3. OAuth consent: **External**, leave as **Testing**, add your Google account as a **test user**, scope `https://www.googleapis.com/auth/calendar`.
4. Create an **Android** OAuth client:
   - Package name: `com.calendarbridge`
   - SHA-1 from the debug keystore:

     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
     ```

5. On the Android client, turn **Enable custom URI scheme** **ON** (AppAuth needs it).
6. Put the client ID in `.env` as `OAUTH_CLIENT_ID`.

## Build

Needs JDK 17, Android SDK, and `local.properties` with `sdk.dir` (Android Studio creates this; it is gitignored).

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Install

Install on the same Android user as Huawei Calendar (if GBox **Install to Device** is on, that is the real tablet).

1. Sideload the debug APK.
2. Open **Calendar Bridge** → allow calendar (and notifications) → **Sign in with Google**.
3. If Google says the app is unverified, continue (Testing + test user).
4. Allow **ignore battery optimization**. On Honor: Settings → Apps → Calendar Bridge → Battery → **Unrestricted**, and App launch → Auto-launch / Run in background.
5. The setup screen closes. **Calendar sync running** stays in the shade — Android requires that for a foreground service.

Tap Calendar Bridge again later to force a pull.
