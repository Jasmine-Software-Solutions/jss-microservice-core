package com.jasminesoftwaresolutions.core.domain.events

import java.time.Instant
import java.util.UUID

/**
 * Base type for all domain events emitted by Jasmine microservices.
 *
 * Every event carries the Principal-Who / Action-What / Resource-Where triple
 * that was authorized before the action was performed.
 *
 * [eventType] is the simple class name (e.g. "CandidateCreatedEvent") and serves
 * as the polymorphic discriminator for deserialization.
 *
 * NOTE: not `sealed` — concrete events live in each service's own module, and
 * Kotlin forbids extending sealed types across module boundaries.
 */
interface Event {
    /** Unique identifier for this event instance. Defaults to a random UUID. */
    val eventId: UUID

    /** Polymorphic discriminator — simple class name, e.g. "CandidateCreatedEvent". */
    val eventType: String get() = this::class.simpleName!!

    /** The UUID of the principal (account or client) that performed the action. */
    val principalId: UUID

    /** The service's action constant that was authorized for this event. */
    val action: String

    /** A human-readable label for the resource being acted upon. */
    val resource: String

    /** UTC instant at which the event was produced. */
    val timestamp: Instant
}

/**
 * Dispatches domain events to external systems (e.g. SNS, event bus).
 *
 * Dispatch is fire-and-forget — callers should not block on the result,
 * and failures must be handled internally (e.g. logged) by the implementation.
 */
interface IEventDispatcher {
    /**
     * Publish [event] to the configured event destination.
     *
     * This method should return quickly. Implementations must not throw
     * exceptions that would disrupt the caller's business transaction.
     */
    fun dispatch(event: Event)
}

/**
 * A long-lived background worker that can be started and stopped.
 *
 * Workers consume events (e.g. from SQS) or perform periodic tasks.
 * Implementations should block in [start] until [stop] is called.
 */
interface IWorker {
    /** Begin processing. May block the calling thread. */
    fun start()

    /** Signal the worker to shut down gracefully. */
    fun stop()
}
