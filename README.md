# jss-microservice-core

Shared Kotlin library for the Jasmine microservices (hiring_new, forms, background, …).
Extracts the code that every service had duplicated: domain kernel (events, entity
contracts, repositories), server infrastructure (Exposed delegates, Gson factory,
SNS event dispatcher, Hikari/Exposed wiring), and the Javalin Gson mapper.

| Module / artifact | Package root | Contents |
|---|---|---|
| `microservice-core-domain` | `com.jasminesoftwaresolutions.core.domain` | `events/Events.kt` (Event, IEventDispatcher, IWorker), `models/Entity.kt` (IIdentified, ICreated), `repositories/Repositories.kt` (IRepository, IIdentifiedRepository), `services/TenantWorkspace.kt`. Zero dependencies. |
| `microservice-core-server` | `com.jasminesoftwaresolutions.core.server` | `infrastructure/util/EntityDelegates.kt`, `infrastructure/util/EnvUtil.kt`, `infrastructure/util/GsonFactory.kt`, `infrastructure/services/SnsEventDispatcher.kt`, `infrastructure/db/DatabaseFactory.kt`, `infrastructure/repositories/ExposedEntityRepository.kt` |
| `microservice-core-interfaces` | `com.jasminesoftwaresolutions.core.interfaces` | `GsonJsonMapper.kt` |

Group `com.jasminesoftwaresolutions`, version `1.0.0-SNAPSHOT`, published to the
CodeArtifact repository `microservice-core` in domain `jasmine-software-solutions`
(owner 435238036697) and to Maven Local.

## Consuming from a microservice

Add the CodeArtifact repository (plus your token) to the service's repositories block:

```kotlin
repositories {
    mavenCentral()
    mavenLocal() // stays FIRST so local SNAPSHOT builds win
    maven {
        url = uri("https://jasmine-software-solutions-435238036697.d.codeartifact.us-east-1.amazonaws.com/maven/microservice-core/")
        credentials {
            username = "aws"
            password = System.getenv("CODEARTIFACT_AUTH_TOKEN")
        }
    }
}
```

Then per module:

```kotlin
// domain/build.gradle.kts — must be `api` (service contracts expose shared types)
dependencies {
    api("com.jasminesoftwaresolutions:microservice-core-domain:1.0.0-SNAPSHOT")
}
// server/build.gradle.kts
dependencies {
    implementation("com.jasminesoftwaresolutions:microservice-core-server:1.0.0-SNAPSHOT")
}
// interfaces/build.gradle.kts
dependencies {
    implementation("com.jasminesoftwaresolutions:microservice-core-interfaces:1.0.0-SNAPSHOT")
}
```

**SNAPSHOT staleness:** after republishing the library, consumers must run
`./gradlew --refresh-dependencies build`. If `CODEARTIFACT_AUTH_TOKEN` is not set
(e.g. a fresh machine), publish to Maven Local first:

```sh
cd jss-microservice-core
./gradlew publishToMavenLocal
```

## Publishing

```sh
./gradlew publishToMavenLocal          # local development
./gradlew publish                      # CodeArtifact (needs CODEARTIFACT_AUTH_TOKEN)
```

CI publishes on `workflow_dispatch` and on `v*` tags via `.github/workflows/publish-codeartifact.yml`
(OIDC role assumption — same as the `id` service). The GitHub repo requires the same
variables and secret as `Jasmine-Software-Solutions/id`: repo variables
`CODEARTIFACT_DOMAIN`, `CODEARTIFACT_DOMAIN_OWNER`, `CODEARTIFACT_REGION`,
`CODEARTIFACT_PLUGIN_REPOSITORY`, `CODEARTIFACT_MAVEN_REPOSITORY`, and repo secret
`AWS_ROLE_TO_ASSUME` (whose trust/role policy must allow publishing to the
`microservice-core` repository).

## Development

```sh
./gradlew build    # builds all modules + runs tests
```

Kotlin 2.4.10, Gradle 9.6.1, toolchain 26, Exposed 0.40.1, Javalin 6.6.0, Gson 2.13.2,
AWS BOM 2.29.52 — the common workspace version matrix. The JDBC driver is deliberately
NOT a dependency of `microservice-core-server`: each service keeps its own
postgres/sqlite driver.
