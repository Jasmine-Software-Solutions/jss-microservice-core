package com.jasminesoftwaresolutions.core.domain.services

import java.util.UUID

data class TenantWorkspace(
    val tenantId: UUID,
    val tenantName: String,
    val workspaceId: UUID,
    val workspaceName: String,
)
