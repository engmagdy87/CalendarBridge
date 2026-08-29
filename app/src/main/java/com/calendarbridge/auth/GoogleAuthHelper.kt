package com.calendarbridge.auth

import android.content.Context
import com.calendarbridge.Constants
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Handles the token half of OAuth: exchanging an authorization code for tokens (once, during
 * setup) and silently refreshing the access token before each sync. The authorization-code half
 * (the actual sign-in UI) is handled by AppAuth in SetupActivity.
 */
class GoogleAuthHelper(context: Context) {

    private val tokenStore = TokenStore(context)
    private val http = OkHttpClient()

    /** Exchanges the one-time authorization code (from AppAuth) for a refresh + access token. */
    fun exchangeAuthCode(code: String, clientId: String, redirectUri: String, codeVerifier: String) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", codeVerifier)
            .build()

        val response = http.newCall(
            Request.Builder().url(Constants.TOKEN_ENDPOINT).post(body).build()
        ).execute()

        response.use {
            if (!it.isSuccessful) throw IOException("Token exchange failed: ${it.code} ${it.body?.string()}")
            val json = JSONObject(it.body!!.string())
            tokenStore.refreshToken = json.getString("refresh_token")
            tokenStore.accessToken = json.getString("access_token")
            tokenStore.accessTokenExpiry =
                System.currentTimeMillis() + (json.getLong("expires_in") * 1000L)
        }
    }

    /**
     * Returns a valid access token, refreshing it first if it's expired or close to expiring.
     * Call this at the start of every sync pass.
     */
    @Synchronized
    fun getValidAccessToken(clientId: String): String {
        val fresh = tokenStore.accessTokenExpiry - System.currentTimeMillis() > 60_000L
        if (fresh && tokenStore.accessToken != null) {
            return tokenStore.accessToken!!
        }

        val refreshToken = tokenStore.refreshToken
            ?: throw IllegalStateException("Not signed in — no refresh token stored")

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()

        val response = http.newCall(
            Request.Builder().url(Constants.TOKEN_ENDPOINT).post(body).build()
        ).execute()

        response.use {
            if (!it.isSuccessful) throw IOException("Token refresh failed: ${it.code} ${it.body?.string()}")
            val json = JSONObject(it.body!!.string())
            val accessToken = json.getString("access_token")
            tokenStore.accessToken = accessToken
            tokenStore.accessTokenExpiry =
                System.currentTimeMillis() + (json.getLong("expires_in") * 1000L)
            return accessToken
        }
    }
}
