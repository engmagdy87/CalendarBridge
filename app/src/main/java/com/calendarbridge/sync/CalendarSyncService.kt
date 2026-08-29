package com.calendarbridge.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder

class CalendarSyncService : Service() {

    companion object {
        private var syncAdapter: CalendarSyncAdapter? = null
        private val lock = Any()

        private fun getAdapter(context: android.content.Context): CalendarSyncAdapter {
            synchronized(lock) {
                if (syncAdapter == null) {
                    syncAdapter = CalendarSyncAdapter(context.applicationContext, true)
                }
                return syncAdapter!!
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = getAdapter(this).syncAdapterBinder
}
