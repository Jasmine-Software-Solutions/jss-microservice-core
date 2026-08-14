plugins {
    kotlin("jvm")
}

group = "com.jasminesoftwaresolutions"
version = "1.0.0-SNAPSHOT"

dependencies {
    api(project(":microservice-core-domain"))

    api("org.jetbrains.exposed:exposed-core:0.40.1")
    api("org.jetbrains.exposed:exposed-dao:0.40.1")
    api("com.zaxxer:HikariCP:4.0.3")
    api("com.google.code.gson:gson:2.13.2")
    api("org.slf4j:slf4j-api:2.0.3")
    api(platform("software.amazon.awssdk:bom:2.29.52"))
    api("software.amazon.awssdk:sns")

    implementation("org.jetbrains.exposed:exposed-jdbc:0.40.1")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

kotlin {
    jvmToolchain(26)
    compilerOptions { javaParameters.set(true) }
}

tasks.test { useJUnitPlatform() }
tasks.withType<Test> { maxParallelForks = 1 }
