plugins {
    id("com.android.library")
}

android {
    namespace = "com.playbridge.libs.mpv"
    compileSdk = 35
}

dependencies {
    api(fileTree(mapOf("dir" to ".", "include" to listOf("*.aar"))))
}
