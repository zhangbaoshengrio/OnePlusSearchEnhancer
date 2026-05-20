plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.rio.opluslauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rio.opluslauncher"
        minSdk = 31
        targetSdk = 34
        versionCode = 2
        // 版本号带上目标桌面 build,方便排查兼容问题
        versionName = "1.0.1-oos16.4.15"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 用 debug keystore 给 release 签名,装得上就行 —— 个人模块不走正式签名流程
            signingConfig = signingConfigs.getByName("debug")
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
    // Xposed API:只在编译期需要,运行时由 LSPosed 提供
    compileOnly("de.robv.android.xposed:api:82")

    // @Keep 注解:YukiHookAPI 的 KSP 生成代码会用到
    implementation("androidx.annotation:annotation:1.7.1")

    // YukiHookAPI:Hook 框架本体 + KSP 注解处理器(自动生成 Xposed 入口)
    implementation("com.highcapable.yukihookapi:api:1.2.0")
    ksp("com.highcapable.yukihookapi:ksp-xposed:1.2.0")
}
