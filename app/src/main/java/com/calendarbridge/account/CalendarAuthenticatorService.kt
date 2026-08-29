package com.calendarbridge.account

import android.app.Service
import android.content.Intent
import android.os.IBinder

class CalendarAuthenticatorService : Service() {

    private lateinit var authenticator: CalendarAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = CalendarAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
