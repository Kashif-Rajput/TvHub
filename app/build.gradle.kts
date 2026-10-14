import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// YouTube Data API key. Best kept as a GitHub secret named YT_API_KEY (see README).
// It can also go in gradle.properties as YT_API_KEY=..., or be typed in the app's Settings.
val ytApiKey: String = (System.getenv("YT_API_KEY")?.takeIf { it.isNotBlank() }
    ?: (project.findProperty("YT_API_KEY") as String?)
    ?: "").trim().replace("\"", "")

android {
    namespace = "com.tvhub.media"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tvhub.media"
        minSdk = 28          // Android 9 (many local TVs are Android 9 even if they say otherwise)
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"

        buildConfigField("String", "YT_API_KEY", "\"$ytApiKey\"")

        // Budget TVs are ARM. Dropping x86 keeps the APK small.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // Fixed key so every new APK installs over the old one (no uninstall needed).
    signingConfigs {
        create("tvhub") {
            storeFile = file("tvhub.jks")
            storePassword = "tvhub123"
            keyAlias = "tvhub"
            keyPassword = "tvhub123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("tvhub")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("tvhub")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        // Media3 marks some APIs as @UnstableApi; they are opted into in code.
        disable += "UnsafeOptInUsageError"
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val media3 = "1.9.0"

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Video / audio playback (hardware decoders first)
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    // Software audio fallback (AC3, E-AC3, DTS, TrueHD...) when the TV chip lacks them.
    // Version must match media3 above.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")

    // Small, fast image loader for thumbnails and the picture viewer
    implementation("io.coil-kt:coil:2.7.0")
}
