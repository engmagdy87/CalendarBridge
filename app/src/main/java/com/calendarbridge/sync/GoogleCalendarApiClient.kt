package com.calendarbridge.sync

import com.calendarbridge.Constants
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Thrown when Google reports the stored syncToken is no longer valid (HTTP 410 Gone). */
class SyncTokenInvalidatedException : Exception("syncToken expired — a full resync is required")

data class PullResult(val events: List<JSONObject>, val nextSyncToken: String)

data class GoogleReminder(val minutes: Int, val method: String)

class GoogleCalendarApiClient(private val accessToken: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val eventsUrl = "${Constants.CALENDAR_API_BASE}/calendars/primary/events"
    private val calendarUrl = "${Constants.CALENDAR_API_BASE}/calendars/primary"

    private fun authedRequest(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $accessToken")

    /** Calendar-level default reminders (used when an event has `reminders.useDefault = true`). */
    fun getPrimaryCalendarDefaults(): List<GoogleReminder> {
        val fromCalendar = runCatching { fetchDefaultReminders(calendarUrl) }.getOrDefault(emptyList())
        if (fromCalendar.isNotEmpty()) return fromCalendar
        val calendarListUrl = "${Constants.CALENDAR_API_BASE}/users/me/calendarList/primary"
        return runCatching { fetchDefaultReminders(calendarListUrl) }.getOrDefault(emptyList())
    }

    private fun fetchDefaultReminders(url: String): List<GoogleReminder> {
        val response = http.newCall(authedRequest(url).build()).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("defaultReminders failed: ${it.code} ${it.body?.string()}")
            return parseReminderArray(JSONObject(it.body!!.string()).optJSONArray("defaultReminders"))
        }
    }

    /**
     * Pulls all events changed since [syncToken]. Pass null to force a full initial sync.
     * Handles pagination transparently and returns the token to store for next time.
     */
    fun pullChanges(syncToken: String?): PullResult {
        val events = mutableListOf<JSONObject>()
        var pageToken: String? = null
        var nextSyncToken: String? = null

        do {
            val url = eventsUrl.toHttpUrl().newBuilder().apply {
                if (syncToken != null) {
                    addQueryParameter("syncToken", syncToken)
                    addQueryParameter("showDeleted", "true")
                } else {
                    addQueryParameter("singleEvents", "true")
                    addQueryParameter("showDeleted", "true")
                    addQueryParameter("maxResults", "250")
                    addQueryParameter("conferenceDataVersion", "1")
                    val now = System.currentTimeMillis()
                    addQueryParameter("timeMin", toRfc3339(now - Constants.PULL_PAST_MS))
                    addQueryParameter("timeMax", toRfc3339(now + Constants.PULL_FUTURE_MS))
                }
                if (pageToken != null) addQueryParameter("pageToken", pageToken)
            }.build()

            val response = http.newCall(authedRequest(url.toString()).build()).execute()
            response.use {
                if (it.code == 410) throw SyncTokenInvalidatedException()
                if (syncToken != null && it.code == 400) {
                    throw SyncTokenInvalidatedException()
                }
                if (!it.isSuccessful) throw IOException("pullChanges failed: ${it.code} ${it.body?.string()}")

                val json = JSONObject(it.body!!.string())
                val items = json.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) events.add(items.getJSONObject(i))
                }

                pageToken = json.optString("nextPageToken").ifEmpty { null }
                nextSyncToken = json.optString("nextSyncToken").ifEmpty { null }
            }
        } while (pageToken != null)

        return PullResult(events, nextSyncToken ?: syncToken.orEmpty())
    }

    fun createEvent(eventBody: JSONObject): JSONObject {
        val response = http.newCall(
            authedRequest(eventsUrl)
                .post(eventBody.toString().toRequestBody(jsonMedia))
                .build()
        ).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("createEvent failed: ${it.code} ${it.body?.string()}")
            return JSONObject(it.body!!.string())
        }
    }

    fun updateEvent(eventId: String, eventBody: JSONObject): JSONObject {
        val response = http.newCall(
            authedRequest("$eventsUrl/$eventId")
                .patch(eventBody.toString().toRequestBody(jsonMedia))
                .build()
        ).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("updateEvent failed: ${it.code} ${it.body?.string()}")
            return JSONObject(it.body!!.string())
        }
    }

    fun deleteEvent(eventId: String) {
        val response = http.newCall(
            authedRequest("$eventsUrl/$eventId").delete().build()
        ).execute()
        response.use {
            // 410/404 both mean "already gone" from the server's perspective — treat as success.
            if (!it.isSuccessful && it.code != 410 && it.code != 404) {
                throw IOException("deleteEvent failed: ${it.code} ${it.body?.string()}")
            }
        }
    }

    private fun toRfc3339(epochMillis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMillis))
    }
}

internal fun parseReminderArray(arr: org.json.JSONArray?): List<GoogleReminder> {
    if (arr == null || arr.length() == 0) return emptyList()
    val out = mutableListOf<GoogleReminder>()
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val minutes = jsonMinutes(item) ?: continue
        out.add(
            GoogleReminder(
                minutes = minutes,
                method = item.optString("method", "popup").ifEmpty { "popup" }
            )
        )
    }
    return out
}

private fun jsonMinutes(item: JSONObject): Int? {
    if (!item.has("minutes") || item.isNull("minutes")) return null
    return try {
        item.getInt("minutes")
    } catch (_: Exception) {
        item.optString("minutes").toIntOrNull()
    }
}
