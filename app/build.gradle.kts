plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.physiorecord"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.physiorecord"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 只引两个库，且每个都用得上：
//   material        —— 商业应用通用的控件体系（底部导航、Tab、卡片、Chip、滑杆、输入框、Snackbar）
//   MPAndroidChart  —— 星数最高的 Android 图表库（折线/柱状/缩放/高亮）
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
}
