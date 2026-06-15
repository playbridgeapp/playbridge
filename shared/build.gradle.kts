import java.net.URL
import java.util.zip.ZipInputStream
import java.io.File

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

            // MPV (compile-only; the TV player app bundles the runtime .aar)
            compileOnly(files("../tv/android/player/app/libs/mpv-android.aar"))
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

// =========================================================================
// Automated uBlock Origin Extension Management (Shared)
// =========================================================================

tasks.register("ensureUblock") {
    group = "playbridge"
    description = "Ensures uBlock Origin is downloaded, extracted, and patched in shared assets"
    
    val outputDir = file("src/androidMain/assets/extensions/ublock_origin")
    val manifestFile = file("src/androidMain/assets/extensions/ublock_origin/manifest.json")
    
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
        val outputDir = file("src/androidMain/assets/extensions/ublock_origin")
        if (outputDir.exists()) {
            println("Cleaning old uBlock installation...")
            outputDir.deleteRecursively()
        }
    }
    finalizedBy("ensureUblock")
}

// Hook uBlock installation into the build lifecycle so it runs before compilation
tasks.matching { it.name in listOf("preBuild", "androidPreBuild", "preAndroidMainBuild") }.configureEach {
    dependsOn("ensureUblock")
}
