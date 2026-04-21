import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.UUID

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.openjfx.javafxplugin") version "0.0.13"
}

group = "org.nitri"
val appVersion = "1.0.2"
version = appVersion

repositories {
    mavenCentral()
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
    val javafxVersion = "17.0.2"

    implementation("org.openjfx:javafx-controls:$javafxVersion")
    implementation("org.openjfx:javafx-swing:$javafxVersion")
    implementation("org.openjfx:javafx-web:$javafxVersion")

}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            modules(
                "java.desktop",
                "java.net.http",
                "jdk.unsupported.desktop"
            )
            targetFormats(
                TargetFormat.Exe,
                TargetFormat.Deb)
            packageName = "GpxPlayer"
            packageVersion = appVersion
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
