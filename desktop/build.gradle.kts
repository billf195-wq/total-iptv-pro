import java.io.File

plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10"
}

group = "com.totaliptv.pro"
version = "1.2.22"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.totaliptv.pro.desktop.MainKt"
        // Compose Desktop's bundled ProGuard 7.2 cannot read Java 21 (class file 65) bytecode.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi
            )
            packageName = "TotalIptvPro"
            packageVersion = "1.2.22"
            description = "Total IPTV Pro — desktop IPTV player (Linux & Windows)"
            linux {
                packageName = "total-iptv-pro"
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "Total IPTV Pro"
                upgradeUuid = "a7c3e91f-4b2d-4e8a-9f1c-6d5e8b0a2c4d"
            }
        }
    }
}

// Compose jlink defaults to --strip-native-commands, which drops java/javaw from
// the bundled runtime. Copy matching launchers from the build JDK after packaging.
tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        ensureRuntimeJavaLaunchers(project)
    }
}

fun ensureRuntimeJavaLaunchers(project: Project) {
    val binaries = project.layout.buildDirectory.dir("compose/binaries").get().asFile
    if (!binaries.isDirectory) return
    val javaHomeBin = File(System.getProperty("java.home"), "bin")
    val launchers = listOf("java", "java.exe", "javaw", "javaw.exe")
    binaries.walkTopDown()
        .filter { it.isDirectory && it.name == "runtime" }
        .forEach { runtime ->
            val destBin = runtime.resolve("bin")
            destBin.mkdirs()
            for (name in launchers) {
                val src = javaHomeBin.resolve(name)
                val dest = destBin.resolve(name)
                if (src.isFile && !dest.isFile) {
                    src.copyTo(dest)
                    dest.setExecutable(true, false)
                    project.logger.lifecycle("Copied $name into ${destBin.relativeTo(project.projectDir)}")
                }
            }
            val javaOk = destBin.resolve("java").isFile || destBin.resolve("java.exe").isFile
            check(javaOk) {
                "Bundled runtime is missing java/java.exe under $destBin. " +
                    "Install JDK 21 and rebuild, or copy launchers from a matching Temurin 21."
            }
        }
}
