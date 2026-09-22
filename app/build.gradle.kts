plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val autoVersionCode = ciRunNumber?.let { 1000 + it } ?: 112
val autoVersionName = ciRunNumber?.let { "1.0.${1000 + it}-tachowatch" } ?: "1.0.112-tachowatch"
val stableDebugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")

val releaseKeystorePath = System.getenv("TACHOWATCH_UPLOAD_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("TACHOWATCH_UPLOAD_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("TACHOWATCH_UPLOAD_KEY_ALIAS")
val releaseKeyPassword = System.getenv("TACHOWATCH_UPLOAD_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.pylikv.tachowatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pylikv.tachowatch"
        minSdk = 26
        targetSdk = 36
        versionCode = autoVersionCode
        versionName = autoVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = stableDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        if (hasReleaseSigning) {
            create("releaseUpload") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }

        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("releaseUpload")
            }
        }
    }

    lint {
        // Reporting-only initially: surface legacy findings without blocking APK builds.
        abortOnError = false
        checkDependencies = true
        htmlReport = true
        xmlReport = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

detekt {
    // Start in reporting mode so existing legacy findings do not break APK builds.
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    ignoreFailures = true
}

ktlint {
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(true)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
