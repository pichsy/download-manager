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
    namespace = "com.pichs.shanhai.base"
    compileSdk = rootProject.ext.get("compileSdk") as Int

    defaultConfig {
        minSdk = rootProject.ext.get("minSdk") as Int
        consumerProguardFiles("consumer-rules.pro")
        // 将上面的代码转成 kts
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
    api(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    api(libs.androidx.core.ktx)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.appcompat)
    api(libs.material)
    api(libs.androidx.recyclerview)
    api(libs.androidx.annotation)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.activity.ktx)
    api(libs.androidx.fragment.ktx)

    api(libs.androidx.draganddrop)

    // brv
    api(libs.brv)
    // 弹窗
    // 弹窗
    api(libs.basepopup)
    api(libs.xxpermissions)
    api(libs.toaster)
    api(libs.easywindow)

    api(libs.xwidget)
    api(libs.filepicker)
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.ui)

    api(libs.xbase)
    api(libs.androidx.swiperefreshlayout)

    // liveEventBus
    api(libs.liveEventBus)
    api(libs.refreshLayoutKernel)
    api(libs.refreshHeaderClassics)
    api(libs.refreshFooterClassics)
    api(libs.refreshHeaderFalsify)
    api(libs.refreshHeaderMaterial)


    api(libs.okhttp)
    api(libs.retrofit)
    api(libs.loggingInterceptor)
    api(libs.gson)
    api(libs.converterGson)
    api(libs.okio)
    api(libs.glide)

    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // mmkv
    api(libs.tencent.mmkv)

    // 数字动画
    api(libs.text.ticker)

    // banner
    api(libs.youth.banner)

    ksp(libs.theRouterCompiler)
    api(libs.theRouter)

    // 文件选择器
    api(libs.xfilechooser)

    // 编码库apache
    api(libs.commons.codec)

    api(libs.nicedialog)

    // kotlin的序列化 protobuf
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.protobuf)
}