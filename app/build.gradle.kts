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
