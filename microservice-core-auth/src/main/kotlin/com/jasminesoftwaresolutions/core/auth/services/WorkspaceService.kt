package com.jasminesoftwaresolutions.core.auth.services

import com.jasminesoftwaresolutions.core.domain.models.IWorkspace
import com.jasminesoftwaresolutions.core.domain.repositories.IWorkspaceRepository
import com.jasminesoftwaresolutions.core.domain.services.IWorkspaceService
import io.javalin.http.NotFoundResponse
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * The one [IWorkspaceService] implementation, shared by every service.
 *
 * It throws Javalin's [NotFoundResponse] rather than a domain exception, which
 * is why it lives in this module and not in `microservice-core-server`: the
 * server module stays Javalin-free so services that never mount an HTTP API
 * (workers) can depend on it. Here, Javalin is already a dependency and a 404
 * is the contract every caller implements — the alternative, a domain
 * exception mapped per service, buys isolation at the cost of a mapper each
 * service must remember to register, and a missed registration turns a 404
 * into a 500.
 */
class WorkspaceService(
    private val workspaceRepository: IWorkspaceRepository,
) : IWorkspaceService {

    override fun createWorkspace(principalId: UUID, tenantId: UUID, name: String): IWorkspace = transaction {
        workspaceRepository.create {
            this.tenantId = tenantId
            this.name = name
        }
    }

    override fun renameWorkspace(principalId: UUID, tenantId: UUID, workspaceId: UUID, name: String) = transaction {
        // Scoped by tenant, not just id: the caller's authorization was checked
        // against (tenantId, workspaceId), so the lookup must agree with it.
        val workspace = workspaceRepository.findById(workspaceId)
            ?.takeIf { it.tenantId == tenantId }
            ?: throw NotFoundResponse("Workspace not found")
        workspaceRepository.update(workspace) { this.name = name }
    }

    override fun archiveWorkspace(principalId: UUID, tenantId: UUID, workspaceId: UUID) = transaction {
        val workspace = workspaceRepository.findById(workspaceId)
            ?.takeIf { it.tenantId == tenantId }
            ?: throw NotFoundResponse("Workspace not found")
        workspaceRepository.update(workspace) { this.archived = true }
    }

    override fun listWorkspaces(tenantId: UUID): List<IWorkspace> = transaction {
        workspaceRepository.findByTenant(tenantId)
    }
}
