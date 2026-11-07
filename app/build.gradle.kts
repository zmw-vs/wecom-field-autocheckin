plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zmw.autocheckin"
    // 任务书约束 1：minSdk 29 / targetSdk 34（Android 10 ~ 14）
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zmw.autocheckin"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 任务书约束 4：不新增第三方依赖。appcompat 未被使用，删除；
    // core-ktx 为既有依赖（ContextCompat.registerReceiver 兼容 API<33），保留。
    implementation("androidx.core:core-ktx:1.13.1")
}
