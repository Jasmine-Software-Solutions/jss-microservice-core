package com.jasminesoftwaresolutions.core.server.infrastructure.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class GsonFactoryTest {

    private data class Payload(
        val someField: String,
        val createdAt: Instant,
    )

    @Test
    fun `serializes with lower_case_with_underscores and epoch-millis Instants`() {
        val gson = GsonFactory.create()

        val payload = Payload("hello", Instant.ofEpochMilli(1_700_000_000_123L))
        val json = gson.toJson(payload)

        assertEquals("""{"some_field":"hello","created_at":1700000000123}""", json)
    }

    @Test
    fun `round-trips a payload through JSON`() {
        val gson = GsonFactory.create()

        val original = Payload("hello", Instant.ofEpochMilli(1_700_000_000_123L))
        val back = gson.fromJson(gson.toJson(original), Payload::class.java)

        assertEquals(original, back)
    }

    @Test
    fun `parses raw epoch-millis numbers`() {
        val gson = GsonFactory.create()

        val parsed = gson.fromJson("1700000000123", Instant::class.java)

        assertTrue(parsed == Instant.ofEpochMilli(1_700_000_000_123L))
    }

    /**
     * The shape that used to answer 500 instead of 400: an exception held in a
     * field *declared* as `Exception` — as Javalin's `ValidationError` declares
     * its fourth field. Reflection into `Throwable#detailMessage` throws
     * `JsonIOException` under JDK 26, so this both fails to render and takes the
     * response's status with it. See [GsonFactory]'s KDoc.
     */
    private data class ValidationErrorShape(
        val message: String,
        val value: String,
        val exception: Exception,
    )

    @Test
    fun `renders an exception field by type and message instead of reflecting`() {
        val gson = GsonFactory.create()

        val payload = ValidationErrorShape(
            message = "Invalid UUID",
            value = "not-a-uuid",
            exception = IllegalArgumentException("Invalid UUID string: not-a-uuid"),
        )

        assertEquals(
            """{"message":"Invalid UUID","value":"not-a-uuid",""" +
                """"exception":{"type":"IllegalArgumentException",""" +
                """"message":"Invalid UUID string: not-a-uuid"}}""",
            gson.toJson(payload),
        )
    }
}
