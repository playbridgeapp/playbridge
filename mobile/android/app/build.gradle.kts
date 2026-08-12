import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

@CacheableTask
abstract class StripGeckoViewWebRtcClasses : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectories: ListProperty<Directory>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun transform() {
        val writtenEntries = HashSet<String>()
        val output = outputJar.get().asFile
        output.parentFile.mkdirs()

        ZipOutputStream(output.outputStream().buffered()).use { jarOutput ->
            inputDirectories.get().forEach { directory ->
                val root = directory.asFile
                root.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val entryName = file.relativeTo(root).invariantSeparatorsPath
                        writeEntry(entryName, writtenEntries, jarOutput) {
                            file.inputStream().buffered().use { it.copyTo(jarOutput) }
                        }
                    }
            }

            inputJars.get().forEach { regularFile ->
                val jar = regularFile.asFile
                val isGeckoView = jar.name.contains("geckoview", ignoreCase = true)
                ZipInputStream(jar.inputStream().buffered()).use { jarInput ->
                    while (true) {
                        val entry = jarInput.nextEntry ?: break
                        if (entry.isDirectory ||
                            (isGeckoView && entry.name.startsWith("org/webrtc/"))
                        ) {
                            continue
                        }
                        writeEntry(entry.name, writtenEntries, jarOutput) {
                            jarInput.copyTo(jarOutput)
                        }
                    }
                }
            }
        }
    }

    private fun writeEntry(
        name: String,
        writtenEntries: MutableSet<String>,
        output: ZipOutputStream,
        writeContents: () -> Unit,
    ) {
        if (!writtenEntries.add(name)) {
            check(!name.endsWith(".class") || name.startsWith("META-INF/")) {
                "Duplicate class remained after filtering GeckoView: $name"
            }
            return
        }
        output.putNextEntry(ZipEntry(name).apply { time = 0L })
        writeContents()
        output.closeEntry()
    }
}

val googleCastApplicationId = providers.gradleProperty("PLAYBRIDGE_GOOGLE_CAST_APP_ID")
    .orElse(providers.environmentVariable("PLAYBRIDGE_GOOGLE_CAST_APP_ID"))
    .orElse("30FDC6BC")
    .map { applicationId ->
        require(applicationId.matches(Regex("[A-Za-z0-9_-]+"))) {
            "PLAYBRIDGE_GOOGLE_CAST_APP_ID contains unsupported characters"
        }
        applicationId
    }

val buildingAppBundle = gradle.startParameter.taskNames.any { taskName ->
    taskName.substringAfterLast(':').contains("bundle", ignoreCase = true)
}

android {
    namespace = "com.playbridge.sender"
    buildFeatures {
        buildConfig = true
    }
    compileSdk {
        version = release(37)
    }

    lint {
        // Existing issues are recorded in lint-baseline.xml; CI fails only on NEW ones.
        baseline = file("lint-baseline.xml")
        disable += "UnsafeOptInUsageError"
        // New check introduced by the Compose/lint upgrade; flags pre-existing
        // Locale.getDefault() date formatting in composables. Not a crash/security
        // issue (locale rarely changes at runtime), so suppress rather than churn.
        disable += "NonObservableLocale"
    }

    defaultConfig {
        applicationId = "com.playbridge.sender"
        minSdk = 26
        targetSdk = 36
        versionCode = 223
        versionName = "0.13.0"
        buildConfigField(
            "String",
            "GOOGLE_CAST_APPLICATION_ID",
            "\"${googleCastApplicationId.get()}\"",
        )

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
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // Distribution flavors: `foss` (GitHub/sideload; includes APK self-update) vs
    // `play` (Google Play; self-update code and REQUEST_INSTALL_PACKAGES stripped -
    // Play policy prohibits apps updating themselves outside the Store).
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            isDefault = true
        }
        create("play") {
            dimension = "distribution"
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
            // AGP cannot combine optimized resource shrinking, APK splits, and bundles.
            // Keep split APKs for FOSS assembly; Play bundles perform their own ABI splits.
            isEnable = !buildingAppBundle
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val taskName = "strip${variant.name.replaceFirstChar { it.uppercase() }}GeckoViewWebRtc"
        val stripTask = tasks.register<StripGeckoViewWebRtcClasses>(taskName)
        variant.artifacts
            .forScope(ScopedArtifacts.Scope.ALL)
            .use(stripTask)
            .toTransform(
                ScopedArtifact.CLASSES,
                StripGeckoViewWebRtcClasses::inputJars,
                StripGeckoViewWebRtcClasses::inputDirectories,
                StripGeckoViewWebRtcClasses::outputJar,
            )
    }
}

dependencies {
    implementation(project(":webrtc-runtime"))
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

    // GeckoView also bundles upstream org.webrtc classes. The release scoped-artifact
    // transform keeps PlayBridge's patched M150 runtime as the single packaged copy.
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
    // Shared Media3 FFmpeg audio decoder (repo prebuilt/media3/ — also used by TV).
    // PhoneExoPlayerFactory: EXTENSION_RENDERER_MODE_PREFER → FfmpegAudioRenderer.
    implementation(
        files("${rootProject.projectDir}/../../prebuilt/media3/lib-decoder-ffmpeg-release.aar"),
    )

    // Koin Dependency Injection
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // QuickJS — sandboxed JS runtime for Nuvio scraper plugins
    implementation(libs.quickjs.kt)

    implementation(libs.jsoup)

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
