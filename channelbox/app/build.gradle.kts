import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release key material stays outside git. Env vars override channelbox/keystore.properties.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun signingValue(envName: String, propertyName: String): String? {
    val fromEnv = System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }
    if (fromEnv != null) return fromEnv
    return keystoreProperties.getProperty(propertyName)?.trim()?.takeIf { it.isNotEmpty() }
}

val tipStorePath = signingValue("TIP_KEYSTORE_PATH", "storeFile")
val tipStorePassword = signingValue("TIP_KEYSTORE_PASSWORD", "storePassword")
val tipKeyAlias = signingValue("TIP_KEY_ALIAS", "keyAlias")
val tipKeyPassword = signingValue("TIP_KEY_PASSWORD", "keyPassword")

fun resolveKeystoreFile(raw: String): File {
    val direct = File(raw)
    if (direct.isAbsolute) return direct
    val fromChannelBox = rootProject.file(raw)
    if (fromChannelBox.isFile) return fromChannelBox
    val besideProperties = File(keystorePropertiesFile.parentFile, raw)
    if (besideProperties.isFile) return besideProperties
    return file(raw)
}

val tipStoreFile = tipStorePath?.let { resolveKeystoreFile(it) }
val releaseKeystore = tipStoreFile?.takeIf { store ->
    store.isFile &&
        !tipStorePassword.isNullOrBlank() &&
        !tipKeyAlias.isNullOrBlank() &&
        !tipKeyPassword.isNullOrBlank()
}

android {
    namespace = "com.totaliptv.pro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.totaliptv.pro"
        minSdk = 24
        targetSdk = 35
        versionCode = 86
        versionName = "1.4.74"
        buildConfigField(
            "String",
            "DEFAULT_UPDATE_BASE_URL",
            "\"\""
        )
    }

    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            // Keep Shield / Leanback applicationId unchanged.
            versionCode = 86
            versionName = "1.4.74"
            buildConfigField(
                "String",
                "DEFAULT_UPDATE_BASE_URL",
                "\"\""
            )
        }
        create("phone") {
            dimension = "device"
            applicationIdSuffix = ".phone"
            // Phone-only bump so Shield is not forced to update.
            versionCode = 63
            versionName = "1.4.51-phone"
            resValue("string", "app_name", "Total IPTV Pro Phone")
            buildConfigField(
                "String",
                "DEFAULT_UPDATE_BASE_URL",
                "\"\""
            )
        }
    }

    signingConfigs {
        val store = releaseKeystore
        val storePassword = tipStorePassword
        val alias = tipKeyAlias
        val keyPassword = tipKeyPassword
        if (store != null && storePassword != null && alias != null && keyPassword != null) {
            create("release") {
                storeFile = store
                this.storePassword = storePassword
                keyAlias = alias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (signingConfigs.findByName("release") != null) {
                signingConfigs.getByName("release")
            } else {
                // CI and machines without the release key still produce an installable APK.
                signingConfigs.getByName("debug")
            }
        }
    }

    applicationVariants.configureEach {
        if (buildType.name != "release") return@configureEach
        val assetVersion = versionName.removeSuffix("-phone")
        val apkName = "TotalIPTVPro-android-$flavorName-$assetVersion-release.apk"
        outputs.configureEach {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = apkName
        }
    }

    compileOptions {
        // java.time is API 26. Desugar it so minSdk 24 does not trip NewApi.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        checkOnly += "NewApi"
        error += "NewApi"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

if (releaseKeystore == null) {
    val reason = when {
        tipStorePath == null ->
            "No release keystore configured."
        tipStoreFile == null || !tipStoreFile.isFile ->
            "Release keystore not found at ${tipStoreFile?.absolutePath ?: tipStorePath}."
        else ->
            "Release keystore at ${tipStoreFile.absolutePath} is missing a password or key alias."
    }
    gradle.taskGraph.whenReady {
        val buildingRelease = allTasks.any { it.name.contains("Release") }
        if (buildingRelease) {
            logger.warn(
                "$reason Release APKs will be signed with the debug key and will not install " +
                    "over a build signed on another machine. Set TIP_KEYSTORE_PATH, " +
                    "TIP_KEYSTORE_PASSWORD, TIP_KEY_ALIAS, and TIP_KEY_PASSWORD, or create " +
                    "channelbox/keystore.properties (see channelbox/README.md). Do not commit the keystore."
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    "tvImplementation"(libs.androidx.tv.foundation)
    "tvImplementation"(libs.androidx.tv.material)
    "tvImplementation"(libs.androidx.compose.material3)

    "phoneImplementation"(libs.androidx.compose.material3)
    "phoneImplementation"(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.serialization.json)
}

// NewApi is an error on debug and release builds, not only lintVitalRelease.
tasks.matching { it.name.matches(Regex("assemble(Tv|Phone)(Debug|Release)")) }.configureEach {
    dependsOn("lint${name.removePrefix("assemble")}")
}
