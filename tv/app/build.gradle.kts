import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.playbridge.receiver"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.playbridge.receiver"
        minSdk = 26
        targetSdk = 36
        val versionProps = Properties()
        versionProps.load(file("../../version.properties").inputStream())
        
        versionCode = versionProps.getProperty("VERSION_CODE").toInt()
        versionName = versionProps.getProperty("VERSION_NAME")

    }

    signingConfigs {
        create("release") {
            // Check if we are running in CI or locally with env vars
            val keystoreFile = file("../../keystore/release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("PLAYBRIDGE_STORE_PASSWORD") ?: findProperty("PLAYBRIDGE_STORE_PASSWORD")?.toString()
                keyAlias = System.getenv("PLAYBRIDGE_KEY_ALIAS") ?: findProperty("PLAYBRIDGE_KEY_ALIAS")?.toString()
                keyPassword = System.getenv("PLAYBRIDGE_KEY_PASSWORD") ?: findProperty("PLAYBRIDGE_KEY_PASSWORD")?.toString()
            }
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    // Ktor WebSocket Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
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
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)           // HLS streaming (.m3u8)
    implementation(libs.media3.exoplayer.dash)          // DASH streaming (.mpd)
    implementation(libs.media3.exoplayer.smoothstreaming) // SmoothStreaming
    implementation(libs.media3.exoplayer.rtsp)          // RTSP streaming
    implementation(libs.media3.datasource.okhttp)       // Better HTTP performance
    implementation(libs.okhttp)                         // OkHttp client
    implementation(libs.okhttp.urlconnection)           // Cookie support for OkHttp
    implementation(libs.media3.ui)                      // PlayerView UI
    implementation(libs.media3.common)                  // Common utilities
    implementation(libs.media3.session)                 // Media session support
    
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Utils
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}