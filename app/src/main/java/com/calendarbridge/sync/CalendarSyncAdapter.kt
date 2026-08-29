package com.calendarbridge.sync

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.util.Log
import com.calendarbridge.Constants
import com.calendarbridge.R
import com.calendarbridge.auth.GoogleAuthHelper
import com.calendarbridge.auth.TokenStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private const val TAG = "CalendarSyncAdapter"
private const val MAX_PUSH_PER_PASS = 20

internal object SyncInProgress {
    @Volatile
    var value = false
}

class CalendarSyncAdapter(context: Context, autoInitialize: Boolean) :
    AbstractThreadedSyncAdapter(context, autoInitialize) {

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        val appContext = context.applicationContext
        SyncInProgress.value = true
        try {
            val tokenStore = TokenStore(appContext)
            if (!tokenStore.isSignedIn()) {
                Log.w(TAG, "Sync requested but no account is signed in yet — skipping")
                return
            }

            if (tokenStore.syncQueryVersion < Constants.SYNC_QUERY_VERSION) {
                tokenStore.calendarSyncToken = null
                tokenStore.syncQueryVersion = Constants.SYNC_QUERY_VERSION
            }

            val authHelper = GoogleAuthHelper(appContext)
            val clientId = appContext.getString(R.string.oauth_client_id)
            val accessToken = authHelper.getValidAccessToken(clientId)

            val writer = LocalCalendarWriter(context.contentResolver, account, tokenStore)
            val calendarId = writer.ensureLocalCalendar()
            val api = GoogleCalendarApiClient(accessToken)

            val fullPull = tokenStore.calendarSyncToken == null
            // A full pull after a failed first sync can see thousands of dirty rows.
            // Pushing them all exceeds Android's sync timeout; the pull is source of truth.
            if (!fullPull) {
                try {
                    pushLocalChanges(writer, api)
                } catch (e: Exception) {
                    Log.e("CalendarBridge", "Push failed — continuing with pull: ${e.message}", e)
                }
            }

            pullRemoteChanges(tokenStore, writer, api, calendarId)
            Log.i("CalendarBridge", "Sync pass succeeded")

        } catch (e: SyncTokenInvalidatedException) {
            Log.w("CalendarBridge", "syncToken expired — clearing it and retrying with a full resync")
            TokenStore(appContext).calendarSyncToken = null
            syncResult.fullSyncRequested = true
        } catch (e: Exception) {
            Log.e("CalendarBridge", "Sync pass failed: ${e.message}", e)
            writeSyncLog(appContext, "Sync pass failed: ${e.message}")
            syncResult.stats.numIoExceptions++
        } finally {
            SyncInProgress.value = false
        }
    }

    private fun pushLocalChanges(writer: LocalCalendarWriter, api: GoogleCalendarApiClient) {
        val calendarId = writer.ensureLocalCalendar()
        if (writer.hasExcessDirty(calendarId, MAX_PUSH_PER_PASS)) {
            Log.w("CalendarBridge", "Skipping local push — too many dirty rows; pull still runs")
            return
        }
        val toPush = writer.getDirtyLocalEvents(
            calendarId,
            onlyUnsyncedOrDeleted = false,
            limit = MAX_PUSH_PER_PASS
        )

        for (dirty in toPush) {
            try {
                when {
                    dirty.deleted && dirty.googleEventId != null -> {
                        api.deleteEvent(dirty.googleEventId)
                        writer.deleteLocalRow(dirty.rowId)
                    }
                    dirty.googleEventId == null -> {
                        val created = api.createEvent(dirty.toGoogleEventJson())
                        writer.markClean(dirty.rowId, created.getString("id"))
                    }
                    else -> {
                        val updated = api.updateEvent(dirty.googleEventId, dirty.toGoogleEventJson())
                        writer.markClean(dirty.rowId, updated.getString("id"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push local change for row ${dirty.rowId}", e)
                // Leave it dirty — it'll be retried on the next sync pass.
            }
        }
    }

    private fun pullRemoteChanges(
        tokenStore: TokenStore,
        writer: LocalCalendarWriter,
        api: GoogleCalendarApiClient,
        calendarId: Long
    ) {
        val calendarDefaults = try {
            api.getPrimaryCalendarDefaults()
        } catch (e: Exception) {
            Log.w("CalendarBridge", "Could not read calendar default reminders; using ${Constants.FALLBACK_REMINDER_MINUTES}-minute popup", e)
            listOf(GoogleReminder(minutes = Constants.FALLBACK_REMINDER_MINUTES, method = "popup"))
        }
        val result = api.pullChanges(tokenStore.calendarSyncToken)
        Log.i("CalendarBridge", "Pulled ${result.events.size} remote events")
        for (event in result.events) {
            try {
                writer.applyRemoteEvent(calendarId, event, calendarDefaults)
            } catch (e: Exception) {
                Log.e("CalendarBridge", "Failed to apply event ${event.optString("id")}: ${e.message}", e)
            }
        }
        tokenStore.calendarSyncToken = result.nextSyncToken
        tokenStore.lastSuccessfulSyncMillis = System.currentTimeMillis()
    }
}

private fun writeSyncLog(context: Context, msg: String) {
    try {
        context.openFileOutput("sync.log", Context.MODE_APPEND).bufferedWriter().use { out ->
            out.appendLine("${System.currentTimeMillis()} $msg")
        }
    } catch (_: Exception) {
    }
}

private fun LocalDirtyEvent.toGoogleEventJson(): JSONObject {
    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone(this@toGoogleEventJson.timeZone)
    }
    return JSONObject().apply {
        put("summary", title ?: "(No title)")
        put("description", description ?: "")
        put("location", location ?: "")
        put(
            "start",
            JSONObject().put("dateTime", isoFormat.format(startMillis)).put("timeZone", timeZone)
        )
        put(
            "end",
            JSONObject().put("dateTime", isoFormat.format(endMillis)).put("timeZone", timeZone)
        )
    }
}
