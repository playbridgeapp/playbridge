import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.playbridge.player"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.playbridge.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 47
        versionName = "0.1.47"

    }

    signingConfigs {
        create("release") {
            storeFile = file("../../../keystore/release.jks")
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
        }
        jniLibs {
            // mpv-android and libvlc both ship libc++_shared.so for every ABI; pick one copy.
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation("androidx.compose.material3:material3")
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")

    // Ktor WebSocket Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.json)

    // QR Code Generation
    implementation(libs.zxing.core)

    // DataStore
    implementation(libs.androidx.datastore)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Media3 ExoPlayer - Full Suite
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)           // HLS streaming (.m3u8)
    implementation(libs.androidx.media3.exoplayer.dash)          // DASH streaming (.mpd)
    implementation(libs.androidx.media3.exoplayer.smoothstreaming) // SmoothStreaming
    implementation(libs.androidx.media3.exoplayer.rtsp)          // RTSP streaming
    implementation(libs.androidx.media3.datasource.okhttp)       // Better HTTP performance
    implementation(libs.okhttp)                         // OkHttp client
    implementation(libs.okhttp.urlconnection)           // Cookie support for OkHttp
    implementation(libs.androidx.media3.ui)                      // PlayerView UI
    implementation(libs.androidx.media3.common)                  // Common utilities
    implementation(libs.androidx.media3.session)                 // Media session support
    implementation(libs.androidx.media3.effect)                  // Media effects support

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Utils
    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.okhttp)

    implementation(project(":shared"))

    // LibVLC
    implementation(libs.libvlc.all)

    // MPV
    implementation(files("libs/mpv-android.aar"))

    // ExoPlayer Extensions (Software Decoders)
    implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    implementation(files("libs/lib-decoder-av1-release.aar"))
    implementation(files("libs/lib-decoder-iamf-release.aar"))
    implementation(files("libs/lib-decoder-mpegh-release.aar"))

    // Advanced Metadata
    implementation(files("libs/nextlib-mediainfo-local.aar"))
}
