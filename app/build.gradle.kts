plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Numéro de version fourni par GitHub Actions (1, 2, 3…) ; 1 en local
val vCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val vName = (project.findProperty("versionName") as String?) ?: "1.0-local"
val keystorePath: String? = System.getenv("HALO_KEYSTORE")

android {
    namespace = "fr.cedriccoste.retouchelot"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.cedriccoste.retouchelot"
        minSdk = 30
        targetSdk = 34
        versionCode = vCode
        versionName = vName
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("HALO_KS_PASS")
                keyAlias = "halo"
                keyPassword = System.getenv("HALO_KS_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sur GitHub : clé officielle de Halo. En local : clé de debug.
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
