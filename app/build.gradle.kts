plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.abw3laa.basmaalarm"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.abw3laa.basmaalarm"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    val signingStoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (!signingStoreFile.isNullOrBlank()) {
            create("release") {
                storeFile = file(signingStoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (!signingStoreFile.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
}
