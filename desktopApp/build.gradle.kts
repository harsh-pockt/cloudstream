import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm {}
    sourceSets {
        jvmMain.dependencies {
            implementation(libs.bundles.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.collections.immutable)
            implementation(compose.desktop.currentOs) {
                // compose.desktop.currentOs imports the wrong material 2, so we exclude it
                exclude(group = "org.jetbrains.compose.material", module = "material")
            }
            implementation(project(":shared"))
            implementation(project(":library"))
        }
    }
}

// java.lang.System::load has been called by org.jetbrains.skiko.LibraryLoader in an unnamed module
tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

compose.desktop {
    application {
        mainClass = "com.lagradost.cloudstream4.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "CloudStream"
            // MSI requires MAJOR.MINOR.BUILD with numbers only, which versionName already is
            packageVersion = libs.versions.versionName.get()

            // The packaged runtime only contains these JDK modules, regenerate with ./gradlew :desktopApp:suggestRuntimeModules
            modules("java.instrument", "java.management", "java.net.http", "java.sql", "jdk.dynalink", "jdk.unsupported")

            val iconsRoot = project.file("src/desktop-icons")
            macOS {
                // iconFile.set(iconsRoot.resolve("icon-mac.icns"))
            }
            windows {
                iconFile.set(iconsRoot.resolve("icon-windows.ico"))
                menu = true
                menuGroup = "CloudStream"
                shortcut = true
                dirChooser = true
                // Must never change, otherwise new versions install side by side instead of upgrading
                // see https://wixtoolset.org/documentation/manual/v3/howtos/general/generate_guids.html
                upgradeUuid = "33b76f91-08d2-41b1-9ed1-ddde419f24be"
            }
            linux {
                iconFile.set(iconsRoot.resolve("icon-linux.png"))
            }
        }

        //buildTypes.release.proguard {
        //    configurationFiles.from(project.file("rules.pro"))
        //}
    }
}