plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    androidLibrary {
        namespace = "com.playbridge.shared"
        compileSdk = 35
        minSdk = 24
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        // Wire commonTest to a JVM host-test compilation so the shared player
        // logic (PlayerViewModel, queue/playlist) is actually unit-tested.
        // isReturnDefaultValues lets android.util.Log calls no-op under plain JVM tests.
        withHostTestBuilder {
        }.configure {
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir("../protocol/generated/kotlin")
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.okio)
            api(libs.wire.runtime)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.wire.moshi.adapter)

            // Media3 ExoPlayer
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.exoplayer.hls)
            implementation(libs.androidx.media3.exoplayer.dash)
            implementation(libs.androidx.media3.exoplayer.smoothstreaming)
            implementation(libs.androidx.media3.exoplayer.rtsp)
            implementation(libs.androidx.media3.datasource.okhttp)
            implementation(libs.androidx.media3.common)
            implementation(libs.androidx.media3.session)
            implementation(libs.androidx.media3.effect)
            implementation(libs.okhttp)
            implementation(libs.okhttp.urlconnection)

            // MPV
            compileOnly(files("../libs/mpv-android/mpv-android.aar"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.turbine)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.okio.fakefilesystem)
        }
    }
}
