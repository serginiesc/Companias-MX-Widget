plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Llave fija para firmar (CI la descifra del secreto DEBUG_KEYSTORE_B64 y deja su ruta aquí).
// Se configura EXPLÍCITAMENTE: depender de la ubicación por defecto de la llave de debug hizo que
// la v1.3 y la v1.4 salieran firmadas con llaves temporales distintas.
val fixedKeystore: File? = System.getenv("SIGNING_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "mx.mitelcel.widget"
    compileSdk = 35

    defaultConfig {
        applicationId = "mx.mitelcel.widget"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.5-beta"
    }

    signingConfigs {
        if (fixedKeystore != null) {
            create("fixed") {
                storeFile = fixedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (fixedKeystore != null) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
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
        buildConfig = true // BuildConfig.VERSION_NAME: lo usa UpdateManager para comparar versiones
    }

    testOptions {
        // Robolectric necesita los recursos (layouts, temas) para inflar pantallas en la JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3") // Photo Picker (PickVisualMedia)
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
