package com.jasminesoftwaresolutions.core.server.infrastructure.util

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.time.Instant

/**
 * The canonical project Gson instance shared by all microservices:
 * LOWER_CASE_WITH_UNDERSCORES field naming and Instant serialized
 * as epoch milliseconds.
 *
 * ## Why [Throwable] is rendered by hand
 *
 * A malformed path parameter must answer 400, and Javalin does produce one: its
 * validator wraps the `IllegalArgumentException` from `UUID.fromString` in a
 * `ValidationError` — whose fourth field is the caught exception — and
 * `Validation.addValidationExceptionMapper` serialises that through whichever
 * [com.google.gson.Gson] the service registered.
 *
 * Under JDK 26 that serialisation is what fails. Gson reflects into
 * `java.lang.Throwable#detailMessage`, the module system refuses to open it, and
 * the `JsonIOException` is thrown *while rendering the 400*. Javalin then falls
 * back to its `HttpResponseExceptionMapper`, which builds its JSON by hand and
 * never consults this mapper — so the caller gets
 * `{"title":"Server Error","status":500,"details":{}}` and the reason is lost.
 * No handler-level fix reaches this: an `app.exception(...)` handler's own
 * `ctx.json(...)` goes through the same Gson and fails the same way.
 *
 * Rendering type and message is enough for the body to carry the reason. It must
 * be a *hierarchy* adapter: the field is declared `Exception`, so an adapter
 * registered for `Throwable` exactly would not match it.
 */
object GsonFactory {
    fun create(): Gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerTypeAdapter(Instant::class.java, JsonSerializer<Instant> { src, _, _ ->
            JsonPrimitive(src.toEpochMilli())
        })
        .registerTypeAdapter(Instant::class.java, JsonDeserializer<Instant> { json, _, _ ->
            Instant.ofEpochMilli(json.asLong)
        })
        .registerTypeHierarchyAdapter(Throwable::class.java, JsonSerializer<Throwable> { src, _, _ ->
            JsonObject().apply {
                addProperty("type", src::class.java.simpleName)
                addProperty("message", src.message)
            }
        })
        .create()
}
