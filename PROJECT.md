# Calendar Bridge — project log

Personal Android app. Not a product, not a calendar UI.

## Problem

Google apps run on an Honor tablet (model **TXZ-W09**) with **GBox**. Gmail, Drive, Maps, and YouTube sync. **Google Calendar does not**: cloud events never land in a local calendar database, so reminders never fire — including events accepted in Gmail.

## Goal

A small **two-way sync bridge** between:

- **Cloud Google Calendar** — source of truth (Gmail accept, web edits, etc.)
- **The tablet’s CalendarProvider** (`CalendarContract`) — what Huawei Calendar reads

The native calendar app owns notifications. After first-time Google sign-in, Calendar Bridge has no ongoing UI. It keeps a required foreground notification (**Calendar sync running**).

Original design: [calendar-sync-bridge-architecture.md](calendar-sync-bridge-architecture.md).

## As built (v1.1.5)

| Topic | What we do |
|---|---|
| Local calendar app | **Huawei Calendar**. The Google Calendar app stays empty (GBox cloud path). |
| Where events are written | Existing Gmail/`com.google` calendar when present, so Huawei Calendar shows them under that account. |
| Pull | Incremental `syncToken`. First/full pull is windowed (~3 days back, ~30 days forward) so Android’s ~2 minute sync limit is not exceeded. |
| Pull interval | ~15 minutes (Android’s practical minimum), plus **manual** pull by tapping the app or the persistent notification. |
| Push | `ContentObserver` on local dirty rows; debounce a few seconds. Full pulls skip pushing thousands of dirty rows. |
| Join links | One clean **Meet** / **Zoom** line in Location; Google redirect/tracking URLs stripped from notes. |
| Reminders | Tablet **popup** using Google’s minutes when present; **30 minutes** only if Google sent no time. Honor cannot show Gmail-only “email” reminders. |
| Auth | OAuth (AppAuth / Custom Tabs). Client ID lives in gitignored `.env`. Refresh token in `EncryptedSharedPreferences`. |
| Survival | Foreground service, boot receiver (including Honor/Huawei boot actions). Honor **Auto-launch** and unrestricted battery still required. |

Conflicts: last-write-wins. Recurring events are expanded instances (`singleEvents=true`); series-wide edits are still a rough edge.

## How to build and run

See [README.md](README.md).
