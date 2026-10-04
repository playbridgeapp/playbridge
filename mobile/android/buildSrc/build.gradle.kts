plugins { `java-library` }

val geckoFixture by configurations.creating { isTransitive = false }

dependencies {
    implementation("org.ow2.asm:asm:9.9")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testRuntimeOnly("com.google.android:android:4.1.1.4") // Class signatures only; no Android methods run.
    geckoFixture(libs.geckoview.omni)
}

tasks.test {
    inputs.files(geckoFixture)
    doFirst { systemProperty("gecko.fixture.aar", geckoFixture.singleFile.absolutePath) }
}
