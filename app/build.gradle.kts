import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 高德 key 从 local.properties 读取（amap.key=xxxx），不进版本库
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// 优先 local.properties，其次 -Pamap.key=...（CI 用）
val amapKey: String = localProps.getProperty("amap.key")
    ?: (project.findProperty("amap.key") as String?)
    ?: ""

android {
    namespace = "com.yg.busisland"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.yg.busisland"
        minSdk = 26
        targetSdk = 33
        versionCode = 4
        versionName = "1.1.1"
        manifestPlaceholders["amapKey"] = amapKey
        buildConfigField("String", "AMAP_KEY", "\"$amapKey\"")
    }

    // 仓库里不带签名文件；本地放好 keystore/busisland.jks 才会命中固定签名，
    // CI / 其他人的环境没有该文件时退回默认 debug 签名，照样能构建。
    val keystoreFile = rootProject.file("keystore/busisland.jks")
    val hasKeystore = keystoreFile.exists()

    signingConfigs {
        if (hasKeystore) {
            create("app") {
                storeFile = keystoreFile
                storePassword = localProps.getProperty("keystore.storePassword") ?: "busisland123"
                keyAlias = localProps.getProperty("keystore.keyAlias") ?: "busisland"
                keyPassword = localProps.getProperty("keystore.keyPassword") ?: "busisland123"
            }
        }
    }

    buildTypes {
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("app")
        }
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("app")
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("com.amap.api:location:11.3.101")
}
