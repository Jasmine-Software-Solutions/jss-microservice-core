package com.jasminesoftwaresolutions.core.domain.services

import com.jasminesoftwaresolutions.core.domain.models.IWorkspace
import java.util.UUID

/**
 * Workspace lifecycle. `principalId` leads every mutating signature, matching
 * the convention of the services that implement this.
 *
 * Mutations are addressed by (tenantId, workspaceId) rather than by workspaceId
 * alone, so the service can enforce the tenant bound itself instead of trusting
 * that its caller already did.
 */
interface IWorkspaceService {
    fun createWorkspace(principalId: UUID, tenantId: UUID, name: String): IWorkspace

    fun renameWorkspace(principalId: UUID, tenantId: UUID, workspaceId: UUID, name: String)

    fun archiveWorkspace(principalId: UUID, tenantId: UUID, workspaceId: UUID)

    fun listWorkspaces(tenantId: UUID): List<IWorkspace>
}
