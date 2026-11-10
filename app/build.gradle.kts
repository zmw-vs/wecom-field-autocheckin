import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release 签名凭据只从本地 keystore.properties 读取（该文件不入库）。
// 没有这个文件时仍能正常打 debug 包，release 包则为未签名状态。
val ksPropsFile = rootProject.file("keystore.properties")
val ksProps = Properties()
if (ksPropsFile.exists()) {
    FileInputStream(ksPropsFile).use { ksProps.load(it) }
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

    signingConfigs {
        if (ksProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // 无障碍服务类名写在 Manifest 里，且流程靠界面文案匹配，
            // 不做代码混淆，避免反射/类名变化引入难以排查的问题。
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
