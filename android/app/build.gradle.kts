plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val serverUrl = providers.gradleProperty("novachat.serverUrl").getOrElse("http://10.0.2.2:8080")

android {
    namespace = "app.novachat"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.novachat"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "0.24.0"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$serverUrl\"")
        // Calls (WebRTC) ship native code per CPU type. Real phones are ARM,
        // so leaving out the emulator-only x86 builds keeps the app ~25 MB
        // smaller. Add "x86_64" here to try calls on a PC emulator.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // A shared debug key (password "android"), so every test build from
        // CI installs over the previous one and keeps your chats. Release
        // builds get their own private key before going to the Play Store.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // R8 shrinking keeps the install small (see the guide's size section).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.tink.android)
    implementation(libs.webrtc)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
