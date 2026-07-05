import java.util.Properties
import java.io.FileInputStream


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.playbridge.sender"
    compileSdk {
        version = release(37)
    }

    lint {
        // Existing issues are recorded in lint-baseline.xml; CI fails only on NEW ones.
        baseline = file("lint-baseline.xml")
        disable += "UnsafeOptInUsageError"
    }

    defaultConfig {
        applicationId = "com.playbridge.sender"
        minSdk = 26
        targetSdk = 36
        versionCode = 217
        versionName = "0.9.0"

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../../keystore/release.jks").absoluteFile
            val storePw = System.getenv("PLAYBRIDGE_STORE_PASSWORD") ?: findProperty("PLAYBRIDGE_STORE_PASSWORD")?.toString()
            val keyPw = System.getenv("PLAYBRIDGE_KEY_PASSWORD") ?: findProperty("PLAYBRIDGE_KEY_PASSWORD")?.toString()
            storePassword = storePw
            keyAlias = System.getenv("PLAYBRIDGE_KEY_ALIAS") ?: findProperty("PLAYBRIDGE_KEY_ALIAS")?.toString()
            keyPassword = if (keyPw.isNullOrBlank()) storePw else keyPw
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            pickFirsts.add("lib/**/libc++_shared.so")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    // Compose BOM still aligns non-overridden androidx/compose artifacts. The
    // core UI artifacts below are pinned to the alpha `compose` version so they
    // pair with the alpha material3 that carries Material 3 Expressive. Explicit
    // versions win over the BOM's constraints.
    implementation(platform(libs.androidx.compose.bom))
    val composeVersion = libs.versions.compose.get()
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.ui:ui-graphics:$composeVersion")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeVersion")
    implementation("androidx.compose.ui:ui-text-google-fonts:$composeVersion")
    implementation("androidx.compose.foundation:foundation:$composeVersion")
    implementation("androidx.compose.animation:animation:$composeVersion")
    // material3 alpha (Expressive theme + MotionScheme) via the catalog alias.
    implementation(libs.androidx.compose.material3)
    // Seeds a full ColorScheme from poster/backdrop art (dynamic theming).
    implementation(libs.material.kolor)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation(libs.coil.compose)


    // OkHttp WebSocket
    implementation(libs.okhttp)

    // DataStore
    implementation(libs.androidx.datastore)

    // Multiplatform Settings

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager — download engine (Phase 1)
    implementation(libs.androidx.work.runtime.ktx)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Mozilla Android Components
    implementation(libs.moz.concept.engine)
    implementation(libs.moz.concept.fetch)
    implementation(libs.moz.browser.engine.gecko)
    implementation(libs.moz.browser.state)
    implementation(libs.moz.browser.tabstray)
    implementation(libs.moz.browser.toolbar)
    implementation(libs.moz.feature.tabs)
    implementation(libs.moz.feature.session)
    implementation(libs.moz.feature.toolbar)
    implementation(libs.moz.feature.addons)
    implementation(libs.moz.feature.prompts)
    implementation(libs.moz.support.webextensions)
    implementation(libs.moz.support.ktx)
    implementation(libs.moz.lib.fetch.okhttp)
    implementation(libs.moz.browser.menu)
    implementation(libs.moz.ui.widgets)
    implementation(libs.moz.ui.icons)

    // GeckoView
    implementation(libs.geckoview.omni)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:${libs.versions.compose.get()}")
    // Phase-0 transmux spike only. The production HLS merge uses the platform MediaMuxer
    // (PlatformMuxer), not Transformer, so this stays test-scoped and out of the APK.
    androidTestImplementation(libs.androidx.media3.transformer)
    debugImplementation("androidx.compose.ui:ui-tooling:${libs.versions.compose.get()}")
    debugImplementation("androidx.compose.ui:ui-test-manifest:${libs.versions.compose.get()}")
    // Media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.effect) // HDR→SDR tone mapping via the playback effects pipeline

    // Koin Dependency Injection
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // QuickJS — sandboxed JS runtime for Nuvio scraper plugins
    implementation(libs.quickjs.kt)

    implementation(project(":shared"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
        // Material 3 Expressive theme + motion APIs are experimental (alpha channel).
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}
