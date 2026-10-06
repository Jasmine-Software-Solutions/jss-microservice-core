package com.jasminesoftwaresolutions.core.auth.testutil

import com.jasminesoftwaresolutions.core.server.infrastructure.entities.WorkspacesTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.io.File

/**
 * Base class for the integration tests here that need a workspace table —
 * currently `WorkspaceServiceTest`, which runs the shared service against the
 * shared Exposed repository rather than a double, so the tenant bound it pins
 * is the one production uses.
 *
 * A per-module copy, like the products carry: a test source set is not visible
 * to another module's, and this module's tests need the workspace table the
 * server module defines.
 */
abstract class DatabaseTestBase {

    @BeforeEach
    fun resetTables() {
        transaction {
            exec("PRAGMA foreign_keys = OFF")
            SchemaUtils.drop(*TestTables.ALL.reversedArray(), inBatch = false)
        }
        transaction {
            SchemaUtils.createMissingTablesAndColumns(*TestTables.ALL)
            exec("PRAGMA foreign_keys = ON")
        }
    }

    companion object {
        private var initialized = false

        @JvmStatic
        @BeforeAll
        fun setupDatabase() {
            if (!initialized) {
                val dbFile = File("build/microservice-core-auth-test.sqlite")
                dbFile.parentFile.mkdirs()
                dbFile.delete()
                Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
                initialized = true
            }
        }
    }
}

object TestTables {
    val ALL: Array<Table> = arrayOf(WorkspacesTable)
}
