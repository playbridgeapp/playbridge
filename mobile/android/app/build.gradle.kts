import java.util.Properties
import java.io.FileInputStream
import java.net.URL
import java.util.zip.ZipInputStream
import java.io.File


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

    lint {
        // Existing issues are recorded in lint-baseline.xml; CI fails only on NEW ones.
        baseline = file("lint-baseline.xml")
    }

    defaultConfig {
        applicationId = "com.playbridge.sender"
        minSdk = 26
        targetSdk = 36
        versionCode = 205
        versionName = "0.2.5"

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }

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
            isUniversalApk = true
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
    implementation(libs.androidx.media3.effect) // HDR→SDR tone mapping via the playback effects pipeline

    // Koin Dependency Injection
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    implementation(project(":shared"))
}

// =========================================================================
// Automated uBlock Origin Extension Management (Method 1 Automation)
// =========================================================================

tasks.register("ensureUblock") {
    group = "playbridge"
    description = "Ensures uBlock Origin is downloaded, extracted, and patched in assets"
    
    val outputDir = file("src/main/assets/extensions/ublock_origin")
    val manifestFile = file("src/main/assets/extensions/ublock_origin/manifest.json")
    
    onlyIf { !manifestFile.exists() }
    
    doLast {
        println("uBlock Origin not found in assets. Downloading and patching...")
        outputDir.mkdirs()
        
        var version = "1.57.2" // Fallback version if GitHub API request fails
        var downloadUrl = ""
        try {
            val apiConnection = URL("https://api.github.com/repos/gorhill/uBlock/releases/latest").openConnection()
            apiConnection.setRequestProperty("User-Agent", "PlayBridge-Gradle-Updater")
            val jsonText = apiConnection.getInputStream().bufferedReader().use { it.readText() }
            val match = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(jsonText)
            if (match != null) {
                version = match.groupValues[1]
                println("Latest uBlock version found: $version")
            }
            
            // Extract the Firefox extension xpi asset download URL dynamically
            val urlMatch = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+?\\.xpi)\"").find(jsonText)
            if (urlMatch != null) {
                downloadUrl = urlMatch.groupValues[1]
                println("Resolved uBlock download URL dynamically: $downloadUrl")
            }
        } catch (e: Exception) {
            println("Failed to fetch latest uBlock version from GitHub API: ${e.message}")
        }
        
        if (downloadUrl.isEmpty()) {
            downloadUrl = "https://github.com/gorhill/uBlock/releases/download/$version/uBlock0_$version.firefox.xpi"
            println("Using fallback download URL format: $downloadUrl")
        }
        
        println("Downloading from $downloadUrl...")
        try {
            val connection = URL(downloadUrl).openConnection()
            ZipInputStream(connection.getInputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(outputDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile.mkdirs()
                        outFile.outputStream().use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            println("uBlock Origin extracted successfully.")
        } catch (e: Exception) {
            throw GradleException("Failed to download or extract uBlock: ${e.message}", e)
        }
        
        if (manifestFile.exists()) {
            println("Patching manifest.json for GeckoView compatibility...")
            var content = manifestFile.readText()
            
            // Remove desktop-only permissions that crash GeckoView
            content = content.replace(Regex("\"menus\",\\s*"), "")
            content = content.replace(Regex("\"privacy\",\\s*"), "")
            
            // Remove default_locale requirement to avoid underscore assets loading crash
            content = content.replace(Regex("\"default_locale\"\\s*:\\s*\"[^\"]*\",?\\s*"), "")
            
            // Replace translation placeholders with generic fallback
            content = content.replace(Regex("\"__MSG_[a-zA-Z0-9_]*__\""), "\"uBlock action\"")
            content = content.replace("__MSG_extShortDesc__", "Finally, an efficient blocker. Easy on CPU and memory.")
            
            manifestFile.writeText(content)
            println("uBlock Origin patch applied successfully.")
        } else {
            throw GradleException("Downloaded uBlock successfully but manifest.json was not found in target folder.")
        }
    }
}

tasks.register("updateUblock") {
    group = "playbridge"
    description = "Forces download and patch of the latest uBlock Origin"
    doFirst {
        val outputDir = file("src/main/assets/extensions/ublock_origin")
        if (outputDir.exists()) {
            println("Cleaning old uBlock installation...")
            outputDir.deleteRecursively()
        }
    }
    finalizedBy("ensureUblock")
}

// Hook uBlock installation into the build lifecycle so it runs before compilation
tasks.named("preBuild") {
    dependsOn("ensureUblock")
}
