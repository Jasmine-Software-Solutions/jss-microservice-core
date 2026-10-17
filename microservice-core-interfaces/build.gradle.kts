plugins {
    kotlin("jvm")
}

group = "com.jasminesoftwaresolutions"
version = "1.0.0-SNAPSHOT"

dependencies {
    api("io.javalin:javalin:6.6.0")
    api("com.google.code.gson:gson:2.13.2")

    // The OpenAPI plugin, for `OpenApiDocumentation`. `implementation`, not
    // `api`: no javalin-openapi type appears in this module's public
    // signatures, so a consumer of this module — every service's `:interfaces`
    // — declares `openapi-specification` and the annotation processor itself.
    // It must, either way: an annotation processor runs against the sources of
    // the module that registers it.
    //
    // `javalin-swagger-plugin` is deliberately absent. Serving a document and
    // rendering one in a browser are different decisions, and a service that
    // wants a UI adds that dependency itself.
    implementation("io.javalin.community.openapi:javalin-openapi-plugin:6.6.0")
}

kotlin {
    jvmToolchain(26)
    compilerOptions { javaParameters.set(true) }
}
