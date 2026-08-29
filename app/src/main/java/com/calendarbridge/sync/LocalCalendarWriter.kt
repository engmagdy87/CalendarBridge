package com.calendarbridge.sync

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import com.calendarbridge.Constants
import com.calendarbridge.auth.TokenStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Applies changes pulled from Google Calendar into Android's shared CalendarProvider, and reads
 * back locally-dirty rows so they can be pushed up. This is the only class that touches
 * CalendarContract directly.
 */
class LocalCalendarWriter(
    private val resolver: ContentResolver,
    private val account: Account,
    private val tokenStore: TokenStore
) {

    // Account that owns the calendar we write into. Google Calendar only displays
    // calendars with type com.google, so we prefer that over our own account.
    private var ownerAccountName: String = account.name
    private var ownerAccountType: String = account.type

    /** Finds the calendar we should write into. Prefers the existing Google calendar. */
    fun ensureLocalCalendar(): Long {
        val google = findGoogleCalendar()
        if (google != null) {
            ownerAccountName = google.accountName
            ownerAccountType = google.accountType
            if (tokenStore.localCalendarId != google.id) {
                deleteOurBridgeCalendars()
                tokenStore.calendarSyncToken = null
                tokenStore.localCalendarId = google.id
            }
            return google.id
        }

        val cached = tokenStore.localCalendarId
        if (cached != -1L) return cached
        return createBridgeCalendar()
    }

    private data class CalendarRow(val id: Long, val accountName: String, val accountType: String)

    private fun findGoogleCalendar(): CalendarRow? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY
        )
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf("com.google"),
            null
        )?.use { cursor ->
            var fallback: CalendarRow? = null
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            val typeIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
            val primaryIdx = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            while (cursor.moveToNext()) {
                val row = CalendarRow(
                    id = cursor.getLong(idIdx),
                    accountName = cursor.getString(nameIdx),
                    accountType = cursor.getString(typeIdx)
                )
                val isPrimary = primaryIdx >= 0 && cursor.getInt(primaryIdx) == 1
                if (isPrimary) return row
                if (fallback == null) fallback = row
            }
            return fallback
        }
        return null
    }

    private fun deleteOurBridgeCalendars() {
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(Constants.ACCOUNT_TYPE),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                resolver.delete(
                    asSyncAdapter(
                        ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id),
                        account.name,
                        account.type
                    ),
                    null,
                    null
                )
            }
        }
    }

    private fun createBridgeCalendar(): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
            put(CalendarContract.Calendars.NAME, Constants.LOCAL_CALENDAR_DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, Constants.LOCAL_CALENDAR_DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF4285F4.toInt()) // Google blue
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account.name)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }

        val uri = asSyncAdapter(CalendarContract.Calendars.CONTENT_URI)
        val result = resolver.insert(uri, values)
            ?: throw IllegalStateException("Failed to create local calendar row")
        val id = ContentUris.parseId(result)
        tokenStore.localCalendarId = id
        return id
    }

    /** Upserts one Google event into the local Events table by matching on _SYNC_ID. */
    fun applyRemoteEvent(
        calendarId: Long,
        event: JSONObject,
        calendarDefaultReminders: List<GoogleReminder>
    ) {
        val googleEventId = event.getString("id")
        val isCancelled = event.optString("status") == "cancelled"

        val existingRowId = findLocalRowId(googleEventId)

        if (isCancelled) {
            if (existingRowId != null) {
                resolver.delete(asSyncAdapter(eventUri(existingRowId)), null, null)
            }
            return
        }

        val reminders = resolveReminders(event, calendarDefaultReminders)
        val values = eventToContentValues(calendarId, event, reminders.isNotEmpty())

        val rowId = if (existingRowId != null) {
            resolver.update(asSyncAdapter(eventUri(existingRowId)), values, null, null)
            existingRowId
        } else {
            val uri = resolver.insert(asSyncAdapter(CalendarContract.Events.CONTENT_URI), values)
            uri?.let { ContentUris.parseId(it) }
        } ?: return

        applyReminders(rowId, reminders)
        applyAttendees(rowId, event)
    }

    /** True if more than [max] dirty rows exist — used to skip a huge TITLE/DESCRIPTION query. */
    fun hasExcessDirty(calendarId: Long, max: Int): Boolean {
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DIRTY} = 1",
            arrayOf(calendarId.toString()),
            null
        )?.use { cursor ->
            var n = 0
            while (cursor.moveToNext()) {
                n++
                if (n > max) return true
            }
        }
        return false
    }
    fun getDirtyLocalEvents(
        calendarId: Long,
        onlyUnsyncedOrDeleted: Boolean = false,
        limit: Int = 20
    ): List<LocalDirtyEvent> {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events._SYNC_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.DELETED
        )
        val selection = buildString {
            append("${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DIRTY} = 1")
            if (onlyUnsyncedOrDeleted) {
                append(" AND (${CalendarContract.Events._SYNC_ID} IS NULL OR ${CalendarContract.Events.DELETED} = 1)")
            }
        }
        val results = mutableListOf<LocalDirtyEvent>()

        resolver.query(
            CalendarContract.Events.CONTENT_URI, projection, selection,
            arrayOf(calendarId.toString()), null
        )?.use { cursor ->
            while (results.size < limit && cursor.moveToNext()) {
                results.add(
                    LocalDirtyEvent(
                        rowId = cursor.getLong(0),
                        googleEventId = cursor.getString(1),
                        title = cursor.getString(2),
                        description = cursor.getString(3),
                        location = cursor.getString(4),
                        startMillis = cursor.getLong(5),
                        endMillis = cursor.getLong(6),
                        timeZone = cursor.getString(7) ?: TimeZone.getDefault().id,
                        deleted = cursor.getInt(8) == 1
                    )
                )
            }
        }
        return results
    }

    /** Clears the dirty flag and stamps the row with its confirmed Google event id, post-push. */
    fun markClean(rowId: Long, googleEventId: String) {
        val values = ContentValues().apply {
            put(CalendarContract.Events._SYNC_ID, googleEventId)
            put(CalendarContract.Events.DIRTY, 0)
        }
        resolver.update(asSyncAdapter(eventUri(rowId)), values, null, null)
    }

    /**
     * Clears DIRTY on rows that already have a Google id. Used when a previous pass (or a
     * reminder insert that forgot CALLER_IS_SYNCADAPTER) flagged thousands of server-origin
     * events dirty — pushing them all exceeds Android's sync timeout and the pull never runs.
     */
    fun clearDirtyOnSyncedRows(calendarId: Long): Int {
        val values = ContentValues().apply {
            put(CalendarContract.Events.DIRTY, 0)
        }
        return resolver.update(
            asSyncAdapter(CalendarContract.Events.CONTENT_URI),
            values,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DIRTY} = 1 AND ${CalendarContract.Events._SYNC_ID} IS NOT NULL",
            arrayOf(calendarId.toString())
        )
    }

    fun deleteLocalRow(rowId: Long) {
        resolver.delete(asSyncAdapter(eventUri(rowId)), null, null)
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun findLocalRowId(googleEventId: String): Long? {
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events._SYNC_ID} = ?",
            arrayOf(googleEventId),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    private fun eventToContentValues(
        calendarId: Long,
        event: JSONObject,
        hasAlarm: Boolean
    ): ContentValues {
        val start = event.getJSONObject("start")
        val end = event.getJSONObject("end")
        val isAllDay = start.has("date")

        val (startMillis, tz) = parseGoogleDateTime(start, isAllDay)
        val (endMillis, _) = parseGoogleDateTime(end, isAllDay)

        val attendees = event.optJSONArray("attendees")
        val organizerEmail = event.optJSONObject("organizer")?.optString("email").orEmpty()

        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events._SYNC_ID, event.getString("id"))
            put(CalendarContract.Events.TITLE, event.optString("summary").ifEmpty { "(No title)" })
            put(CalendarContract.Events.DESCRIPTION, descriptionWithJoinLinks(event))
            put(CalendarContract.Events.EVENT_LOCATION, locationWithJoinLinks(event))
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            put(CalendarContract.Events.ALL_DAY, if (isAllDay) 1 else 0)
            put(CalendarContract.Events.HAS_ALARM, if (hasAlarm) 1 else 0)
            put(CalendarContract.Events.HAS_ATTENDEE_DATA, if (attendees != null && attendees.length() > 0) 1 else 0)
            put(CalendarContract.Events.AVAILABILITY, availabilityFromGoogle(event.optString("transparency")))
            put(CalendarContract.Events.ACCESS_LEVEL, accessFromGoogle(event.optString("visibility")))
            if (organizerEmail.isNotEmpty()) {
                put(CalendarContract.Events.ORGANIZER, organizerEmail)
            }
            googleEventColor(event.optString("colorId"))?.let {
                put(CalendarContract.Events.EVENT_COLOR, it)
            }
            put(CalendarContract.Events.DIRTY, 0) // data came from the server, not a local edit
        }
    }

    private fun resolveReminders(
        event: JSONObject,
        calendarDefaultReminders: List<GoogleReminder>
    ): List<GoogleReminder> {
        val remindersObj = event.optJSONObject("reminders")
        val fromGoogle = when {
            remindersObj == null -> calendarDefaultReminders
            else -> {
                val overrides = parseReminderArray(remindersObj.optJSONArray("overrides"))
                when {
                    overrides.isNotEmpty() -> overrides
                    remindersObj.optBoolean("useDefault", true) -> calendarDefaultReminders
                    else -> emptyList()
                }
            }
        }
        return ensureDevicePopup(fromGoogle)
    }

    /** Honor only shows popup/alert. Use Google's minutes even if Google sent email/SMS. */
    private fun ensureDevicePopup(reminders: List<GoogleReminder>): List<GoogleReminder> {
        val minutes = reminders.map { it.minutes }.distinct()
        if (minutes.isEmpty()) {
            return listOf(GoogleReminder(Constants.FALLBACK_REMINDER_MINUTES, "popup"))
        }
        return minutes.map { GoogleReminder(minutes = it, method = "popup") }
    }

    private fun applyReminders(eventRowId: Long, reminders: List<GoogleReminder>) {
        resolver.delete(
            asSyncAdapter(CalendarContract.Reminders.CONTENT_URI),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventRowId.toString())
        )

        for (reminder in reminders) {
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventRowId)
                put(CalendarContract.Reminders.MINUTES, reminder.minutes)
                put(CalendarContract.Reminders.METHOD, reminderMethodFromGoogle(reminder.method))
            }
            resolver.insert(asSyncAdapter(CalendarContract.Reminders.CONTENT_URI), values)
        }
    }

    private fun applyAttendees(eventRowId: Long, event: JSONObject) {
        resolver.delete(
            asSyncAdapter(CalendarContract.Attendees.CONTENT_URI),
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventRowId.toString())
        )

        val attendees = event.optJSONArray("attendees") ?: return
        for (i in 0 until attendees.length()) {
            val attendee = attendees.optJSONObject(i) ?: continue
            val email = attendee.optString("email")
            if (email.isEmpty()) continue
            val values = ContentValues().apply {
                put(CalendarContract.Attendees.EVENT_ID, eventRowId)
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                put(
                    CalendarContract.Attendees.ATTENDEE_NAME,
                    attendee.optString("displayName").ifEmpty { email }
                )
                put(CalendarContract.Attendees.ATTENDEE_STATUS, attendeeStatusFromGoogle(attendee.optString("responseStatus")))
                put(
                    CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                    if (attendee.optBoolean("organizer")) {
                        CalendarContract.Attendees.RELATIONSHIP_ORGANIZER
                    } else {
                        CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
                    }
                )
                put(
                    CalendarContract.Attendees.ATTENDEE_TYPE,
                    when {
                        attendee.optBoolean("resource") -> CalendarContract.Attendees.TYPE_RESOURCE
                        attendee.optBoolean("optional") -> CalendarContract.Attendees.TYPE_OPTIONAL
                        else -> CalendarContract.Attendees.TYPE_REQUIRED
                    }
                )
            }
            resolver.insert(asSyncAdapter(CalendarContract.Attendees.CONTENT_URI), values)
        }
    }

    private fun parseGoogleDateTime(node: JSONObject, isAllDay: Boolean): Pair<Long, String> {
        return if (isAllDay) {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            Pair(fmt.parse(node.getString("date"))!!.time, "UTC")
        } else {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            val millis = fmt.parse(node.getString("dateTime"))!!.time
            Pair(millis, node.optString("timeZone").ifEmpty { TimeZone.getDefault().id })
        }
    }

    private fun eventUri(rowId: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, rowId)

    private fun asSyncAdapter(uri: android.net.Uri) =
        asSyncAdapter(uri, ownerAccountName, ownerAccountType)

    private fun asSyncAdapter(uri: android.net.Uri, name: String, type: String) = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, name)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, type)
        .build()
}

private fun locationWithJoinLinks(event: JSONObject): String {
    val joins = labeledJoinLines(event)
    val place = stripUrls(event.optString("location", "")).trim()
    return when {
        place.isEmpty() -> joins.joinToString("\n")
        joins.isEmpty() -> place
        else -> place + "\n" + joins.joinToString("\n")
    }
}

private fun descriptionWithJoinLinks(event: JSONObject): String {
    val joins = joinUrls(event)
    return cleanNotes(event.optString("description", ""), joins)
}

private fun labeledJoinLines(event: JSONObject): List<String> =
    joinUrls(event).map { url ->
        when {
            url.contains("meet.google.com", ignoreCase = true) -> "Meet  $url"
            isZoomUrl(url) -> "Zoom  $url"
            else -> url
        }
    }

/** Meet first, then Zoom. Tracking / google-redirect wrappers are unwrapped or dropped. */
private fun joinUrls(event: JSONObject): List<String> {
    val found = LinkedHashSet<String>()
    event.optString("hangoutLink").takeIf { it.isNotEmpty() }?.let { found.add(unwrapUrl(it)) }
    val entryPoints = event.optJSONObject("conferenceData")?.optJSONArray("entryPoints")
    if (entryPoints != null) {
        for (i in 0 until entryPoints.length()) {
            val point = entryPoints.optJSONObject(i) ?: continue
            val type = point.optString("entryPointType")
            if (type == "video" || type == "more") {
                val uri = unwrapUrl(point.optString("uri"))
                if (isJoinUrl(uri)) found.add(uri)
            }
        }
    }
    found.addAll(httpUrlsIn(event.optString("location")).map { unwrapUrl(it) })
    found.addAll(httpUrlsIn(event.optString("description")).map { unwrapUrl(it) })
    return found.filter { isJoinUrl(it) }.sortedBy { url ->
        when {
            url.contains("meet.google.com", ignoreCase = true) -> 0
            isZoomUrl(url) -> 1
            else -> 2
        }
    }
}

private fun isZoomUrl(url: String): Boolean {
    val lower = url.lowercase(Locale.US)
    return lower.contains("zoom.us") || lower.contains("zoom.com")
}

private fun isJoinUrl(url: String): Boolean =
    url.startsWith("http") &&
        (url.contains("meet.google.com", ignoreCase = true) || isZoomUrl(url)) &&
        !isTrackingUrl(url)

private fun isTrackingUrl(url: String): Boolean {
    val lower = url.lowercase(Locale.US)
    if (lower.contains("google.com/url")) return true
    if (lower.contains("meetinguuid")) return true
    if (lower.contains("signature=") && !isZoomUrl(url)) return true
    return false
}

private fun unwrapUrl(raw: String): String {
    var current = raw.trim().trimEnd('.', ',', ';', ')', ']')
    repeat(3) {
        if (!current.contains("google.com/url", ignoreCase = true)) return current
        val encoded = current.substringAfter("q=", "").substringBefore("&")
        if (encoded.isEmpty()) return current
        current = try {
            java.net.URLDecoder.decode(encoded, "UTF-8")
        } catch (_: Exception) {
            return current
        }
    }
    return current
}

private fun httpUrlsIn(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    return Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map { it.value.trimEnd('.', ',', ';', ')', ']') }
        .toList()
}

private fun stripUrls(text: String): String {
    if (text.isEmpty()) return text
    return Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        .replace(text, " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun cleanNotes(text: String, joinUrls: List<String>): String {
    if (text.isEmpty()) return text
    var result = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).replace(text) { match ->
        val raw = match.value.trimEnd('.', ',', ';', ')', ']')
        val clean = unwrapUrl(raw)
        when {
            isTrackingUrl(raw) || isTrackingUrl(clean) -> ""
            joinUrls.any { it == clean || clean.contains(it) || it.contains(clean) } -> ""
            isJoinUrl(clean) -> ""
            else -> clean
        }
    }
    result = result.replace(Regex("[ \\t]+"), " ")
    result = result.replace(Regex("\n{3,}"), "\n\n")
    return result.trim()
}

private fun reminderMethodFromGoogle(method: String): Int = when (method.lowercase(Locale.US)) {
    "email" -> CalendarContract.Reminders.METHOD_EMAIL
    "sms" -> CalendarContract.Reminders.METHOD_SMS
    else -> CalendarContract.Reminders.METHOD_ALERT
}

private fun attendeeStatusFromGoogle(status: String): Int = when (status) {
    "accepted" -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
    "declined" -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
    "tentative" -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
    else -> CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
}

private fun availabilityFromGoogle(transparency: String): Int =
    if (transparency == "transparent") {
        CalendarContract.Events.AVAILABILITY_FREE
    } else {
        CalendarContract.Events.AVAILABILITY_BUSY
    }

private fun accessFromGoogle(visibility: String): Int = when (visibility) {
    "public" -> CalendarContract.Events.ACCESS_PUBLIC
    "private" -> CalendarContract.Events.ACCESS_PRIVATE
    "confidential" -> CalendarContract.Events.ACCESS_CONFIDENTIAL
    else -> CalendarContract.Events.ACCESS_DEFAULT
}

/** Google Calendar event color IDs → ARGB. Unknown / missing colorId keeps the calendar color. */
private fun googleEventColor(colorId: String): Int? = when (colorId) {
    "1" -> 0xFFA4BDFC.toInt()
    "2" -> 0xFF7AE7BF.toInt()
    "3" -> 0xFFDBADFF.toInt()
    "4" -> 0xFFFF887C.toInt()
    "5" -> 0xFFFBD75B.toInt()
    "6" -> 0xFFFFB878.toInt()
    "7" -> 0xFF46D6DB.toInt()
    "8" -> 0xFFE1E1E1.toInt()
    "9" -> 0xFF5484ED.toInt()
    "10" -> 0xFF51B749.toInt()
    "11" -> 0xFFDC2127.toInt()
    else -> null
}

data class LocalDirtyEvent(
    val rowId: Long,
    val googleEventId: String?,
    val title: String?,
    val description: String?,
    val location: String?,
    val startMillis: Long,
    val endMillis: Long,
    val timeZone: String,
    val deleted: Boolean
)
