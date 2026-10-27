// Plugins are declared once here with `apply false` so every module resolves the same version.
// AGP 9 compiles Kotlin itself: `org.jetbrains.kotlin.android` must not be applied anywhere.
// Declaring kotlin-jvm pins the Kotlin Gradle Plugin that AGP's built-in Kotlin uses.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.baselineprofile) apply false
}
