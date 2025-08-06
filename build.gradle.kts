val kotlin_version: String by project
val logback_version: String by project
val hikariCpVersion = "5.0.1"
val flywayVersion = "8.5.4"
plugins {
    kotlin("jvm") version "2.1.10"
    id("io.ktor.plugin") version "3.2.2"
}

group = "cn.com.lushunming"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

repositories {
    maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }
    maven { url = uri("https://maven.aliyun.com/nexus/content/groups/public") }
    mavenCentral()
}
buildscript {
    repositories {
        maven { url = uri("https://maven.aliyun.com/nexus/content/groups/public") }
        mavenCentral()
    }
}



dependencies {
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-swagger")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-gson")
    implementation("io.ktor:ktor-server-netty")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-config-yaml")
    implementation("io.ktor:ktor-server-websockets")
    implementation("io.ktor:ktor-server-thymeleaf")

    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-okhttp")
    implementation("io.ktor:ktor-client-okhttp-jvm:3.2.2")
    //db
    implementation("org.jetbrains.exposed:exposed-core:1.0.0-beta-5")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.0.0-beta-5")
    implementation("com.h2database:h2:2.2.224")
    implementation("com.zaxxer:HikariCP:${hikariCpVersion}")
    implementation("org.flywaydb:flyway-core:${flywayVersion}")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    //testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.10.2")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}