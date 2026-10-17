// Do NOT add the Kotlin annotation processor plugin here, and never add a
// `kapt(...)` OpenAPI processor to this module.
//
// `WorkspaceController` lives here, and annotating it in place is the obvious
// thing to want — the annotations are on the class, the build should generate
// the document. It cannot. An annotation processor reads only the sources of
// the compilation it runs in, so a processor here would document *this*
// module's sources; a service consuming this jar sees none of them. And running
// one here as well as in a service is worse: the processor writes a fixed
// `openapi-plugin/openapi-default.json` resolved by resource name, and the
// consuming service's fat jar merges duplicates rather than picking a winner,
// so a second copy silently changes which document is served.
//
// Each service documents the shared surface from its own module instead — see
// `hiring_new/interfaces/.../openapi/SharedSurfaceDocumentation.kt`.
plugins {
    kotlin("jvm")
}

group = "com.jasminesoftwaresolutions"
version = "1.0.0-SNAPSHOT"

dependencies {
    api(project(":microservice-core-domain"))

    // `api`: SessionAuthService's public signatures take a Javalin Context and
    // the controllers are route classes a consumer registers, so both are part
    // of this module's contract rather than an implementation detail.
    api("io.javalin:javalin:6.6.0")
    api("io.javalin.community.routing:routing-annotated:6.4.1-SNAPSHOT")
    api("com.google.code.gson:gson:2.13.2")
    api("com.jasminesoftwaresolutions:id-java-client:1.0.0-SNAPSHOT")
    api("com.jasminesoftwaresolutions:witchwood:1.0-SNAPSHOT")

    // WorkspaceService's `transaction {}` — the same Exposed the server module
    // uses, but not re-exported: nothing in this module's API is Exposed-typed.
    implementation("org.jetbrains.exposed:exposed-core:0.40.1")

    // Test-only: the shared fakes and wire-shape assertions use the server
    // module's GsonFactory and the interfaces module's GsonJsonMapper, and the
    // endpoint tests mount controllers on a real Javalin instance.
    testImplementation(project(":microservice-core-server"))
    testImplementation(project(":microservice-core-interfaces"))
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("io.javalin:javalin-testtools:6.6.0")
    // WorkspaceServiceTest runs the service against the real Exposed repository.
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

kotlin {
    jvmToolchain(26)
    compilerOptions { javaParameters.set(true) }
}

tasks.test { useJUnitPlatform() }
tasks.withType<Test> { maxParallelForks = 1 }
