package com.calendarbridge.sync

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract

/**
 * Watches CalendarContract.Events for local edits. Android automatically sets each edited row's
 * DIRTY flag, so we don't need to diff anything ourselves — we just need to know "something
 * changed" and ask the sync framework to run a pass soon. Debounced so a burst of edits (e.g.
 * bulk import) doesn't trigger a sync per row.
 */
class LocalChangeObserver(
    private val account: Account,
    private val resolver: ContentResolver,
    private val context: Context
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSync: Runnable? = null
    private val debounceMillis = 5_000L

    override fun onChange(selfChange: Boolean) {
        if (SyncInProgress.value) return
        pendingSync?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { requestSyncNow() }
        pendingSync = runnable
        handler.postDelayed(runnable, debounceMillis)
    }

    private fun requestSyncNow() {
        SyncRequests.requestPull(context, account, cancelIfStuck = false)
    }

    fun register() {
        resolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, this)
    }

    fun unregister() {
        resolver.unregisterContentObserver(this)
    }
}
