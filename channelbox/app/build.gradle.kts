plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.totaliptv.pro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.totaliptv.pro"
        minSdk = 24
        targetSdk = 35
        versionCode = 73
        versionName = "1.4.61"
        buildConfigField(
            "String",
            "DEFAULT_UPDATE_BASE_URL",
            "\"http://192.168.4.33:8765/\""
        )
    }

    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            // Keep Shield / Leanback applicationId unchanged.
            versionCode = 73
            versionName = "1.4.61"
            buildConfigField(
                "String",
                "DEFAULT_UPDATE_BASE_URL",
                "\"http://192.168.4.33:8765/\""
            )
        }
        create("phone") {
            dimension = "device"
            applicationIdSuffix = ".phone"
            // Phone-only bump so Shield is not forced to update.
            versionCode = 50
            versionName = "1.4.38-phone"
            resValue("string", "app_name", "Total IPTV Pro Phone")
            buildConfigField(
                "String",
                "DEFAULT_UPDATE_BASE_URL",
                "\"http://192.168.4.33:8765/phone/\""
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.serialization.json)
}
