package com.calendarbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.calendarbridge.auth.TokenStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent.action != "com.huawei.intent.action.BOOT_COMPLETED"
        ) {
            return
        }

        // Only start if setup has actually been completed — otherwise there's nothing to sync yet.
        if (TokenStore(context).isSignedIn()) {
            ContextCompat.startForegroundService(context, Intent(context, BridgeForegroundService::class.java))
        }
    }
}
