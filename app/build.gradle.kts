plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFilePath = (findProperty("RELEASE_STORE_FILE") as? String)?.takeIf { it.isNotBlank() }
val releaseStorePassword = (findProperty("RELEASE_STORE_PASSWORD") as? String)?.takeIf { it.isNotBlank() }
val releaseKeyAlias = (findProperty("RELEASE_KEY_ALIAS") as? String)?.takeIf { it.isNotBlank() }
val releaseKeyPassword = (findProperty("RELEASE_KEY_PASSWORD") as? String)?.takeIf { it.isNotBlank() }
val hasReleaseSigning = releaseStoreFilePath != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null

fun resolveKeyPassword(storeFile: java.io.File?, storePass: String?, keyPass: String?, alias: String?): String? {
    if (storeFile == null || !storeFile.exists() || storePass == null || alias == null) return keyPass ?: storePass
    val candidate = keyPass?.takeIf { it.isNotBlank() } ?: return storePass
    return try {
        val ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
        storeFile.inputStream().use { ks.load(it, storePass.toCharArray()) }
        try {
            ks.getKey(alias, candidate.toCharArray())
            candidate
        } catch (_: Exception) {
            storePass
        }
    } catch (_: Exception) {
        candidate
    }
}

val actualKeyPassword = resolveKeyPassword(
    storeFile = releaseStoreFilePath?.let { file(it) },
    storePass = releaseStorePassword,
    keyPass = releaseKeyPassword,
    alias = releaseKeyAlias
)

android {
    namespace = "com.example.radioshuffle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.radioshuffle"
        minSdk = 26
        targetSdk = 35

        val appVersionName = project.findProperty("customVersionName") as? String ?: "1.0.0"
        val appVersionCode = (project.findProperty("customVersionCode") as? String)?.toIntOrNull() ?: 1

        versionCode = appVersionCode
        versionName = appVersionName

        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = actualKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.version"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")

    // Media3 (Playback and Background MediaSession)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
