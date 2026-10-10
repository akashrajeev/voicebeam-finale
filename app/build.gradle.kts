plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Label the checked-out source, not the event that requested a build.
val sourceCommit = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.get().trim().also {
    require(it.matches(Regex("[0-9a-f]{40}"))) { "Cannot determine checked-out source commit" }
}

android {
    namespace = "com.akashrajeev.voicebeam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.akashrajeev.voicebeam.finale"
        minSdk = 26
        targetSdk = 35
        buildConfigField("String", "LAB_COMMIT", "\"" + sourceCommit + "\"")
        versionCode = 121
        versionName = "RECALL-2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += if (System.getenv("VB_EMULATOR") == "1") listOf("x86_64") else listOf("arm64-v8a")
        }
    }

    // Release signing comes from the environment, never from the repo. Without these four variables
    // the release build is left unsigned instead of falling back to the debug key.
    val releaseKeystore = System.getenv("VB_RELEASE_KEYSTORE")
    signingConfigs {
        if (System.getenv("VB_RECALL_KEYSTORE") != null) {
            create("recallLab") {
                storeFile = file(System.getenv("VB_RECALL_KEYSTORE"))
                storePassword = System.getenv("VB_RECALL_KEY_PASSWORD")
                keyAlias = "recall"
                keyPassword = System.getenv("VB_RECALL_KEY_PASSWORD")
            }
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("VB_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("VB_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("VB_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("recallLab") ?: signingConfigs.getByName("debug")
            // Debug also carries x86_64 so the emulator tests can run it.
            ndk { abiFilters += if (System.getenv("VB_EMULATOR") == "1") listOf("x86_64") else listOf("arm64-v8a") }
        }
        release {
            // R8 stays off until a minified build has been run on a device: the sherpa-onnx JNI layer
            // and MediaPipe need keep rules (see proguard-rules.pro) that are not yet verified.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += listOf("onnx", "task", "txt", "bin") }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation(files("libs/sherpa-onnx.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-video:$camerax")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    // Debug only: face fallback for x86_64 emulators (MediaPipe ships arm64-only).
    debugImplementation("com.google.mlkit:face-detection:16.1.7")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")

    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
