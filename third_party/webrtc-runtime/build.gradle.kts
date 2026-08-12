plugins {
    alias(libs.plugins.android.library)
}

/**
 * PlayBridge's pinned Android libwebrtc runtime.
 *
 * The source and ABI-specific native libraries are deliberately vendored here from
 * the pinned ScreenStream M150 snapshot. Keeping this wrapper in PlayBridge makes
 * both Android Gradle roots consume the same audited revision without depending on a
 * mutable Maven artifact. See THIRD_PARTY_NOTICES.md for provenance and hashes.
 */
android {
    namespace = "com.playbridge.webrtc.runtime"
    compileSdk { version = release(37) }

    defaultConfig { minSdk = 26 }

    lint {
        // Keep the pinned upstream snapshot unchanged. Existing upstream findings are
        // recorded by exact file and line so lint still fails on newly introduced issues.
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.9.1")
}
