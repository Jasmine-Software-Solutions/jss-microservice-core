package com.jasminesoftwaresolutions.core.server.infrastructure.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import javax.sql.DataSource

/**
 * Shared Hikari/Exposed wiring for microservice composition roots.
 *
 * [dataSource] builds a Hikari pool from a configure lambda — services
 * pass their own driver/URL/pool settings (e.g. background's connection
 * liveness settings) and keep their own JDBC driver on the classpath.
 *
 * [connect] attaches the pool to Exposed with the standard
 * `keepLoadedReferencesOutOfTransaction` config, plus any extra config.
 */
object DatabaseFactory {

    fun dataSource(configure: HikariConfig.() -> Unit): HikariDataSource =
        HikariDataSource(HikariConfig().apply(configure))

    fun connect(dataSource: DataSource, configure: DatabaseConfig.Builder.() -> Unit = {}): Database =
        Database.connect(dataSource, databaseConfig = DatabaseConfig {
            keepLoadedReferencesOutOfTransaction = true
            configure()
        })
}
