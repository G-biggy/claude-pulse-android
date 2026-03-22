import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.ghayyath.claudepulse"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ghayyath.claudepulse"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "PULSE_API_URL", "\"${localProperties.getProperty("pulse.api.url", "https://bridge.ghayyath.com/api/usage")}\"")
        buildConfigField("String", "PULSE_API_TOKEN", "\"${localProperties.getProperty("pulse.api.token", "test-token-placeholder")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
