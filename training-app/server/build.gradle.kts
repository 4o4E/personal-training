plugins { kotlin("jvm"); kotlin("plugin.serialization"); application }
kotlin { jvmToolchain(21) }
application { mainClass.set("top.e404.training.server.MainKt") }
val catalogJar by tasks.registering(Jar::class) {
    archiveBaseName.set("training-catalog")
    from(rootProject.file("catalog"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
dependencies {
    implementation(project(":shared"))
    runtimeOnly(files(catalogJar))
    val ktor = "3.3.3"
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-auth:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
tasks.test { useJUnitPlatform() }
