package com.jasminesoftwaresolutions.core.server.infrastructure.testutil

import com.jasminesoftwaresolutions.core.server.infrastructure.entities.WorkspacesTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.util.UUID

/**
 * Base class for the shared repository integration tests.
 *
 * Uses a fresh SQLite file per test run. Tables are dropped and recreated
 * before each test to guarantee hermetic isolation.
 *
 * A per-module copy rather than a shared fixture: a test source set is not
 * visible to another module's, and the products each carry their own copy of
 * this class for the same reason. The table list below is what differs.
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

    protected fun newTenantId(): UUID = UUID.randomUUID()
    protected fun newWorkspaceId(): UUID = UUID.randomUUID()

    companion object {
        private var initialized = false

        @JvmStatic
        @BeforeAll
        fun setupDatabase() {
            if (!initialized) {
                val dbFile = File("build/microservice-core-server-test.sqlite")
                dbFile.parentFile.mkdirs()
                dbFile.delete()
                Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
                initialized = true
            }
        }
    }
}

/**
 * The tables these tests exercise.
 *
 * [WorkspacesTable] is the shared table under test. [WidgetsTable] is a
 * test-only partitioned table — see `WorkspaceScopedFindByIdTest` for why the
 * scoped single-row read is pinned against a stand-in here rather than against
 * one of the products' aggregates.
 */
object TestTables {
    val ALL: Array<Table> = arrayOf(WorkspacesTable, WidgetsTable)
}
