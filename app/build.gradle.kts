import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Single source of truth for the version; release.sh bumps it.
val appVersion: String = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}.getProperty("VERSION_NAME")
val appVersionCode: Int = appVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

android {
    namespace = "com.example.dashcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.dashcam"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        // Where the in-app updater looks for new releases.
        buildConfigField("String", "UPDATE_REPO", "\"Unpiloted0852/DashCam\"")
    }

    buildFeatures { buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }

    // Release key lives outside the repo (see ~/.gradle/gradle.properties). Every release must be
    // signed with the same key or Android refuses to install it as an update.
    signingConfigs {
        create("release") {
            val storeFilePath = providers.gradleProperty("DASHCAM_STORE_FILE").orNull
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("DASHCAM_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("DASHCAM_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("DASHCAM_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (providers.gradleProperty("DASHCAM_STORE_FILE").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    testImplementation("junit:junit:4.13.2")
}
