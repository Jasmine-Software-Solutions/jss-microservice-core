package com.jasminesoftwaresolutions.core.domain.repositories

import com.jasminesoftwaresolutions.core.domain.models.IIdentified
import java.util.UUID

interface IRepository<T> {
    fun create(function: T.() -> Unit): T
    fun update(entity: T, function: T.() -> Unit)
    fun delete(entity: T)
    fun install()
}

interface IIdentifiedRepository<T : IIdentified> : IRepository<T> {
    fun findById(id: UUID): T?
    override fun create(function: T.() -> Unit): T = create(UUID.randomUUID(), function)
    fun create(id: UUID, function: T.() -> Unit): T
}
