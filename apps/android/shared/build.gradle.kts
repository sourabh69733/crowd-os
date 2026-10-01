// Code shared across platforms. Only the Android target is enabled for now; an iOS target can be added here later.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidLibrary {
        namespace = "org.freegram.shared"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
            implementation("org.jetbrains.compose.ui:ui:1.12.1")
            implementation("org.jetbrains.compose.ui:ui-backhandler:1.12.1")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("io.github.alexzhirkevich:qrose:1.3.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
