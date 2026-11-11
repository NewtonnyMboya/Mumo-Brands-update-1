plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = System.getenv("MUMO_KEYSTORE_FILE")
val releaseStorePassword = System.getenv("MUMO_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("MUMO_KEY_ALIAS")
val releaseKeyPassword = System.getenv("MUMO_KEY_PASSWORD")
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.mumobrands.printing"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mumobrands.printing"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }
    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = file(releaseStoreFile!!)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
