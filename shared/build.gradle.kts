plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        // Must be unique
        namespace = "com.lagradost.cloudstream4"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources {
            enable = true
        }
    }

    jvm()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        all {
            languageSettings {
                optIn("com.lagradost.cloudstream3.InternalAPI")
                optIn("com.lagradost.cloudstream3.Prerelease")
            }
        }

        commonMain.dependencies {
            implementation(libs.coil.network.ktor3)
            implementation(libs.bundles.compose)
            implementation(libs.kotlinx.collections.immutable)
            implementation(project(":library"))
        }

        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.preference.ktx)
            implementation(libs.ktor.client.android)
        }

        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        jvmMain.dependencies {
            api(project(":androidCompat"))
            implementation(libs.jackson.module.kotlin)
            implementation(libs.ktor.client.java)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okhttp)
            implementation(libs.okhttp.dnsoverhttps)
            // For the extensions' client, app.baseClient
            implementation(libs.nicehttp)
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.lagradost.cloudstream4.generated.resources"
    generateResClass = auto
}
// The Android extensions test converts and loads hundreds of extensions in one run
tasks.withType<Test>().configureEach {
    maxHeapSize = "3g"
    // Tests keep their files, such as the HTTP cache, away from the real app's folders
    systemProperty("cloudstream.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    // Tests that start the installed Edge or Chrome, off by default: gradlew :shared:jvmTest -PbrowserTests
    systemProperty("cloudstream.browserTests", providers.gradleProperty("browserTests").isPresent.toString())
    systemProperty("cloudstream.cloudflareTest", providers.gradleProperty("cloudflareTest").isPresent.toString())
    systemProperty("cloudstream.cloudflareUrl", providers.gradleProperty("cloudflareUrl").getOrElse(""))
}
