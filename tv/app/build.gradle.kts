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
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}