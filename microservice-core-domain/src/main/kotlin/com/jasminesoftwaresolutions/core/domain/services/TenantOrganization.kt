package com.jasminesoftwaresolutions.core.domain.services

import java.util.UUID

data class TenantOrganization(
    val tenantId: UUID,
    val tenantName: String,
    val organizationId: UUID,
    val organizationName: String,
)
