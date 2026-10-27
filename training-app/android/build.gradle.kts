plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val releaseSigningPath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
gradle.taskGraph.whenReady {
    if (hasTask(":android:assembleRelease")) require(releaseSigningPath != null) { "正式构建需要 ANDROID_KEYSTORE_PATH 及签名凭据" }
}
android {
    namespace = "top.e404.training"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "top.e404.training"
        minSdk = 26
        targetSdk = 36
        versionCode = rootProject.extra["androidVersionCode"] as Int
        versionName = project.version.toString()
    }
    if (releaseSigningPath != null) signingConfigs.create("release") {
        storeFile = file(releaseSigningPath)
        storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
        keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
        keyPassword = storePassword
    }
    buildTypes.getByName("release") {
        isDebuggable = false
        if (releaseSigningPath != null) signingConfig = signingConfigs.getByName("release")
    }
    buildTypes.getByName("debug") {
        applicationIdSuffix = ".debug"
        resValue("string", "app_name", "个人训练·验证")
    }
    buildTypes.getByName("release").resValue("string", "app_name", "个人训练")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].assets.srcDir(rootProject.file("catalog"))
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/INDEX.LIST") }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", file("schemas").absolutePath) }
dependencies {
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.ktor:ktor-client-okhttp:3.3.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
