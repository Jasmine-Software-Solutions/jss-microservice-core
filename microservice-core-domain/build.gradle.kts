plugins {
    kotlin("jvm")
}

group = "com.jasminesoftwaresolutions"
version = "1.0.0-SNAPSHOT"

kotlin {
    jvmToolchain(26)
    compilerOptions { javaParameters.set(true) }
}
