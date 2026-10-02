/**
 * Lets desktop run extensions that were only built for Android.
 *
 * It converts an extension's .cs3 (Android dex) into a jar, and provides desktop versions of the
 * Android and CloudStream app classes extensions use. Android classes it has no desktop version of
 * are generated as empty stubs when an extension loads, see AndroidPluginClassLoader.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        all {
            languageSettings {
                optIn("com.lagradost.cloudstream3.InternalAPI")
                optIn("com.lagradost.cloudstream3.Prerelease")
            }
        }

        jvmMain.dependencies {
            api(project(":library"))
            // Android has Gson built in through its libraries, extensions use it without bundling it
            api(libs.gson)
            implementation(libs.jackson.module.kotlin)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.nicehttp)
            implementation(libs.okhttp)
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    // dex2jar's d2j-external has its own copy of the ASM core classes, the one its command line
    // tool runs with, so leave out the separate ASM core to have one copy only
    "jvmMainImplementation"(libs.asm.tree) { exclude(group = "org.ow2.asm", module = "asm") }
    "jvmMainImplementation"(libs.dex2jar) { exclude(group = "org.ow2.asm", module = "asm") }
}
