plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.whl.quickjs.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    buildFeatures {
        buildConfig = false
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // 脱糖:minSdk 24 下 java.time / java.util.stream / java.nio.file 等 JDK 库 API 改写为 j$ 实现
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    api(libs.quickjs.wrapper.android)
    api(libs.quickjs.wrapper.java)

    // 脱糖运行时库(实际打包在 :app,此处声明以启用本模块代码的脱糖)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
