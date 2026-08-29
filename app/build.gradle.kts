plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun loadDotEnv(root: File): Map<String, String> {
    val names = listOf(".env", ".env.example")
    var envFile: File? = null
    for (name in names) {
        val candidate = File(root, name)
        if (candidate.exists()) {
            envFile = candidate
            break
        }
    }
    val source = envFile ?: return emptyMap()
    val out = mutableMapOf<String, String>()
    for (raw in source.readLines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        out[line.substring(0, eq).trim()] = line.substring(eq + 1).trim().trim('"')
    }
    return out
}

val oauthClientId = loadDotEnv(rootProject.projectDir)["OAUTH_CLIENT_ID"]
    ?: "YOUR_CLIENT_ID.apps.googleusercontent.com"
val oauthClientIdPrefix = oauthClientId.removeSuffix(".apps.googleusercontent.com")

android {
    namespace = "com.calendarbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.calendarbridge"
        minSdk = 26 // Foreground service notification behavior requires 26+
        targetSdk = 34
        versionCode = 17
        versionName = "1.1.5"

        resValue("string", "oauth_client_id", oauthClientId)
        resValue("string", "oauth_redirect_uri", "com.googleusercontent.apps.$oauthClientIdPrefix:/oauth2redirect")

        // Used by AppAuth's RedirectUriReceiverActivity manifest placeholder.
        // Must match the reversed-client-id scheme of your OAuth Android client.
        manifestPlaceholders["appAuthRedirectScheme"] = "com.googleusercontent.apps.$oauthClientIdPrefix"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // OAuth 2.0 / PKCE authorization-code flow via Custom Tabs (no client secret needed)
    implementation("net.openid:appauth:0.11.1")

    // Direct REST calls to Google Calendar API v3 (no heavyweight Google API client / Play Services)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Encrypted local storage for refresh token
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
