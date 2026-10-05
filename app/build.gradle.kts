import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * 签名口令读取顺序：环境变量（CI 由 GitHub Secrets 注入）→ 未跟踪的 keystore.properties（本地）→ gradle.properties。
 * 仓库里不再保存任何口令明文。
 */
fun signingProp(name: String): String? =
    System.getenv(name)
        ?: rootProject.file("keystore.properties")
            .takeIf { it.exists() }
            ?.let { file ->
                file.readLines()
                    .firstOrNull { it.trimStart().startsWith("$name=") }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        ?: (project.findProperty(name) as String?)?.takeIf { it.isNotEmpty() }

/**
 * 版本号来源:CI 由 `-PappVersionName=vX.Y.Z` 注入(取自 Release tag,与 tag 同源 ——
 * 这样 App 内更新检查拿 tag 与本地 versionName 比较才成立);本地构建用下面的默认值。
 */
val appVersionName: String = (project.findProperty("appVersionName") as String?)
    ?.removePrefix("v")
    ?.removePrefix("V")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "1.0.1"

/**
 * versionCode 由 versionName 推导,不再手工维护 —— 避免"发了新版本忘了加 versionCode"
 * 导致新包装不上旧包(系统要求 versionCode 严格递增)。
 * 1.2.3 → 10203;每段最多取两位(超出截断),保证单调递增。
 */
fun versionCodeOf(name: String): Int {
    val parts = name.split('.', '-', '_')
    fun seg(i: Int) = parts.getOrNull(i)?.filter { it.isDigit() }?.take(2)?.toIntOrNull() ?: 0
    return seg(0) * 10_000 + seg(1) * 100 + seg(2)
}

/**
 * 是否启用"启动强制更新"闸门。
 *
 * <p>默认 true —— 正式发布包必须强制更新。CI 构建**自用调试包**时传 `-PforceUpdate=false`
 * 关掉,否则新版本一发布,自己装的调试包也会被自己的强制更新弹窗拦住。
 *
 * <p>另外代码里还有一层 `BuildConfig.DEBUG` 判断(Android Studio 直接跑的包永不检查),
 * 与本值相互独立。
 */
val forceUpdateEnabled: Boolean =
    (project.findProperty("forceUpdate") as String?)?.toBooleanStrictOrNull() ?: true

android {
    namespace = "com.github.tvbox.osc"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.zczy.mediabox"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = versionCodeOf(appVersionName)
        versionName = appVersionName
        // 启动强制更新闸门的开关(见 forceUpdateEnabled 注释):CI 测试包用 -PforceUpdate=false 关掉
        buildConfigField("boolean", "FORCE_UPDATE_ENABLED", forceUpdateEnabled.toString())
        multiDexEnabled = true
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/beans.xml")
        }
    }

    androidResources {
        localeFilters += listOf("en", "zh", "zh-rCN", "b+zh+Hant", "zh-rTW", "zh-rHK")
    }

    sourceSets {
        getByName("main") {
            java.directories += "src/python/java"
        }
    }

    // 签名材料不入库：口令只从环境变量 / 本地未跟踪的 keystore.properties 读，
    // CI 侧由 release.yml 把 GitHub Secrets 还原成 .key/app-release.jks + 环境变量。
    // 缺任一项就跳过 create("release")，此时 release 包走未签名（本地开发不受影响）。
    signingConfigs {
        val storeFilePath = signingProp("RELEASE_STORE_FILE") ?: ".key/app-release.jks"
        val storeFileResolved = rootProject.file(storeFilePath)
        // 局部变量不能与 signingConfig 的属性同名：create("release") { } 内右侧会解析到
        // 块自己的 val 属性（storePassword/storeFile/keyAlias/keyPassword），赋值得可变形参，
        // 写成同名会报 "'val' cannot be reassigned"。
        val storePwd = signingProp("RELEASE_STORE_PASSWORD")
        val aliasName = signingProp("RELEASE_KEY_ALIAS")
        val keyPwd = signingProp("RELEASE_KEY_PASSWORD")
        if (storeFileResolved.exists() && !storePwd.isNullOrEmpty()
            && !aliasName.isNullOrEmpty() && !keyPwd.isNullOrEmpty()
        ) {
            create("release") {
                storeFile = storeFileResolved
                storePassword = storePwd
                keyAlias = aliasName
                keyPassword = keyPwd
            }
        } else {
            logger.lifecycle("[MediaBox] 签名材料不完整，release 包将不签名（CI 会从 Secrets 注入）")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "proguard-python.pro")
        }
    }
    splits {
        abi {
            isEnable = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("MediaBox_${variant.buildType}.apk")
        }
    }
}

dependencies {
    api(fileTree("libs") { include("*.jar", "*.aar") })

    implementation(libs.nanohttpd)
    implementation(libs.cling.core)
    implementation(libs.cling.support)
    compileOnly(libs.cdi.api)
    compileOnly(libs.javax.inject)
    compileOnly(libs.javax.annotation.api)
    compileOnly(libs.javax.servlet.api)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.media)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.okio)
    implementation(libs.gson)
    implementation(libs.autosize)
    implementation(libs.xstream) {
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "xpp3", module = "xpp3_min")
    }
    implementation(libs.eventbus)
    implementation(libs.mmkv)
    implementation(libs.danmaku.flame.master)

    implementation(project(":player"))
    // 画质参数(调色)的着色器效果:ExoPlayer#setVideoEffects 在运行期反射查找效果模块,必须打进包
    implementation(libs.media3.effect)
    implementation(project(":quickjs"))
    implementation(project(":pyramid"))

    implementation(libs.okgo)
    implementation(libs.xx.permissions)
    implementation(libs.jsoup)
    implementation(libs.commons.io)
    implementation(libs.juniversalchardet)
    // zxing:动态加载的爬虫 jar 运行期需要 com.google.zxing.*(二维码),宿主必须提供。
    // 宿主源码无静态引用,禁止按"零引用"删除;keep 规则见 proguard-rules.pro
    implementation(libs.zxing.core)
    // sardine:订阅源 jar 里的 WebDAV 爬虫(com.github.catvod.spider.WebDAV)用它做
    implementation(libs.sardine) {
        exclude(group = "xpp3", module = "xpp3")
    }

    // Compose UI(avbox-mobile-ui-spec §2)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.materialkolor)
    implementation(project(":libs:backdrop"))
    implementation(libs.capsule)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
}

configurations.configureEach {
    exclude(group = "io.antmedia", module = "rtmp-client")
}
