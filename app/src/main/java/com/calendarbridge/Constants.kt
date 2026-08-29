package com.calendarbridge

object Constants {
    const val ACCOUNT_TYPE = "com.calendarbridge.account"
    const val ACCOUNT_NAME = "Calendar Bridge"
    const val CONTENT_AUTHORITY = "com.android.calendar"

    const val CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar"
    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val CALENDAR_API_BASE = "https://www.googleapis.com/calendar/v3"

    // Local calendar row that our synced events live under (created on first sync if missing)
    const val LOCAL_CALENDAR_DISPLAY_NAME = "Google Calendar (Bridge)"

    // How often the OS should trigger a cloud -> local pull if no local edits happen
    const val SYNC_INTERVAL_SECONDS = 15L * 60L // 15 minutes, Android's practical minimum

    const val PREFS_FILE = "calendar_bridge_secure_prefs"
    const val PREF_REFRESH_TOKEN = "refresh_token"
    const val PREF_ACCESS_TOKEN = "access_token"
    const val PREF_ACCESS_TOKEN_EXPIRY = "access_token_expiry"
    const val PREF_SYNC_TOKEN = "calendar_sync_token"
    const val PREF_SYNC_QUERY_VERSION = "sync_query_version"
    const val PREF_LOCAL_CALENDAR_ID = "local_calendar_id"
    const val PREF_LAST_SUCCESSFUL_SYNC_MS = "last_successful_sync_ms"
    const val SYNC_QUERY_VERSION = 8

    // When Google sends no popup reminder, Honor still needs a local alarm.
    const val FALLBACK_REMINDER_MINUTES = 30

    // Keep the first pull small enough to finish inside Android's ~2 minute sync limit.
    const val PULL_PAST_MS = 3L * 24 * 60 * 60 * 1000
    const val PULL_FUTURE_MS = 30L * 24 * 60 * 60 * 1000

    const val NOTIFICATION_CHANNEL_ID = "calendar_bridge_service"
    const val FOREGROUND_NOTIFICATION_ID = 1001
}
