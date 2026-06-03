import java.net.URL
import java.io.File
import java.util.zip.ZipInputStream

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
        versionCode = 201
        versionName = "0.2.1"
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
    implementation(project(":shared"))

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
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

