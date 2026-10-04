dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2/")
    }
    versionCatalogs {
        create("libs") { from(files("../../../gradle/libs.versions.toml")) }
    }
}
