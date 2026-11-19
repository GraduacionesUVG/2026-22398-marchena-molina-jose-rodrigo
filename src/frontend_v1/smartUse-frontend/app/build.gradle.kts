import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 0 means "use the default in JobLimits".
fun localInt(key: String): Int = localProps.getProperty(key)?.trim()?.toIntOrNull() ?: 0

fun localString(key: String): String? = localProps.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

// The backend URL is never in source: `smartuse.baseUrl` in local.properties,
// else the SMARTUSE_BASE_URL environment variable. Without either, the build
// still works (unit and instrumented tests use a fake backend) but every real
// request fails against the reserved `.invalid` domain.
val placeholderBaseUrl = "https://backend.invalid"
val smartUseBaseUrl: String = localString("smartuse.baseUrl")
    ?: System.getenv("SMARTUSE_BASE_URL")?.trim()?.takeIf { it.isNotEmpty() }
    ?: placeholderBaseUrl.also {
        logger.warn("SmartUse: no backend URL configured (smartuse.baseUrl / SMARTUSE_BASE_URL); using $it")
    }

// The study contact shown to participants (Welcome, Terms, end screens) is
// personal data, so it isn't in source either: local.properties, else an
// environment variable, else a visible placeholder.
fun configString(key: String, env: String, placeholder: String): String =
    (localString(key) ?: System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() } ?: placeholder.also {
        logger.warn("SmartUse: $key / $env not configured; using \"$it\"")
    }).replace("\\", "\\\\").replace("\"", "\\\"")
val studyContactPhone = configString("smartuse.contact.phone", "SMARTUSE_CONTACT_PHONE", "(teléfono no configurado)")
val studyContactEmail = configString("smartuse.contact.email", "SMARTUSE_CONTACT_EMAIL", "(correo no configurado)")

android {
    namespace = "com.example.smartuse_frontend"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.smartuse_frontend"
        // Android 13-15 devices only (API 33-35). targetSdk stays at 33 on purpose:
        // 34+ requires a declared foreground-service type, and 35+ adds the
        // dataSync 6h cap and blocks dataSync services from boot receivers.
        // Don't raise it without reviewing the background execution model.
        minSdk = 33
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SMARTUSE_BASE_URL", "\"$smartUseBaseUrl\"")
        buildConfigField("String", "STUDY_CONTACT_PHONE", "\"$studyContactPhone\"")
        buildConfigField("String", "STUDY_CONTACT_EMAIL", "\"$studyContactEmail\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Each test starts from a clean install (Android Test Orchestrator).
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    // Release signing from local.properties (never in source). Missing values
    // leave the release APK unsigned rather than failing the build.
    val releaseStoreFile = localString("smartuse.release.storeFile")
    val releaseStorePassword = localString("smartuse.release.storePassword")
    val releaseKeyAlias = localString("smartuse.release.keyAlias")
    val releaseKeyPassword = localString("smartuse.release.keyPassword")
    val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
        .all { it != null }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Developer-tunable job settings from local.properties (0 = the
            // JobLimits default).
            buildConfigField("int", "JOB_CAPTURE_INTERVAL_MINUTES", localInt("smartuse.captureIntervalMinutes").toString())
            buildConfigField("int", "JOB_SYNC_INTERVAL_MINUTES", localInt("smartuse.syncIntervalMinutes").toString())
            buildConfigField("int", "JOB_SYNC_BATCH_SIZE", localInt("smartuse.syncBatchSize").toString())
            buildConfigField("int", "JOB_STUCK_AFTER_FAILED_RUNS", localInt("smartuse.stuckAfterFailedRuns").toString())
        }
        release {
            // Release ignores the job tuning keys: test values can never ship.
            buildConfigField("int", "JOB_CAPTURE_INTERVAL_MINUTES", "0")
            buildConfigField("int", "JOB_SYNC_INTERVAL_MINUTES", "0")
            buildConfigField("int", "JOB_SYNC_BATCH_SIZE", "0")
            buildConfigField("int", "JOB_STUCK_AFTER_FAILED_RUNS", "0")
            optimization {
                enable = true
            }
            // Optional separate release backend; otherwise the same URL as debug.
            localString("smartuse.release.baseUrl")?.let {
                buildConfigField("String", "SMARTUSE_BASE_URL", "\"$it\"")
            }
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestUtil(libs.androidx.test.orchestrator)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}