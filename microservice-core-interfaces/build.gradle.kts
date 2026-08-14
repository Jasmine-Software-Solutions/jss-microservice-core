plugins {
    kotlin("jvm")
}

group = "com.jasminesoftwaresolutions"
version = "1.0.0-SNAPSHOT"

dependencies {
    api("io.javalin:javalin:6.6.0")
    api("com.google.code.gson:gson:2.13.2")
}

kotlin {
    jvmToolchain(26)
    compilerOptions { javaParameters.set(true) }
}
