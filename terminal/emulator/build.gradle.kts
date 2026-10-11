// Terminal emulation, from Termux. See ../README.md for where this code comes
// from and how it differs from the original.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.emulator"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // The upstream tests touch a few android.* constants and rely on the
        // stubbed framework returning defaults.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
