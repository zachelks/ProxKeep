// Plugins are declared here once so every module resolves the same versions.
// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so Android modules do not
// apply org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
