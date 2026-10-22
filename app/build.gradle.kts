plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rantube.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rantube.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"
        ndk {
            abiFilters += if (providers.gradleProperty("phoneOnly").orNull == "true") {
                listOf("arm64-v8a")
            } else {
                listOf("arm64-v8a", "x86_64")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    // GPL-3.0: yt-dlp runtime for page extraction, format selection and downloads.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}

