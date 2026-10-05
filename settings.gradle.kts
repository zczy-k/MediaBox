import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/releases")
    }
    resolutionStrategy {
        eachPlugin {
            // Chaquopy 的 plugin marker 未能稳定解析,直接映射到实现工件
            if (requested.id.id == "com.chaquo.python") {
                useModule("com.chaquo.python:gradle:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("http://4thline.org/m2") {
            isAllowInsecureProtocol = true
        }
        maven("https://jitpack.io")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/releases")
    }
}

rootProject.name = "MediaBox"
include(":app")
include(":player")
include(":quickjs")
include(":pyramid")
include(":libs:backdrop")
