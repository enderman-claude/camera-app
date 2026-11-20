plugins { id("com.android.application") }

android {
    namespace = "dev.archbox.camera"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.archbox.camera"
        minSdk = 29          // MediaStore RELATIVE_PATH and IS_PENDING need API 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    // Release signing comes from CI secrets (see .github/workflows/build.yml).
    // Without them, release builds fall back to the debug key, so local builds still work.
    signingConfigs {
        create("release") {
            val ks = System.getenv("CAMERA_KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("CAMERA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CAMERA_KEY_ALIAS")
                keyPassword = System.getenv("CAMERA_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = if (System.getenv("CAMERA_KEYSTORE_PATH") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.0")
    // Apache-2.0, Google AndroidX. Writes EXIF metadata into saved JPEGs.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
