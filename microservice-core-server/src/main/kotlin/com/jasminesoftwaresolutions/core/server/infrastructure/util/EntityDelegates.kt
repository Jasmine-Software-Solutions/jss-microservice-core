package com.jasminesoftwaresolutions.core.server.infrastructure.util

import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty

/**
 * A property delegate that redirects reads and writes to a mutable property
 * on a target entity via kotlin.reflect. Use when the adapter's field type
 * matches the entity's field type exactly.
 *
 * Example:
 * ```
 * override var name: String by entityPropertyDelegate(entity, CandidateEntity::name)
 * ```
 */
inline fun <reified E, reified V> entityPropertyDelegate(
    entity: E,
    property: KMutableProperty1<E, V>
): ReadWriteProperty<Any?, V> = object : ReadWriteProperty<Any?, V> {
    override fun getValue(thisRef: Any?, p: KProperty<*>): V = property.get(entity)
    override fun setValue(thisRef: Any?, p: KProperty<*>, value: V) = property.set(entity, value)
}

/**
 * A property delegate that redirects reads and writes to a mutable property
 * on a target entity, transforming the value in both directions.
 * Useful for mapping DAO `Long` timestamps to domain `Instant` fields.
 *
 * Example:
 * ```
 * override val createdAt: Instant by entityPropertyDelegate(
 *     entity, CandidateEntity::createdAt,
 *     fromRaw = Instant::ofEpochMilli,
 *     toRaw = Instant::toEpochMilli
 * )
 * ```
 */
inline fun <reified E, reified Raw, reified V> entityPropertyDelegate(
    entity: E,
    property: KMutableProperty1<E, Raw>,
    noinline fromRaw: (Raw) -> V,
    noinline toRaw: (V) -> Raw
): ReadWriteProperty<Any?, V> = object : ReadWriteProperty<Any?, V> {
    override fun getValue(thisRef: Any?, p: KProperty<*>): V = fromRaw(property.get(entity))
    override fun setValue(thisRef: Any?, p: KProperty<*>, value: V) = property.set(entity, toRaw(value))
}

/**
 * Lazy, cached property delegate for entity references (parent/child relations).
 * The load lambda runs inside a new transaction on first read and the result
 * is cached. The optional save lambda writes the value back to the backing
 * entity and refreshes the cache; it runs WITHOUT a transaction, so it must
 * only be invoked from within one (always the case: setters are called from
 * repository create/update lambdas).
 */
fun <T> lazyEntityReference(
    load: () -> T,
    save: ((T) -> Unit)? = null,
): ReadWriteProperty<Any?, T> {
    var cached: T? = null
    var loaded = false
    return object : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T {
            if (!loaded) {
                cached = transaction { load() }
                loaded = true
            }
            @Suppress("UNCHECKED_CAST")
            return cached as T
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            save?.invoke(value)
            cached = value
            loaded = true
        }
    }
}
