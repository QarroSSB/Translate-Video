plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qarro.livetranslator"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qarro.livetranslator"
        minSdk = 29
        targetSdk = 35
        versionCode = 13
        versionName = "0.5.4"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    testImplementation("junit:junit:4.13.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
