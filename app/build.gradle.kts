plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Gemini credentials come from api.txt (git-ignored, never committed): lines "api-key: …" and "model: …".
val aiConfig: Map<String, String> = rootProject.file("api.txt").takeIf { it.exists() }?.readLines().orEmpty()
    .mapNotNull { line -> line.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().lowercase() to it[1].trim() } }
    .toMap()

android {
    namespace = "com.spendsnap"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.spendsnap"
        // Personal build for a single Pixel 9 on Android 17.
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // Pixel 9 is arm64; skipping other ABIs keeps the bundled OCR model small.
        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("String", "GEMINI_API_KEY", "\"${aiConfig["api-key"].orEmpty()}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${aiConfig["model"] ?: "gemini-3.8-flash"}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideload: signed with the debug key so it installs over the debug build
            // and keeps the database and granted permissions.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Bundled on-device model: no Play Services download, nothing leaves the phone.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Gemini Nano through Android AICore (on-device AI engine for Insights).
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    // GenAI's compiled code calls Job.cancel$default, which only exists in coroutines 1.11+;
    // without this pin 1.9.0 resolves and Nano calls crash with NoSuchMethodError.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (Android's copy is a stub there).
    testImplementation("org.json:json:20240303")
}
