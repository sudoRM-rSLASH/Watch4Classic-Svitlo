plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Shared code for the watch (:app) and phone (:phone) apps:
// parsers, cache, settings, background refresh, reminders.
android {
    namespace = "com.yehor.lightwatch.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
}
