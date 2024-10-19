import java.text.SimpleDateFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

room {
    schemaDirectory("$projectDir/schemas")
}

fun releaseTime(): String {
    return SimpleDateFormat("yyyyMMddHHmm").format(System.currentTimeMillis())
}

android {
    namespace = "com.pichs.download.demo"
    compileSdk = rootProject.ext.get("compileSdk") as Int

    defaultConfig {
        applicationId = "com.pichs.download.demo"
        minSdk = rootProject.ext.get("minSdk") as Int
        targetSdk = rootProject.ext.get("targetSdk") as Int
        versionCode = rootProject.ext.get("versionCode") as Int
        versionName = rootProject.ext.get("versionName").toString()
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
            abiFilters.add("x86_64")
            abiFilters.add("x86")
        }
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    //此处配置必须添加 否则无法正确运行
    androidResources {
        additionalParameters.add("--auto-add-overlay")
        //'foo', 'bar'
        ignoreAssetsPattern = "!.svn:!.git:.*:!CVS:!thumbs.db:!picasa.ini:!*.scc:*~"
    }

    // 根据上面的签名信息，创建正确的签名配置
    signingConfigs {
        // 配置 release 签名,签名开启 v1，V2 签名
        create("appstore") {
            storeFile = file("./appstore.jks")
            storePassword = "shanhai123"
            keyAlias = "shanhai"
            keyPassword = "shanhai123"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
            enableV4Signing = false
        }
    }


    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 签名
            signingConfig = signingConfigs.getByName("appstore")
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 签名
            signingConfig = signingConfigs.getByName("appstore")
        }
    }



    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext.get("javaVersion") as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext.get("javaVersion") as String)
    }

    kotlinOptions {
        jvmTarget = rootProject.ext.get("javaVersion") as String
    }

    // 将上面的 gradle脚本，转成 kts 脚本
    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
                this.outputFileName = "应用市场-${versionName}-${versionCode}-${releaseTime()}-${name}.apk"
            }
        }
    }

    packaging {
        jniLibs {
            excludes += listOf("META-INF/*.kotlin_module")
            pickFirsts.add("lib/arm64-v8a/libc++_shared.so")
            pickFirsts.add("lib/armeabi-v7a/libc++_shared.so")
            pickFirsts.add("lib/armeabi/libc++_shared.so")
            pickFirsts.add("lib/x86/libc++_shared.so")
            pickFirsts.add("lib/x86_64/libc++_shared.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        dex {
            useLegacyPackaging = true
        }
    }

    // viewbinding
    buildFeatures {
        viewBinding = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    api(project(":base"))
    api(project(":download"))
//    api(project(":okdownload"))

}