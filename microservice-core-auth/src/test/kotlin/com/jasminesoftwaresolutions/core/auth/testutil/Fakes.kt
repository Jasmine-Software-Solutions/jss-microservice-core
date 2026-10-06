package com.jasminesoftwaresolutions.core.auth.testutil

import com.jasminesoftwaresolutions.core.domain.models.IWorkspace
import java.time.Instant
import java.util.UUID

/**
 * The [IWorkspace] double the endpoint and authorization tests build fixtures
 * with.
 *
 * Note the absence of a `workspaceId`: a workspace *is* the partition, so it is
 * scoped by tenant alone. [Instant.EPOCH] keeps `toEpochMilli()` a predictable
 * 0, which the wire-shape assertions rely on.
 */
class FakeWorkspace(
    override val id: UUID = UUID.randomUUID(),
    override var tenantId: UUID = UUID.randomUUID(),
    override var name: String = "Sales",
    override var archived: Boolean = false,
) : IWorkspace {
    override var createdAt: Instant = Instant.EPOCH
}
