// The Android view that draws a terminal and takes its input, from Termux.
// See ../README.md for where this code comes from.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":terminal:emulator"))
    implementation(libs.androidx.annotation)
}
