import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Image loading — Coil3 with OkHttp on desktop
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Force modern Skiko runtime to match the Compose version and prevent
    // UnsatisfiedLinkError. Windows x64 + ARM64 are the primary targets here;
    // the macOS/Linux runtimes are kept so the project still compiles + runs on
    // a non-Windows build host (e.g. CI dry-runs, local dev on macOS).
    val skikoVersion = "0.9.4.2"
    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:$skikoVersion")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-arm64:$skikoVersion")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:$skikoVersion")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-macos-x64:$skikoVersion")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:$skikoVersion")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-arm64:$skikoVersion")
}

compose.desktop {
    application {
        mainClass = "com.nyora.windows.MainKt"

        nativeDistributions {
            val osName = System.getProperty("os.name").lowercase()
            if (osName.contains("linux")) {
                targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                targetFormats(TargetFormat.Dmg, TargetFormat.Pkg)
            } else if (osName.contains("win")) {
                targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            }

            packageName        = "nyora"
            packageVersion     = "1.0.0"
            description        = "Nyora — AI-powered manga reader"
            vendor             = "Nyora"
            copyright          = "© 2025 Nyora contributors"

            // Primary Windows packaging (MSI installer + portable EXE). The MSI
            // upgradeUuid MUST stay stable across releases so upgrades replace the
            // previous install instead of stacking side-by-side.
            windows {
                packageName    = "Nyora"
                menuGroup      = "Nyora"
                upgradeUuid    = "7E3B6C2A-1F44-4D58-9B0E-2C9A4F6D1E70"
                menu           = true
                perUserInstall = true
                dirChooser     = true
                console        = false
                // iconFile.set(project.file("src/main/resources/nyora.ico"))
            }

            linux {
                packageName    = "nyora"
                debMaintainer  = "nyora-dev@nyora.app"
                menuGroup      = "Graphics"
                appCategory    = "Utility"
                // iconFile.set(project.file("src/main/resources/nyora.png"))
            }

            // Bundle a JRE so users don't need one pre-installed.
            // Requires JDK 17+ with jpackage on the build host.
            jvmArgs(
                // Allow GraalJS polyglot — needed when the sidecar runs in-process
                "--add-opens", "java.base/jdk.internal.module=ALL-UNNAMED",
                "-Xmx512m",
            )
        }

        buildTypes.release.proguard {
            isEnabled = false  // GraalJS reflection breaks with ProGuard
        }
    }
}

configurations.all {
    resolutionStrategy {
        eachDependency {
            if (requested.group.startsWith("org.jetbrains.compose")) {
                useVersion("1.7.3")
            }
        }
    }
}