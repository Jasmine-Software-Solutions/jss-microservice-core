package com.jasminesoftwaresolutions.core.auth.authorization

import com.jasminesoftwaresolutions.core.domain.services.TenantWorkspace
import com.jasminesoftwaresolutions.witchwood.evaluation.snapshot.resources.CompiledResourcePath
import java.util.UUID

/**
 * The authenticated principal **and the workspace it is working in**, as
 * resolved for one request.
 *
 * Lives in this module rather than in a service's domain because it is the
 * shape controllers are written against, and the controllers are shared now
 * too: `Context.requireAuth()` returns this type, and every staff handler
 * evaluates its action against the `(tenantId, workspaceId)` pair carried here.
 *
 * [workspaces] is the same set the picker lists — resolved lazily, because most
 * requests never read it and it costs a tenant list plus one evaluation per
 * candidate workspace.
 */
interface WorkspaceAuthentication {
    val principalId: UUID
    val tenantId: UUID
    val workspaceId: UUID
    val accessToken: String
    val workspaces: List<TenantWorkspace>

    fun evaluate(action: String, resource: CompiledResourcePath): Boolean

    fun queryResources(action: String): Set<CompiledResourcePath>
}
