plugins {
    id("com.android.application") apply false
    kotlin("jvm") apply false
    kotlin("android") apply false
    kotlin("plugin.serialization") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
}

// 标签、APK 与后端使用同一版本；偏移一位以支持覆盖既有 versionCode=1 的安装。
val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.0.5").get()
require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})").matches(releaseVersion)) {
    "版本必须为 MAJOR.MINOR.PATCH，次版本与修订号不超过 999"
}
val parts = releaseVersion.split('.').map(String::toLong)
val androidVersion = parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2] + 1
require(androidVersion in 1..2_100_000_000L) { "版本超过安卓内部版本号上限" }
extra["androidVersionCode"] = androidVersion.toInt()

allprojects {
    group = "top.e404.training"
    version = releaseVersion
}
