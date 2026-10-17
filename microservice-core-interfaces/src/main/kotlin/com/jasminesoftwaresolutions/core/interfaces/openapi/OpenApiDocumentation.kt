package com.jasminesoftwaresolutions.core.interfaces.openapi

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.javalin.config.JavalinConfig
import io.javalin.openapi.OpenApiLoader
import io.javalin.openapi.plugin.DefinitionProcessor
import io.javalin.openapi.plugin.OpenApiPlugin

/**
 * A service's own last word on its document, applied after the shared overlay.
 *
 * Receives the finished document — `info`, `servers` and `components` already
 * written from the [OpenApiSpecification] — and mutates it in place. Gson
 * rather than the library's model types so that a service needs no
 * javalin-openapi dependency to shape its own document.
 */
typealias DefinitionStep = (JsonObject) -> Unit

/**
 * Publishes a service's compiled OpenAPI document — the same document, whether
 * a client fetches it over HTTP or reads the file the build commits.
 *
 * The routes themselves are not here. They are `@OpenApi` annotations on the
 * controllers, which the annotation processor turns into
 * `openapi-plugin/openapi-default.json` on the classpath at compile time. This
 * object supplies only what annotations cannot: the document's identity, its
 * servers, and its security schemes. It then makes that envelope *the same* in
 * both places the document is produced, which is the point —
 *
 * - [register] serves it from a running server, via [OpenApiPlugin];
 * - [render] returns it as a string, for a build task to write to a file.
 *
 * Both go through one [DefinitionProcessor] built by [processorFor], so the
 * served document and the committed file cannot drift. That processor is also
 * where the library stops being in charge of the document's shape: the plugin
 * applies its own `info`/`servers`/`security` from configuration *before*
 * handing over, and this overwrites them, because the plugin's builders are
 * reachable only through a live plugin and the build-time path has none.
 */
object OpenApiDocumentation {

    /**
     * The `components.securitySchemes` entry every authenticated route names in
     * `@OpenApiSecurity(name = ...)`.
     */
    const val SESSION_SCHEME_NAME: String = "sessionCookie"

    /**
     * The `components.securitySchemes` entry for the interactive flow, when a
     * service declares one.
     */
    const val OAUTH2_SCHEME_NAME: String = "oauth2"

    /** The version-less name of the document the annotation processor emits. */
    private const val DEFAULT_VERSION: String = "default"

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        // Descriptions are prose and contain `=`, `'` and `<`. Gson's default
        // HTML escaping would turn those into = and ', which is
        // valid JSON that no human wants to read in a committed file.
        .disableHtmlEscaping()
        .create()

    private val objectMapper = ObjectMapper()

    /**
     * Serve the document at [definitionPath] on a running server.
     *
     * @param definitionPath absolute, and served as-is — the plugin mounts it
     *   verbatim. It is the caller's job to put it where its own proxy forwards
     *   traffic.
     */
    fun register(
        config: JavalinConfig,
        specification: OpenApiSpecification,
        definitionPath: String,
        definitionStep: DefinitionStep? = null,
    ) {
        val processor = processorFor(specification, definitionStep)

        config.registerPlugin(OpenApiPlugin { plugin ->
            plugin.withDocumentationPath(definitionPath)
            plugin.withDefinitionConfiguration { _, definition ->
                definition.withDefinitionProcessor(processor)
            }
        })
    }

    /**
     * The same document [register] serves, as a string.
     *
     * Reads the processor's output off the classpath, so it must be called from
     * a classpath that carries it — a build task whose classpath is the
     * module's own runtime classpath, not a library a consumer calls.
     *
     * @throws IllegalStateException if this classpath has no processed document,
     *   which means the annotation processor did not run for this module.
     */
    fun render(
        specification: OpenApiSpecification,
        definitionStep: DefinitionStep? = null,
    ): String {
        val raw = OpenApiLoader().loadOpenApiSchemes()[DEFAULT_VERSION]
            ?: throw IllegalStateException(
                "No OpenAPI document on this classpath at " +
                    "'openapi-plugin/openapi-$DEFAULT_VERSION.json'. The annotation " +
                    "processor is registered on the compiling module alone — check " +
                    "that this task runs against a classpath that includes it."
            )

        return processorFor(specification, definitionStep)
            .process(objectMapper.readTree(raw) as ObjectNode)
    }

    /**
     * The one place the document acquires its envelope, shared by both entry
     * points.
     *
     * Takes the library's Jackson node because that is what [DefinitionProcessor]
     * hands over, converts to Gson immediately, and never looks back — the
     * overlay below is written once, against Gson, and runs identically whether
     * it was reached from a request or from a build task.
     */
    private fun processorFor(
        specification: OpenApiSpecification,
        definitionStep: DefinitionStep?,
    ): DefinitionProcessor = DefinitionProcessor { node ->
        val document = JsonParser.parseString(node.toString()).asJsonObject

        overlay(document, specification)
        definitionStep?.invoke(document)

        gson.toJson(document)
    }

    private fun overlay(document: JsonObject, specification: OpenApiSpecification) {
        document.add("info", JsonObject().apply {
            addProperty("title", specification.title)
            addProperty("version", specification.version)
            addProperty("description", specification.description)
        })

        document.add("servers", JsonArray().apply {
            specification.servers.forEach { server ->
                add(JsonObject().apply {
                    addProperty("url", server.url)
                    addProperty("description", server.description)
                })
            }
        })

        val components = document.getAsJsonObject("components")
            ?: JsonObject().also { document.add("components", it) }
        components.add("securitySchemes", securitySchemes(specification))

        applyFieldNaming(components, specification.fieldNaming)

        // Every operation states its own requirement, so the document declares
        // none globally. A literal empty array rather than an absent key: an
        // absent `security` is indistinguishable from one nobody got round to
        // writing, and a client reads the empty array as the deliberate "no
        // route is authenticated unless it says so" that it is.
        document.add("security", JsonArray())
    }

    /**
     * Respell every derived schema property the way the service's JSON mapper
     * writes it.
     *
     * The compiler reads Kotlin property names, so a schema arrives saying
     * `functionalStage` — while the service's Gson, configured with
     * `LOWER_CASE_WITH_UNDERSCORES` (`GsonFactory`), writes `functional_stage`
     * on both the request and the response. Left alone, the published document
     * would be wrong for **every** multi-word field of every DTO: a client
     * generated from it would send and expect keys the server does not know.
     * That is the one error a client contract cannot survive, and it is
     * invisible from the controller, which is why it is corrected centrally
     * here rather than left to whoever writes the annotations.
     *
     * `required` is respelled with the properties it names, or a required field
     * would stop matching its own schema.
     *
     * **Scope.** `components.schemas` only — the schemas derived from data
     * classes. A response assembled by hand (`ctx.json(mapOf(…))`) has no
     * schema to correct, and a map key keeps whatever spelling its author
     * typed; those are described in prose instead.
     */
    private fun applyFieldNaming(components: JsonObject, fieldNaming: FieldNaming) {
        if (fieldNaming == FieldNaming.AS_DECLARED) return

        val schemas = components.getAsJsonObject("schemas") ?: return

        schemas.entrySet().forEach { (_, node) ->
            val schema = node.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach

            schema.getAsJsonObject("properties")?.let { properties ->
                schema.add("properties", JsonObject().apply {
                    properties.entrySet().forEach { (name, value) -> add(wireName(name), value) }
                })
            }

            schema.getAsJsonArray("required")?.let { required ->
                schema.add("required", JsonArray().apply {
                    required.forEach { add(wireName(it.asString)) }
                })
            }
        }
    }

    /**
     * `Gson`'s `LOWER_CASE_WITH_UNDERSCORES`, reproduced exactly: an underscore
     * before every capital that is not the first character, then lower case.
     *
     * Reproduced rather than approximated because it decides real names —
     * `s3Key` is `s3_key` and `pdfAvailable` is `pdf_available` — and a
     * near-miss would be worse than no correction at all, since it would look
     * deliberate.
     */
    private fun wireName(property: String): String = buildString {
        property.forEach { character ->
            if (character.isUpperCase() && isNotEmpty()) append('_')
            append(character)
        }
    }.lowercase()

    private fun securitySchemes(specification: OpenApiSpecification): JsonObject = JsonObject().apply {
        add(SESSION_SCHEME_NAME, JsonObject().apply {
            addProperty("type", "apiKey")
            addProperty("in", "cookie")
            addProperty("name", specification.sessionCookieName)
            addProperty("description", sessionSchemeDescription(specification))
        })

        // One scheme, every flow the service declares. A caller holds a token
        // from one of them; which one it obtained it by is its own business, and
        // expressing the flows as alternatives is what lets a single operation
        // say "session or bearer" without naming a grant type it cannot check.
        if (specification.authorizationCode == null && specification.clientCredentials == null) return@apply

        add(OAUTH2_SCHEME_NAME, JsonObject().apply {
            addProperty("type", "oauth2")
            add("flows", JsonObject().apply {
                specification.authorizationCode?.let { flow ->
                    add("authorizationCode", JsonObject().apply {
                        addProperty("authorizationUrl", flow.authorizationUrl)
                        addProperty("tokenUrl", flow.tokenUrl)
                        add("scopes", scopes(flow.scopes))
                    })
                }

                specification.clientCredentials?.let { flow ->
                    add("clientCredentials", JsonObject().apply {
                        addProperty("tokenUrl", flow.tokenUrl)
                        add("scopes", scopes(flow.scopes))
                    })
                }
            })
        })
    }

    private fun scopes(scopes: Map<String, String>): JsonObject = JsonObject().apply {
        scopes.forEach { (scope, description) -> addProperty(scope, description) }
    }

    /**
     * What the cookie scheme has to say for itself, which depends on whether the
     * service declared a second credential.
     *
     * A scheme described as the only way in, on an API that also takes a bearer
     * token, is how an integrator concludes the API is browser-only — which is
     * exactly the conclusion this description exists to prevent. Where there is
     * no client-credentials flow the original sentence stands unchanged, because
     * the cookie really is the only credential then.
     */
    private fun sessionSchemeDescription(specification: OpenApiSpecification): String =
        if (specification.clientCredentials == null) {
            "The session cookie set by the sign-in flow. Send it on every " +
                "authenticated request; a request without it is answered 401."
        } else {
            "The session cookie the sign-in flow sets in a browser. One of the two " +
                "credentials an authenticated route accepts — the other is a bearer " +
                "token obtained through `$OAUTH2_SCHEME_NAME`, which a server-to-server " +
                "caller sends instead — and a request carrying neither is answered 401."
        }
}
