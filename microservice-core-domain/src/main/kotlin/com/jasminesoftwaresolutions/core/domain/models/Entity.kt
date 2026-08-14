package com.jasminesoftwaresolutions.core.domain.models

import java.time.Instant
import java.util.UUID

/** Base interface for all domain entities that have a UUID primary key. */
interface IIdentified {
    val id: UUID
}

/** Mixin for entities that track creation time. */
interface ICreated {
    var createdAt: Instant
}
