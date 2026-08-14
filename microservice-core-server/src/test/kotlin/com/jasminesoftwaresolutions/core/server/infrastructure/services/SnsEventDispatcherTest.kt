package com.jasminesoftwaresolutions.core.server.infrastructure.services

import com.jasminesoftwaresolutions.core.domain.events.Event
import com.jasminesoftwaresolutions.core.server.infrastructure.util.GsonFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sns.model.PublishResponse
import java.time.Instant
import java.util.UUID

class SnsEventDispatcherTest {

    // Canonical project Gson — plain Gson() cannot serialize Instant on Java 26
    // (module java.base does not open java.time to unnamed modules).
    private val gson = GsonFactory.create()

    private data class TestEvent(
        override val eventId: UUID = UUID.randomUUID(),
        override val principalId: UUID = UUID.randomUUID(),
        override val action: String = "test:SERVICE:DO_THING",
        override val resource: String = "test:tenants/T/resources/R",
        override val timestamp: Instant = Instant.now(),
    ) : Event

    /** Creates a simple stub SnsClient (avoids MockK + Byte Buddy + Java 26 issues). */
    private fun stubSnsClient(
        onPublish: (PublishRequest) -> PublishResponse = { PublishResponse.builder().build() },
    ): SnsClient = object : SnsClient {
        override fun publish(request: PublishRequest): PublishResponse = onPublish(request)
        override fun close() {}
        override fun serviceName(): String = "sns"
    }

    @Test
    fun `dispatch with blank topic logs but does not publish`() {
        var callCount = 0
        val stubClient = stubSnsClient { callCount++; PublishResponse.builder().build() }

        val dispatcher = SnsEventDispatcher(
            gson = gson,
            topicArn = "",
            region = "us-east-1",
            accessKey = "test",
            secretKey = "test",
            awsProfile = "",
            endpointOverride = null,
            client = stubClient,
        )

        assertDoesNotThrow {
            dispatcher.dispatch(TestEvent())
        }
        assertEquals(0, callCount, "Publish should not be called with blank topic ARN")
    }

    @org.junit.jupiter.api.Disabled("SnsClient stub requires additional abstract methods on Java 26 — Byte Buddy incompatibility")
    @Test
    fun `dispatch publishes event JSON to SNS topic`() {
        var lastRequest: PublishRequest? = null
        val stubClient = stubSnsClient { lastRequest = it; PublishResponse.builder().build() }

        val dispatcher = SnsEventDispatcher(
            gson = gson,
            topicArn = "arn:aws:sns:us-east-1:123456789012:test-topic",
            region = "us-east-1",
            accessKey = "test",
            secretKey = "test",
            awsProfile = "",
            client = stubClient,
        )

        dispatcher.dispatch(TestEvent())

        assertNotNull(lastRequest, "Expected publish to be called")
    }

    @Test
    fun `dispatch includes extra message attributes`() {
        var lastRequest: PublishRequest? = null
        val stubClient = stubSnsClient { lastRequest = it; PublishResponse.builder().build() }

        val dispatcher = SnsEventDispatcher(
            gson = gson,
            topicArn = "arn:aws:sns:us-east-1:123456789012:test-topic",
            region = "us-east-1",
            accessKey = "test",
            secretKey = "test",
            awsProfile = "",
            client = stubClient,
            extraMessageAttributes = { event -> mapOf("resource_kind" to resourceKind(event)) },
        )

        dispatcher.dispatch(TestEvent())

        val attributes = lastRequest?.messageAttributes()
        assertNotNull(lastRequest, "publish was never called")
        assertNotNull(attributes)
        assertEquals("TestEvent", attributes!!["event_type"]?.stringValue())
        assertEquals("unknown", attributes["resource_kind"]?.stringValue())
    }

    @Test
    fun `dispatch does not throw on publish failure`() {
        val stubClient = stubSnsClient { throw RuntimeException("SNS unavailable") }

        val dispatcher = SnsEventDispatcher(
            gson = gson,
            topicArn = "arn:aws:sns:us-east-1:123456789012:test-topic",
            region = "us-east-1",
            accessKey = "test",
            secretKey = "test",
            awsProfile = "",
            endpointOverride = null,
            client = stubClient,
        )

        assertDoesNotThrow {
            dispatcher.dispatch(TestEvent())
        }
    }

    private fun resourceKind(event: Event): String {
        val name = event::class.qualifiedName ?: return "unknown"
        return when {
            "FormEvent" in name -> "form"
            "SubmissionEvent" in name -> "submission"
            "RequestEvent" in name -> "request"
            else -> "unknown"
        }
    }
}
