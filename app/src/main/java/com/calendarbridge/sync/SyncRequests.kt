package com.calendarbridge.sync

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.calendarbridge.Constants
import com.calendarbridge.auth.TokenStore

/**
 * Single place that talks to Android's SyncManager. Honor queues overlapping jobs until
 * they fail with `already-in-progress` and then never pull again — so we refuse to stack,
 * and we cancel a phantom lock if the last successful pull is older than the 15-minute interval.
 */
object SyncRequests {
    private const val TAG = "CalendarSyncAdapter"

    fun requestPull(context: Context, account: Account, cancelIfStuck: Boolean = false) {
        try {
            requestPullInner(context, account, cancelIfStuck)
        } catch (e: Exception) {
            // Never crash the foreground service over a sync-schedule failure.
            Log.e(TAG, "requestPull failed", e)
        }
    }

    private fun requestPullInner(context: Context, account: Account, cancelIfStuck: Boolean) {
        val authority = Constants.CONTENT_AUTHORITY
        val (active, pending) = syncStatus(account, authority)
        val lastOk = TokenStore(context).lastSuccessfulSyncMillis
        val stale = lastOk == 0L ||
            System.currentTimeMillis() - lastOk > (Constants.SYNC_INTERVAL_SECONDS + 60) * 1000L

        if (active || pending) {
            if (!cancelIfStuck && !stale) {
                Log.i(TAG, "Sync already active/pending — not stacking another request")
                return
            }
            Log.w(TAG, "Canceling stuck sync (active=$active pending=$pending stale=$stale)")
            ContentResolver.cancelSync(account, authority)
        } else if (stale) {
            ContentResolver.cancelSync(account, authority)
        }

        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true)
        }
        ContentResolver.requestSync(account, authority, extras)
        Log.i(TAG, "Requested calendar pull")
    }

    private fun syncStatus(account: Account, authority: String): Pair<Boolean, Boolean> {
        return try {
            Pair(
                ContentResolver.isSyncActive(account, authority),
                ContentResolver.isSyncPending(account, authority)
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read sync stats: ${e.message}")
            Pair(false, false)
        }
    }
}
