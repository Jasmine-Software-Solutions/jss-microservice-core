package com.jasminesoftwaresolutions.core.server.infrastructure.services

import com.google.gson.Gson
import com.jasminesoftwaresolutions.core.domain.events.Event
import com.jasminesoftwaresolutions.core.domain.events.IEventDispatcher
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest

/**
 * Publishes domain events to an AWS SNS standard topic.
 *
 * Serialization uses the project [Gson] instance so events are output
 * in the same lower_case_with_underscores convention. Each published
 * message includes SNS message attributes for downstream SQS filtering.
 *
 * Service-specific attributes (e.g. forms' `resource_kind`) are supplied
 * via [extraMessageAttributes] and merged after the standard attributes.
 */
class SnsEventDispatcher(
    private val gson: Gson,
    topicArn: String = "",
    region: String = "us-east-1",
    accessKey: String = "",
    secretKey: String = "",
    awsProfile: String = "",
    endpointOverride: String? = null,
    private val client: SnsClient = createSnsClient(region, accessKey, secretKey, awsProfile, endpointOverride),
    private val extraMessageAttributes: (Event) -> Map<String, String> = { emptyMap() },
) : IEventDispatcher, AutoCloseable by client {

    private val topic = topicArn.ifBlank {
        log.warn("SNS_TOPIC_ARN not configured — events will be logged but not published")
        null
    }

    override fun dispatch(event: Event) {
        val target = topic ?: run {
            log.info("Event not published (no topic): class=${event::class.simpleName} principal=${event.principalId}")
            return
        }

        val body = try {
            gson.toJson(event)
        } catch (e: Exception) {
            log.error("Failed to serialize event ${event::class.simpleName}: ${e.message}", e)
            return
        }

        val request = PublishRequest.builder()
            .topicArn(target)
            .message(body)
            .messageAttributes(
                mapOf(
                    "event_type" to stringAttr(event::class.simpleName ?: "Event"),
                    "action" to stringAttr(event.action),
                    "principal_id" to stringAttr(event.principalId.toString()),
                ) + extraMessageAttributes(event).mapValues { (_, value) -> stringAttr(value) }
            )
            .build()

        try {
            val response = client.publish(request)
            log.info("Published {} to SNS: seq={}", event::class.simpleName, response.sequenceNumber())
        } catch (e: Exception) {
            log.error("Failed to publish event ${event::class.simpleName} to SNS: ${e.message}", e)
        }
    }

    private fun stringAttr(value: String) = MessageAttributeValue.builder()
        .dataType("String")
        .stringValue(value)
        .build()

    companion object {
        private val log = LoggerFactory.getLogger(SnsEventDispatcher::class.java)

        private fun createSnsClient(
            region: String,
            accessKey: String,
            secretKey: String,
            awsProfile: String,
            endpointOverride: String?,
        ): SnsClient {
            val builder = SnsClient.builder().region(Region.of(region))
            endpointOverride?.let { builder.endpointOverride(java.net.URI.create(it)) }
            when {
                accessKey.isNotBlank() && secretKey.isNotBlank() ->
                    builder.credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
                    )
                awsProfile.isNotBlank() && awsProfile != "default" ->
                    builder.credentialsProvider(ProfileCredentialsProvider.create(awsProfile))
            }
            return builder.build()
        }
    }
}
