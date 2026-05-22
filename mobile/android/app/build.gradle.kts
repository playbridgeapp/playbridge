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
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.playbridge.sender"
        minSdk = 26
        targetSdk = 36
        versionCode = 51
        versionName = "0.1.51"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../../keystore/release.jks")
            storePassword = System.getenv("PLAYBRIDGE_STORE_PASSWORD") ?: findProperty("PLAYBRIDGE_STORE_PASSWORD")?.toString()
            keyAlias = System.getenv("PLAYBRIDGE_KEY_ALIAS") ?: findProperty("PLAYBRIDGE_KEY_ALIAS")?.toString()
            keyPassword = System.getenv("PLAYBRIDGE_KEY_PASSWORD") ?: findProperty("PLAYBRIDGE_KEY_PASSWORD")?.toString()
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
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
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
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.ui)

    implementation(project(":shared"))
}
