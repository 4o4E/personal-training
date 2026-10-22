plugins {
    id("com.android.application") apply false
    kotlin("jvm") apply false
    kotlin("android") apply false
    kotlin("plugin.serialization") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
}

allprojects {
    group = "top.e404.training"
    version = "1.0.0"
}
