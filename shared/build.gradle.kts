import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "com.wtm.musicmanager.shared"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)

    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    // Add a JVM target so unit tests can run on the host (Kotest doesn't have
    // kotlin-native artifacts). The JVM target is test-only — production
    // binaries are produced for Android + iOS.
    jvm()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // Note: iOS Xcode integration via CocoaPods is configured later (Fase 2+).
    // For Fase 0 we use `linkDebugFrameworkIosX64` to produce the .framework
    // and consume it from a plain Xcode project (manual setup).

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.client.logging)

            implementation(libs.koin.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // kotest-assertions / kotest-runner-junit5 / kotest-property
            // are JVM-only (they ship with org.jetbrains.kotlin.platform.type=jvm).
            // The iOS native test targets cannot resolve them — that's
            // why these three are restricted to jvmTest below.
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
        }

        // The JDBC sqlite-driver declares org.jetbrains.kotlin.platform.type=jvm,
        // which would cause "platform.type 'jvm' vs 'native' mismatch" on iOS
        // test variants if added to commonTest. We restrict it to jvmTest,
        // where SyncCoordinatorTest.kt lives — that's the only place that
        // exercises the SQLDelight JDBC driver against an in-memory DB.
        jvmTest.dependencies {
            implementation(libs.sqlite.jdbc)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.kotest.runner)
            implementation(libs.kotest.assertions)
            implementation(libs.kotest.property)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.androidx.security.crypto)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }

        jvmMain.dependencies {
            // JVM target is test-only; use OkHttp engine for parity with Android.
            implementation(libs.ktor.client.okhttp)
        }
    }
}

sqldelight {
    databases {
        create("MusicManagerDatabase") {
            packageName.set("com.wtm.musicmanager.db")
        }
    }
}
