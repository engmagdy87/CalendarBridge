package com.calendarbridge.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.content.Context
import android.os.Bundle

/**
 * Minimal account authenticator. We never prompt for credentials through the system account
 * flow — sign-in happens once in SetupActivity — so most callbacks just report "not supported".
 */
class CalendarAuthenticator(private val context: Context) : AbstractAccountAuthenticator(context) {

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle {
        throw UnsupportedOperationException()
    }

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle {
        // Account creation is driven by SetupActivity (AccountManager.addAccountExplicitly),
        // not by the system "Add account" UI, so there's nothing to launch here.
        val result = Bundle()
        result.putString(android.accounts.AccountManager.KEY_ACCOUNT_TYPE, accountType)
        return result
    }

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?
    ): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle {
        throw UnsupportedOperationException()
    }

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle? = null

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?
    ): Bundle {
        val result = Bundle()
        result.putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, false)
        return result
    }
}
