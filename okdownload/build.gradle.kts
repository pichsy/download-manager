plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.gankao.gkappstore.downloader"
    compileSdk = rootProject.ext.get("compileSdk") as Int

    defaultConfig {
        minSdk = rootProject.ext.get("minSdk") as Int
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext.get("javaVersion") as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext.get("javaVersion") as String)
    }

    kotlinOptions {
        jvmTarget = rootProject.ext.get("javaVersion") as String
    }


    buildFeatures {
        viewBinding = true
    }
}



dependencies {
    api(libs.androidx.annotation)
    // room ksp
    ksp(libs.androidx.room.compiler)
    api(libs.androidx.annotation)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.activity.ktx)
    api(libs.androidx.fragment.ktx)

    api(libs.okhttp)
    api(libs.retrofit)
    api(libs.loggingInterceptor)
    api(libs.gson)
    api(libs.converterGson)
    api(libs.okio)
    api(libs.glide)


    // 下载器
//    // 核心下载库，普通文件下载，必选
//    api("com.gankao.downloadkit:downloadkit-core:1.1.4")
//    // 下载任务本地保存扩展，基于mmkv实现，可选
//    api("com.gankao.downloadkit:downloadkit-mmkv:1.1.2")
//    // m3u8下载转换mp4，可选
//    api("com.gankao.downloadkit:downloadkit-m3u8:1.1.1")


//    // 转换为kts
//    implementation("me.laoyuyu.aria:core:3.8.16")
//    ksp("me.laoyuyu.aria:compiler:3.8.16")
//    implementation("me.laoyuyu.aria:ftp:3.8.16")
//    implementation("me.laoyuyu.aria:sftp:3.8.16")
//    implementation("me.laoyuyu.aria:m3u8:3.8.16")

}