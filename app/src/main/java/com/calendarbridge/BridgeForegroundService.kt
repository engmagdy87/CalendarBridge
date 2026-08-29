package com.calendarbridge

import android.accounts.Account
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.calendarbridge.sync.LocalChangeObserver
import com.calendarbridge.sync.SyncRequests

class BridgeForegroundService : Service() {

    private var observer: LocalChangeObserver? = null
    private val handler = Handler(Looper.getMainLooper())
    private val periodicPull = object : Runnable {
        override fun run() {
            requestPullNow()
            handler.postDelayed(this, Constants.SYNC_INTERVAL_SECONDS * 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        startForeground(Constants.FOREGROUND_NOTIFICATION_ID, buildSilentNotification())

        val account = Account(Constants.ACCOUNT_NAME, Constants.ACCOUNT_TYPE)
        setUpPeriodicSync(account)

        observer = LocalChangeObserver(account, contentResolver, applicationContext).also { it.register() }

        // Honor's SyncManager periodic job often sits queued and never runs. The
        // foreground service is already alive, so we tick ourselves every 15 minutes
        // and also arm an exact alarm in case the process is frozen.
        handler.post(periodicPull)
        scheduleExactAlarm()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PERIODIC_PULL || intent?.action == ACTION_MANUAL_PULL) {
            requestPullNow()
            if (intent.action == ACTION_PERIODIC_PULL) {
                scheduleExactAlarm()
            }
        }
        // START_STICKY: if the OS kills this service under memory pressure, restart it once
        // resources free up, without needing to redeliver the original intent.
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(periodicPull)
        observer?.unregister()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestPullNow() {
        val account = Account(Constants.ACCOUNT_NAME, Constants.ACCOUNT_TYPE)
        SyncRequests.requestPull(applicationContext, account, cancelIfStuck = true)
    }

    private fun setUpPeriodicSync(account: Account) {
        ContentResolver.setIsSyncable(account, Constants.CONTENT_AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, Constants.CONTENT_AUTHORITY, true)
        ContentResolver.addPeriodicSync(
            account,
            Constants.CONTENT_AUTHORITY,
            android.os.Bundle.EMPTY,
            Constants.SYNC_INTERVAL_SECONDS
        )
    }

    private fun scheduleExactAlarm() {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(this, BridgeForegroundService::class.java).setAction(ACTION_PERIODIC_PULL)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, ALARM_REQUEST_CODE, intent, flags)
        } else {
            PendingIntent.getService(this, ALARM_REQUEST_CODE, intent, flags)
        }
        val triggerAt = SystemClock.elapsedRealtime() +
            (Constants.SYNC_INTERVAL_SECONDS + 45) * 1000L
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pending
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pending
                )
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pending
            )
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                "Calendar sync",
                NotificationManager.IMPORTANCE_MIN // silent, minimal visual footprint
            ).apply {
                description = "Keeps your calendar synced in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildSilentNotification(): Notification {
        val tapToSync = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Calendar sync running")
            .setContentText("Tap to sync now")
            .setSmallIcon(R.drawable.ic_stat_calendar)
            .setContentIntent(tapToSync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_PERIODIC_PULL = "com.calendarbridge.action.PERIODIC_PULL"
        const val ACTION_MANUAL_PULL = "com.calendarbridge.action.MANUAL_PULL"
        private const val ALARM_REQUEST_CODE = 1002
    }
}
