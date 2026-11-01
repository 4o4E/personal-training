plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("top.e404.training.collector.MainKt") }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

tasks.processResources {
    from(rootProject.file("collector/schema.sql"))
}
