package com.jasminesoftwaresolutions.core.server.infrastructure.repositories.models

import com.jasminesoftwaresolutions.core.domain.models.IWorkspace
import com.jasminesoftwaresolutions.core.server.infrastructure.entities.WorkspaceEntity
import com.jasminesoftwaresolutions.core.server.infrastructure.util.entityPropertyDelegate
import java.time.Instant
import java.util.UUID

class WorkspaceAdapter(val entity: WorkspaceEntity) : IWorkspace {
    override val id: UUID get() = entity.id.value

    override var tenantId: UUID by entityPropertyDelegate(entity, WorkspaceEntity::tenantId)
    override var name: String by entityPropertyDelegate(entity, WorkspaceEntity::name)
    override var archived: Boolean by entityPropertyDelegate(entity, WorkspaceEntity::archived)

    // Hand-rolled rather than delegated: the column is a BIGINT epoch-millis and
    // the model is an Instant, so no generic delegate covers it. Same shape as
    // the other adapters.
    override var createdAt: Instant
        get() = Instant.ofEpochMilli(entity.createdAt)
        set(value) {
            entity.createdAt = value.toEpochMilli()
        }
}
