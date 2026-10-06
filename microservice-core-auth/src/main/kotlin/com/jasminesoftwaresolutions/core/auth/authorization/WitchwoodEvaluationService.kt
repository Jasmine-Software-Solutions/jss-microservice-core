package com.jasminesoftwaresolutions.core.auth.authorization

import com.jasminesoftwaresolutions.witchwood.client.WitchwoodClient
import com.jasminesoftwaresolutions.witchwood.evaluation.snapshot.resources.CompiledResourcePath
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * [IEvaluationService] over the concrete Witchwood client.
 *
 * `AutoCloseable by client` so the adapter's lifetime is visibly the client's:
 * a service that closes the adapter closes its gRPC channel, which is what the
 * composition root already does explicitly when it stops.
 */
class WitchwoodEvaluationService(
    val client: WitchwoodClient,
) : IEvaluationService, AutoCloseable by client {

    override fun evaluate(
        principalId: UUID,
        action: String,
        resource: CompiledResourcePath,
        context: Map<String, String>,
    ): CompletableFuture<Boolean> = client.evaluate(principalId, action, resource, context)

    override fun queryResources(
        principalId: UUID,
        action: String,
    ): CompletableFuture<Set<CompiledResourcePath>> = client.queryResources(principalId, action)
}
