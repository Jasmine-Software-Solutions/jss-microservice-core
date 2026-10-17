package com.jasminesoftwaresolutions.core.auth.testutil

import com.jasminesoftwaresolutions.core.auth.authorization.IEvaluationService
import com.jasminesoftwaresolutions.witchwood.evaluation.snapshot.resources.CompiledResourcePath
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * An [IEvaluationService] that answers from a fixed set of grants and records
 * what it was asked.
 *
 * The seam both authorization tests use, and the reason neither needs a
 * `WitchwoodClient` or the teardown one would require: [SessionAuthService]
 * depends on this interface, so the whole visibility and gate model can be
 * driven from strings.
 *
 * A grant is `key(action, resource)` — the action and the built path, which is
 * exactly the pair a policy in Witchwood's database matches on. Building them
 * through [TestResources] and the config's `tenantPath` rather than by hand
 * keeps both sides of an assertion moving together, which is why one test in
 * `WorkspaceAuthorizationModelTest` spells a path out literally instead.
 *
 * Recording is as much the point as answering: a route's *resource* is the thing
 * most easily transposed (a rename gated on the tenant path would still answer
 * 204 for every happy-path request), so `calls` is what those tests assert on.
 */
class RecordingEvaluation(private val granted: Set<String>) : IEvaluationService {

    val calls = mutableListOf<Pair<String, CompiledResourcePath>>()

    var queryCalls = 0
        private set

    override fun evaluate(
        principalId: UUID,
        action: String,
        resource: CompiledResourcePath,
        context: Map<String, String>,
    ): CompletableFuture<Boolean> {
        calls += action to resource
        return CompletableFuture.completedFuture(key(action, resource) in granted)
    }

    override fun queryResources(
        principalId: UUID,
        action: String,
    ): CompletableFuture<Set<CompiledResourcePath>> {
        queryCalls++
        return CompletableFuture.completedFuture(emptySet())
    }

    companion object {
        fun key(action: String, resource: CompiledResourcePath) = "$action @ $resource"
    }
}
