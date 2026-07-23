plugins {
    id("com.android.application")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.beyondlevi.nexus.lume"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.beyondlevi.nexus.lume"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Rokid Nexus bus-client SDK (published via JitPack). `shared` resolves transitively.
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.1.1")
    // Library index persistence.
    implementation("com.google.code.gson:gson:2.11.0")
    // On-device PDF text extraction (no network; nothing leaves the phone).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
}
