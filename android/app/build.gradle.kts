plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.crode.secretum"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.crode.secretum"
        minSdk = 33          // Android 13+: 신형 BLE API(writeCharacteristic(value), onCharacteristicChanged(value)) 전제
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // 기본값은 실기기 + `adb reverse tcp:8080 tcp:8080` 기준. gradle.properties 또는 -P 로 덮어쓰기
        buildConfigField("String", "SERVER_URL", "\"${project.findProperty("secretum.serverUrl") ?: "http://127.0.0.1:8080"}\"")
        buildConfigField("String", "APP_TOKEN", "\"${project.findProperty("secretum.appToken") ?: "dev-token"}\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("com.google.guava:listenablefuture:1.0")  // ProcessCameraProvider.getInstance() 반환 타입

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
