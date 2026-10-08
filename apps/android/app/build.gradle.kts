plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing key lives outside the repo. Its password comes from FREEGRAM_KEYSTORE_PASSWORD or,
// on the maintainer's Mac, the Keychain item "freegram-release-keystore". Without either, release builds are unsigned.
val releaseKeystore = file(System.getProperty("user.home") + "/.freegram/freegram-release.jks")
val releaseKeystorePassword: String? by lazy {
    System.getenv("FREEGRAM_KEYSTORE_PASSWORD") ?: runCatching {
        providers.exec { commandLine("security", "find-generic-password", "-s", "freegram-release-keystore", "-w") }
            .standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}

android {
    namespace = "org.freegram.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.freegram.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.1"
    }

    signingConfigs {
        if (releaseKeystore.exists()) create("release") {
            storeFile = releaseKeystore
            storePassword = releaseKeystorePassword
            keyAlias = "freegram"
            keyPassword = releaseKeystorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // Quartz ships "-keep class com.vitorpamplona.quartz.** { *; }", which keeps all ~6,000 of its classes
            // plus Jackson and Kotlin reflection. Freegram calls only NIP-44 and NIP-49 directly, so let R8 trace them.
            optimization { keepRules { ignoreFrom("com.vitorpamplona.quartz:quartz-android") } }
        }
    }

    packaging {
        // Quartz brings a bundled SQLite and a chess-openings file that Freegram never uses (Room uses Android's SQLite).
        jniLibs.excludes += "**/libsqliteJni.so"
        resources.excludes += "eco.pgn"
    }

    // 32-bit Intel builds only matter for old emulators; real phones are ARM, and newer emulators are x86_64.
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    testOptions { unitTests.isIncludeAndroidResources = true }

    sourceSets.getByName("test").resources.directories.add("../../../protocol/vectors")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui:1.9.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("fr.acinq.secp256k1:secp256k1-kmp:0.24.0")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.24.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.vitorpamplona.quartz:quartz-android:1.16.0")
    ksp("androidx.room:room-compiler:2.8.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
    testImplementation("androidx.room:room-testing:2.8.5")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
}
