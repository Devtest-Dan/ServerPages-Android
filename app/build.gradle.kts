import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.serverpages"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.serverpages"
        minSdk = 29
        targetSdk = 33
        versionCode = 2
        versionName = "1.2.0"

        buildConfigField(
            "String",
            "TAILSCALE_AUTH_KEY",
            "\"${localProps.getProperty("tailscale.authKey", "")}\""
        )
        buildConfigField(
            "String",
            "TAILSCALE_HOSTNAME",
            "\"${localProps.getProperty("tailscale.hostname", "airdeck-qq")}\""
        )
    }

    signingConfigs {
        create("release") {
            storeFile = file("../airdeck-release.jks")
            storePassword = "airdeck2026"
            keyAlias = "airdeck"
            keyPassword = "airdeck2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.nanohttpd)
    implementation(libs.gson)
    implementation(libs.webrtc)
    implementation(libs.work.runtime.ktx)

    // EncryptedSharedPreferences for Tailscale state store
    implementation("androidx.security:security-crypto:1.1.0-alpha07")

    // Embedded Tailscale (gomobile-bound from tailscale-android@HEAD)
    implementation(files("libs/libtailscale.aar"))
}
