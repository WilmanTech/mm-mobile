plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.sqldelight) apply false
    // kotlinCocoapods is bundled inside kotlin-gradle-plugin 2.0+ — declared
    // apply false here so the :shared module can pick it up without forcing
    // a fresh version resolution.
    alias(libs.plugins.kotlinCocoapods) apply false
}

subprojects {
    group = "com.wtm.musicmanager"
    version = "0.1.0"
}
