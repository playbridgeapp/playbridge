plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.playbridge.browser"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.playbridge.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.1.11"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore)
    implementation(libs.geckoview.omni)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
