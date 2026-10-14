plugins {
    id("com.android.application")
}

android {
    namespace = "za.co.madbot.stickermaker"
    compileSdk = 36

    defaultConfig {
        applicationId = "za.co.madbot.stickermaker"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        val authority = "$applicationId.stickercontentprovider"
        manifestPlaceholders["contentProviderAuthority"] = authority
        buildConfigField("String", "CONTENT_PROVIDER_AUTHORITY", "\"$authority\"")
        ndk {
            abiFilters += listOf("arm64-v8a")
            // -PwithX86 adds the emulator ABI for testing on a PC
            if (project.hasProperty("withX86")) abiFilters += "x86_64"
        }
    }

    buildFeatures { buildConfig = true }

    // Stickers are served by file descriptor; keep webp uncompressed in the APK.
    androidResources { noCompress += "webp" }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.github.bumptech.glide:gifdecoder:4.16.0")
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
}
