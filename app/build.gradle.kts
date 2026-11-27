import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: keystore.properties (git-ignored) sits in the project root next to release.keystore.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Licence: until the real Google and Play keys exist every build uses fake sign-in, billing and server answers (anyone could unlock for free).
// Build with -PrealLicence once local.properties has google.webClientId. The Play bundle refuses to build with the fakes on.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val fakeLicence = !project.hasProperty("realLicence")
// Ads: Google's public test ids until the real AdMob ids are in local.properties (admob.appId, admob.bannerUnitId) and the build uses -PrealAds.
val realAds = project.hasProperty("realAds")
val admobAppId = if (realAds) localProps.getProperty("admob.appId", "") else "ca-app-pub-3940256099942544~3347511713"
val bannerUnit = if (realAds) localProps.getProperty("admob.bannerUnitId", "") else "ca-app-pub-3940256099942544/9214589741"

android {
    namespace = "za.co.madbot.stickermaker"
    compileSdk = 36

    defaultConfig {
        applicationId = "za.co.madbot.stickermaker"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        val authority = "$applicationId.stickercontentprovider"
        manifestPlaceholders["contentProviderAuthority"] = authority
        buildConfigField("String", "CONTENT_PROVIDER_AUTHORITY", "\"$authority\"")
        buildConfigField("boolean", "FAKE_LICENCE", fakeLicence.toString())
        buildConfigField("String", "BANNER_AD_UNIT", "\"$bannerUnit\"")
        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "LICENCE_BASE_URL", "\"https://crm.madbot.co.za/api/licence/v1\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${localProps.getProperty("google.webClientId", "")}\"")
        ndk {
            abiFilters += listOf("arm64-v8a")
            // -PwithX86 adds the emulator ABI for testing on a PC
            if (project.hasProperty("withX86")) abiFilters += "x86_64"
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile").removePrefix("../"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No code shrinking: ML Kit and the JNI bridge are not worth the risk for a personal build.
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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

tasks.matching { it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (fakeLicence) throw GradleException("bundleRelease needs -PrealLicence: this build still has the fake licence, which unlocks for free.")
        if (!realAds || admobAppId.isBlank() || bannerUnit.isBlank()) throw GradleException("bundleRelease needs -PrealAds with admob.appId and admob.bannerUnitId in local.properties: this build would show test ads.")
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
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    implementation("com.google.android.gms:play-services-ads:24.4.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
