plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.vrsbsplayer"
    compileSdk = 35

    defaultConfig {
        // Deliberately different from the namespace above: applicationId is what Android
        // uses to tell installed apps apart, so changing only this - and not the Kotlin
        // package/namespace - lets this build install side-by-side with the original
        // com.example.vrsbsplayer app instead of overwriting it. Bump this again if you
        // ever want a third parallel copy.
        applicationId = "com.example.vrsbsplayer2"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1-cinema"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Media3 / ExoPlayer - hardware-accelerated playback, SAF-friendly
    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-common:1.4.0")
    implementation("androidx.media3:media3-datasource:1.4.0")
    // Progressive MP4/MKV/WebM remuxes are already handled by media3-exoplayer's bundled
    // extractors. These three add the adaptive-streaming source types that external apps
    // (e.g. Stremio's transcoded/quality-switchable links) may hand us instead of a plain
    // file URL. DefaultMediaSourceFactory picks the right one at runtime by URI/MIME type,
    // but only if the corresponding class is actually on the classpath.
    implementation("androidx.media3:media3-exoplayer-hls:1.4.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.0")

    // CameraX + ML Kit barcode scanning, used only for Cardboard QR import
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
