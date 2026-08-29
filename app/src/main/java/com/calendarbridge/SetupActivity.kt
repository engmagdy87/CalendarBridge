package com.calendarbridge

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.calendarbridge.auth.GoogleAuthHelper
import com.calendarbridge.auth.TokenStore
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.concurrent.thread

class SetupActivity : AppCompatActivity() {

    private lateinit var authService: AuthorizationService
    private lateinit var statusText: TextView

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)
        handleAuthorizationResponse(response, exception)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val calendarOk = granted[Manifest.permission.READ_CALENDAR] == true &&
            granted[Manifest.permission.WRITE_CALENDAR] == true
        if (calendarOk) {
            startSignIn()
        } else {
            Toast.makeText(this, "Calendar permission is required for sync to work", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tokenStore = TokenStore(this)
        if (tokenStore.isSignedIn()) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, BridgeForegroundService::class.java)
                    .setAction(BridgeForegroundService.ACTION_MANUAL_PULL)
            )
            Toast.makeText(this, "Syncing now…", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContentView(R.layout.activity_setup)
        statusText = findViewById(R.id.statusText)
        authService = AuthorizationService(this)

        findViewById<android.widget.Button>(R.id.signInButton).setOnClickListener {
            requestPermissionsThenSignIn()
        }
    }

    private fun requestPermissionsThenSignIn() {
        val needed = mutableListOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startSignIn()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startSignIn() {
        val serviceConfig = AuthorizationServiceConfiguration(
            android.net.Uri.parse(Constants.AUTH_ENDPOINT),
            android.net.Uri.parse(Constants.TOKEN_ENDPOINT)
        )

        val request = AuthorizationRequest.Builder(
            serviceConfig,
            getString(R.string.oauth_client_id),
            ResponseTypeValues.CODE,
            android.net.Uri.parse(getString(R.string.oauth_redirect_uri))
        )
            .setScope(Constants.CALENDAR_SCOPE)
            // AppAuth owns "prompt"; Google needs access_type=offline for a refresh token.
            .setPrompt("consent")
            .setAdditionalParameters(mapOf("access_type" to "offline"))
            .build()

        try {
            val authIntent = authService.getAuthorizationRequestIntent(request)
            signInLauncher.launch(authIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleAuthorizationResponse(
        response: AuthorizationResponse?,
        exception: AuthorizationException?
    ) {
        if (exception != null || response == null) {
            Toast.makeText(this, "Sign-in failed: ${exception?.message}", Toast.LENGTH_LONG).show()
            return
        }

        statusText.text = "Finishing setup..."
        val code = response.authorizationCode ?: run {
            Toast.makeText(this, "No authorization code returned", Toast.LENGTH_LONG).show()
            return
        }
        val codeVerifier = response.request.codeVerifier ?: ""

        thread {
            try {
                GoogleAuthHelper(applicationContext).exchangeAuthCode(
                    code = code,
                    clientId = getString(R.string.oauth_client_id),
                    redirectUri = getString(R.string.oauth_redirect_uri),
                    codeVerifier = codeVerifier
                )
                createLocalAccount()
                runOnUiThread {
                    requestBatteryExemption()
                    startBridgeServiceAndFinish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Setup failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun createLocalAccount() {
        val accountManager = AccountManager.get(this)
        val account = Account(Constants.ACCOUNT_NAME, Constants.ACCOUNT_TYPE)
        accountManager.addAccountExplicitly(account, null, null)
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    private fun startBridgeServiceAndFinish() {
        ContextCompat.startForegroundService(this, Intent(this, BridgeForegroundService::class.java))
        finish()
    }

    override fun onDestroy() {
        if (::authService.isInitialized) authService.dispose()
        super.onDestroy()
    }
}
