package com.calendarbridge.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.calendarbridge.Constants

/**
 * Encrypted-at-rest storage for the OAuth refresh/access tokens and sync bookkeeping
 * (calendar syncToken, local calendar row id). Nothing here is ever logged.
 */
class TokenStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            Constants.PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var refreshToken: String?
        get() = prefs.getString(Constants.PREF_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(Constants.PREF_REFRESH_TOKEN, value).apply()

    var accessToken: String?
        get() = prefs.getString(Constants.PREF_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(Constants.PREF_ACCESS_TOKEN, value).apply()

    /** Epoch millis when the current access token expires. */
    var accessTokenExpiry: Long
        get() = prefs.getLong(Constants.PREF_ACCESS_TOKEN_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(Constants.PREF_ACCESS_TOKEN_EXPIRY, value).apply()

    /** Google Calendar API incremental-sync token. Null forces a full resync. */
    var calendarSyncToken: String?
        get() = prefs.getString(Constants.PREF_SYNC_TOKEN, null)
        set(value) = prefs.edit().putString(Constants.PREF_SYNC_TOKEN, value).apply()

    var syncQueryVersion: Int
        get() = prefs.getInt(Constants.PREF_SYNC_QUERY_VERSION, 0)
        set(value) = prefs.edit().putInt(Constants.PREF_SYNC_QUERY_VERSION, value).apply()

    var localCalendarId: Long
        get() = prefs.getLong(Constants.PREF_LOCAL_CALENDAR_ID, -1L)
        set(value) = prefs.edit().putLong(Constants.PREF_LOCAL_CALENDAR_ID, value).apply()

    var lastSuccessfulSyncMillis: Long
        get() = prefs.getLong(Constants.PREF_LAST_SUCCESSFUL_SYNC_MS, 0L)
        set(value) = prefs.edit().putLong(Constants.PREF_LAST_SUCCESSFUL_SYNC_MS, value).apply()

    fun isSignedIn(): Boolean = refreshToken != null

    fun clear() {
        prefs.edit().clear().apply()
    }
}
