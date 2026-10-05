plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.github.tvbox.osc.player"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += setOf("arm64-v8a")
            }
        }
        release {
            isMinifyEnabled = false
            ndk {
                abiFilters += setOf("arm64-v8a")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation(libs.androidx.annotation)
    implementation(libs.okhttp)
    api(libs.media3.exoplayer)
    api(libs.media3.exoplayer.dash)
    api(libs.media3.exoplayer.hls)
    api(libs.media3.exoplayer.rtsp)
    api(libs.media3.datasource)
    // rtmp 扩展(DefaultDataSource 反射加载 RtmpDataSource);其自带的 io.antmedia:rtmp-client 为 4KB 页对齐,
    // 由 app 侧 exclude 后改用 app/libs 里已修 16KB 的同名库
    api(libs.media3.datasource.rtmp)
    api(libs.media3.database)
    api(libs.media3.ui)
    // jellyfin 预编译 ffmpeg 软解(16KB 页对齐),类名 androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
    // 由 media3 DefaultRenderersFactory 反射自动发现,无需手动接线
    api(libs.media3.ffmpeg.decoder)
    api(libs.dkplayer.ui)

    // 脱糖运行时库(实际打包在 :app,此处声明以启用本模块代码的脱糖)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
