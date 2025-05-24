import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.UUID

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.openjfx.javafxplugin") version "0.0.13"
}

group = "org.nitri"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

javafx {
    version = "17.0.2"
    modules = listOf("javafx.web", "javafx.swing") // This includes javafx.controls and javafx.graphics automatically
}

dependencies {
    // Note, if you develop a library, you should use compose.desktop.common.
    // compose.desktop.currentOs should be used in launcher-sourceSet
    // (in a separate module for demo project and in testMain).
    // With compose.desktop.common you will also lose @Preview functionality
    implementation(compose.desktop.currentOs)

    // JetBrains Compose for Swing UI components
    implementation("org.jetbrains.compose.ui:ui-desktop:1.4.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // JavaFX Modules
    val javafxVersion = "21"

    implementation("org.openjfx:javafx-controls:$javafxVersion")
    implementation("org.openjfx:javafx-swing:$javafxVersion")
    implementation("org.openjfx:javafx-web:$javafxVersion")

    //implementation("org.openjfx:javafx-swing:17.0.2")

    // JCEF (JetBrains Chromium Embedded Framework)
    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.7.55") // For Windows
    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.7.55")  // For Linux
    implementation("org.jetbrains.skiko:skiko-awt-runtime-macos-x64:0.7.55")  // For macOS

}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(
                TargetFormat.Exe,
                TargetFormat.Deb)
            packageName = "GpxPlayer"
            packageVersion = "1.0.0"
            linux {
                iconFile.set(project.file("assets/GpxPlayer.ico"))
            }
            windows {
                menuGroup = "Gpx Tools"
                upgradeUuid = UUID.randomUUID().toString()
                iconFile.set(project.file("assets/GpxPlayer.ico"))
            }
        }
    }
}
