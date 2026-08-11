import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlinCocoapods)
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

    cocoapods {
        summary = "Shared Kotlin module for MusicManager iOS app"
        homepage = "https://github.com/WilmanTech/mm-mobile"
        ios.deploymentTarget = "17.0"
        framework {
            // Module name exposed to Swift as `import MusicManagerShared`.
            baseName = "MusicManagerShared"
            isStatic = false
        }
        // The :shared:podInstall task wires this Gradle module into the
        // iOS Xcode project via CocoaPods. Run with:
        //   ./gradlew :shared:podInstall
        // from the repo root after the first build to generate the podspec
        // and execute `pod install` inside ios/.
    }

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
            // kotest-assertions and kotest-property are multiplatform
            // (KMP-published artifacts), so they live in commonTest
            // and any commonTest file (e.g. ConnectivityTest.kt) can
            // use shouldBe / forAll / etc.
            implementation(libs.kotest.assertions)
            implementation(libs.kotest.property)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
        }

        // The JDBC sqlite-driver declares org.jetbrains.kotlin.platform.type=jvm,
        // which would cause "platform.type 'jvm' vs 'native' mismatch" on iOS
        // test variants if added to commonTest. We restrict it to jvmTest,
        // where SyncCoordinatorTest.kt lives — that's the only place that
        // exercises the SQLDelight JDBC driver against an in-memory DB.
        //
        // kotest-runner-junit5 is also JVM-only (it integrates with the
        // JUnit 5 platform; no native equivalent). The iOS native test
        // targets cannot resolve it, so it lives here too. The runner
        // is only needed by tests that extend FunSpec / StringSpec /
        // etc. and let Kotest discover them via JUnit 5; commonTest
        // doesn't need it (Kotlin's kotlin(\"test\") handles the
        // @Test discovery in KMP common tests).
        jvmTest.dependencies {
            implementation(libs.sqlite.jdbc)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.kotest.runner)
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
