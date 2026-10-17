package com.jasminesoftwaresolutions.core.interfaces.openapi

/**
 * What a service declares about its own API document — the parts a document
 * cannot read off the controllers.
 *
 * The routes come from `@OpenApi` annotations and are compiled into the
 * document by the annotation processor. Everything here is the envelope around
 * them: who the API is, where it is served, and how a client authenticates.
 * [OpenApiDocumentation] is the only thing that consumes this.
 *
 * [sessionCookieName] is a parameter rather than a constant because the cookie
 * is the *session layer's* choice, not the document's — it is read from the
 * same place the server reads it, so a rename cannot leave the document
 * describing a cookie nothing sets.
 */
data class OpenApiSpecification(
    val title: String,
    val version: String,
    val description: String,
    val servers: List<OpenApiServer>,

    /**
     * The name of the cookie carrying the session, as it appears on the wire.
     * Published as the `sessionCookie` security scheme's `name`.
     */
    val sessionCookieName: String,

    /**
     * The interactive OAuth2 flow a client uses to obtain a session. Omitted by
     * a service whose clients never authenticate that way.
     */
    val authorizationCode: AuthorizationCode? = null,

    /**
     * The non-interactive flow a **service** uses to obtain the token it
     * presents as `Authorization: Bearer`. Omitted by a service with no
     * server-to-server callers.
     *
     * Declaring it is what makes the bearer credential visible at all. The
     * session cookie is one way to authenticate and the bearer token is the
     * other — `SessionAuthService.authenticate` reads the header before the
     * cookie, so every operation that accepts a session accepts a client
     * token in its place — and a document that names only the cookie describes
     * the browser half of a two-credential API. The token endpoint is Identity's
     * in either case; what a service adds here is the grant type its callers use
     * and the scopes they ask for.
     */
    val clientCredentials: ClientCredentials? = null,

    /**
     * How this service's JSON mapper spells a property on the wire.
     *
     * The compiler reads Kotlin property names; the wire carries whatever the
     * service's `Gson` is configured to write. When those differ the document is
     * wrong in the way that matters most — a client generated from it calls
     * `functionalStage` and the server is reading `functional_stage` — so this
     * is stated rather than assumed. See [FieldNaming].
     */
    val fieldNaming: FieldNaming = FieldNaming.AS_DECLARED,
)

/**
 * A field-naming policy, mirroring the JSON mapper's own.
 *
 * Only the two spellings in use are named here. An unrecognised policy is a
 * compile error rather than a silently wrong document, which is the point of
 * enumerating rather than passing a conversion function.
 */
enum class FieldNaming {
    /** A property is written exactly as the Kotlin code spells it. */
    AS_DECLARED,

    /**
     * `Gson`'s `LOWER_CASE_WITH_UNDERSCORES`: every capital gains a preceding
     * underscore and the whole is lower-cased, so `functionalStage` is written
     * `functional_stage` and `createdAt` as `created_at`.
     *
     * Applies to properties of the **data classes** the document derives
     * schemas from. A response the handler assembles by hand — `ctx.json(mapOf(
     * … ))` — keeps whatever spelling its author typed, because Gson's policy
     * governs reflected fields and not map keys. That is why both spellings
     * exist in this API, and why a hand-built key is described in prose rather
     * than derived.
     */
    LOWER_CASE_WITH_UNDERSCORES,
}

/** One entry of the document's `servers` array. */
data class OpenApiServer(val url: String, val description: String)

/**
 * An OAuth2 authorization-code flow, published under the `oauth2` scheme.
 *
 * [scopes] maps scope name to the sentence describing it — the text a client
 * integrator reads when deciding what to request.
 */
data class AuthorizationCode(
    val authorizationUrl: String,
    val tokenUrl: String,
    val scopes: Map<String, String>,
)

/**
 * An OAuth2 client-credentials flow, published under the `oauth2` scheme beside
 * [AuthorizationCode] when a service has both.
 *
 * Carries its own [tokenUrl] even where it is the same endpoint the interactive
 * flow uses — OpenAPI gives every flow its own, and a reader of the published
 * document should not have to know the two happen to coincide.
 */
data class ClientCredentials(
    val tokenUrl: String,
    val scopes: Map<String, String>,
)
