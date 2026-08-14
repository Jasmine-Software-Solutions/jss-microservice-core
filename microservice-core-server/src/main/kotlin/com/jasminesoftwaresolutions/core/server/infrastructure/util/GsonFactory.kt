package com.jasminesoftwaresolutions.core.server.infrastructure.util

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.time.Instant

/**
 * The canonical project Gson instance shared by all microservices:
 * LOWER_CASE_WITH_UNDERSCORES field naming and Instant serialized
 * as epoch milliseconds.
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
        .create()
}
