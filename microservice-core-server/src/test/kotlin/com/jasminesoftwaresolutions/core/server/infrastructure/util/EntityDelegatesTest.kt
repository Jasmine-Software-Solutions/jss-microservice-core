package com.jasminesoftwaresolutions.core.server.infrastructure.util

import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntityDelegatesTest {

    class FakeEntity {
        var name: String = "initial"
        var createdAt: Long = 0L
    }

    private class Holder(val entity: FakeEntity) {
        var name: String by entityPropertyDelegate(entity, FakeEntity::name)
        var createdAt: Instant by entityPropertyDelegate(
            entity, FakeEntity::createdAt,
            fromRaw = Instant::ofEpochMilli,
            toRaw = Instant::toEpochMilli,
        )
    }

    private class LazyHolder {
        var loaded: Int = 0
        var saved: Int = 0
        var value: String by lazyEntityReference(
            load = { loaded++; "loaded" },
            save = { saved++ },
        )
    }

    init {
        // lazyEntityReference runs its load lambda inside a transaction,
        // so the test needs an Exposed default database.
        Database.connect("jdbc:sqlite:file:entitydelegates?mode=memory&cache=shared", driver = "org.sqlite.JDBC")
    }

    @Test
    fun `direct delegate reads and writes through to the entity`() {
        val holder = Holder(FakeEntity())

        assertEquals("initial", holder.name)
        holder.name = "updated"
        assertEquals("updated", holder.name)
        assertEquals("updated", holder.entity.name)
    }

    @Test
    fun `transforming delegate maps values in both directions`() {
        val holder = Holder(FakeEntity())

        holder.createdAt = Instant.ofEpochMilli(1_700_000_000_123L)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_123L), holder.createdAt)
        assertEquals(1_700_000_000_123L, holder.entity.createdAt)
    }

    @Test
    fun `lazy reference loads once and caches`() {
        val holder = LazyHolder()

        assertEquals("loaded", holder.value)
        assertEquals("loaded", holder.value)
        assertEquals(1, holder.loaded, "load lambda should run only once")
    }

    @Test
    fun `lazy reference save writes back and refreshes the cache`() {
        val holder = LazyHolder()
        holder.value // trigger the load

        holder.value = "replaced"

        assertEquals(1, holder.saved)
        assertEquals("replaced", holder.value)
        assertEquals(1, holder.loaded, "cache refresh must not reload")
    }
}
