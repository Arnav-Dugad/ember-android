plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

// Drives the real app through the hot journeys (cold start, swiping the deck, opening a chat)
// and records the classes ART should compile ahead of time. Run on a device or emulator with:
//   ./gradlew :app:generateBaselineProfile
android {
    namespace = "io.github.arnavdugad.ember.baseline"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.junit4)
}
