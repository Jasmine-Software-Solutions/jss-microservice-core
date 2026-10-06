package com.jasminesoftwaresolutions.core.auth.authorization

import com.jasminesoftwaresolutions.witchwood.evaluation.snapshot.resources.CompiledResourcePath
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Service for evaluating authorization decisions against Witchwood.
 *
 * A port rather than the client itself, so the workspace authorization model
 * can be tested without a gRPC channel — see `WitchwoodEvaluationService` for
 * the adapter.
 */
interface IEvaluationService {
    fun evaluate(
        principalId: UUID,
        action: String,
        resource: CompiledResourcePath,
        context: Map<String, String> = emptyMap(),
    ): CompletableFuture<Boolean>

    fun queryResources(
        principalId: UUID,
        action: String,
    ): CompletableFuture<Set<CompiledResourcePath>>
}
