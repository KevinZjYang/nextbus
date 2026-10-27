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
val amapKey: String = localProps.getProperty("amap.key", "")

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

    signingConfigs {
        create("app") {
            storeFile = rootProject.file("keystore/busisland.jks")
            storePassword = "busisland123"
            keyAlias = "busisland"
            keyPassword = "busisland123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
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
