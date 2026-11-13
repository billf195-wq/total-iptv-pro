import java.io.File
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10"
}

group = "com.totaliptv.pro"
version = "1.2.33"

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
            // Compose rejects formats that this OS cannot produce (e.g. AppImage on macOS).
            val osName = System.getProperty("os.name").orEmpty().lowercase()
            targetFormats(
                *when {
                    osName.contains("mac") || osName.contains("darwin") ->
                        arrayOf(TargetFormat.Dmg)
                    osName.contains("win") ->
                        arrayOf(TargetFormat.Exe, TargetFormat.Msi)
                    else ->
                        arrayOf(TargetFormat.Deb, TargetFormat.AppImage)
                }
            )
            packageName = "TotalIptvPro"
            packageVersion = "1.2.33"
            description = "Total IPTV Pro — desktop IPTV player (Linux, Windows & macOS)"
            linux {
                packageName = "total-iptv-pro"
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "Total IPTV Pro"
                upgradeUuid = "a7c3e91f-4b2d-4e8a-9f1c-6d5e8b0a2c4d"
            }
            macOS {
                iconFile.set(project.file("src/main/resources/icon.icns"))
                bundleID = "com.totaliptv.pro.desktop"
                dockName = "Total IPTV Pro"
                // Unsigned test build: no Apple Developer account yet. Ad-hoc
                // codesign is applied after packaging.
                signing {
                    sign.set(false)
                }
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


// Bake an optional TMDB key for service groups in local/test builds.
// Read from -PtipTmdbKeyFile / TIP_TMDB_KEY_FILE, or from TIP_TMDB_SHARED_KEY
// written to a temp file that is deleted after the task. The key is never logged
// or written into the source tree.
val tmdbGenDir = layout.buildDirectory.dir("generated/tmdb")
kotlin.sourceSets.getByName("main").kotlin.srcDir(tmdbGenDir)

tasks.register("generateTmdbBuiltInKey") {
    val outFile = tmdbGenDir.map { it.file("com/totaliptv/pro/desktop/artwork/TmdbBuiltInKey.kt") }
    outputs.file(outFile)
    outputs.upToDateWhen { false }
    doLast {
        val out = outFile.get().asFile
        var tempFile: File? = null
        try {
            val named = (project.findProperty("tipTmdbKeyFile") as String?)
                ?: System.getenv("TIP_TMDB_KEY_FILE")
            var keyFile = named?.let { File(it) }?.takeIf { it.isFile }
            if (keyFile == null) {
                val shared = System.getenv("TIP_TMDB_SHARED_KEY")?.trim().orEmpty()
                if (shared.isNotEmpty()) {
                    tempFile = File.createTempFile("tip-tmdb-", ".key")
                    tempFile.writeText(shared)
                    keyFile = tempFile
                }
            }
            val key = keyFile
                ?.readLines()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.replace("\\", "\\\\")
                ?.replace("\"", "\\\"")
                .orEmpty()
            out.parentFile.mkdirs()
            out.writeText(
                "package com.totaliptv.pro.desktop.artwork\n\n" +
                    "/** Compile-time default TMDB key. Generated; never commit this file. */\n" +
                    "internal object TmdbBuiltInKey {\n" +
                    "    const val VALUE: String = \"$key\"\n" +
                    "}\n"
            )
        } finally {
            tempFile?.delete()
        }
    }
}

tasks.named("compileKotlin").configure { dependsOn("generateTmdbBuiltInKey") }
tasks.matching { it.name == "compileTestKotlin" }.configureEach { dependsOn("generateTmdbBuiltInKey") }
