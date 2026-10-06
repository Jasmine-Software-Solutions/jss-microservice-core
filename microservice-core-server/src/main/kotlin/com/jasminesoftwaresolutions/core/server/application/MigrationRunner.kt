package com.jasminesoftwaresolutions.core.server.application

import org.flywaydb.core.Flyway
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Runs Flyway migrations **against Postgres only**.
 *
 * A service has no migration tool of its own: its `installXSchema()` calls
 * `SchemaUtils.createMissingTablesAndColumns(...)` over every table it owns, and
 * Exposed can *add* missing columns but never rename or drop one. That is
 * sufficient while the schema only grows — but a repartitioning, a rename, or a
 * `NOT NULL` column on a populated table cannot be expressed that way, so the
 * destructive step lives in Flyway and Exposed remains responsible for creating
 * the schema from nothing.
 *
 * The Postgres-only guard is deliberate, not incidental. SQLite gets its schema
 * purely from Exposed (see the service's `Env.DATABASE_DRIVER` and its test
 * harness), and a single `.sql` file satisfying both engines could not use
 * `TRUNCATE ... CASCADE`, `DROP COLUMN`, or `ADD COLUMN ... NOT NULL
 * CONSTRAINT`. Restricting migrations to Postgres lets the migration be written
 * as real Postgres, and keeps the test suite — which drops and recreates every
 * table from the Exposed objects — independent of it.
 *
 * ## Why [run] takes a `locations` argument
 *
 * This code is deployed inside a jar that also contains Witchwood, which ships
 * its own `db/migration/V1__init.sql`. Flyway scans a location, not a jar, so at
 * the shared path two services' files land in one directory with two migrations
 * claiming version 1 — and Flyway resolves the conflict by running **neither**.
 * It logs "3 SQL migrations were detected but not run because they did not
 * follow the filename convention", then "Successfully validated 0 migrations",
 * and the boot looks entirely healthy. That is not hypothetical: it is why one
 * service's V2 never ran on any deployment.
 *
 * A location private to the service makes the collision structurally impossible
 * rather than merely fixed, which is the point — the failure is silent, so it
 * has to be prevented rather than detected. Each service passes its own
 * `classpath:db/<service>`.
 *
 * Ordering matters: [run] is called as the *first* statement of the service's
 * `start(port)`, before `installXSchema()`. Both entry paths then converge:
 *
 *  - **Existing database** — the destructive migration rewrites the schema; the
 *    later `installXSchema()` finds it already correct and is a no-op.
 *  - **Fresh database** — the migration's `IF EXISTS` guards make it a no-op;
 *    Exposed creates the new shape directly.
 */
object MigrationRunner {

    private val log = Logger.getLogger(MigrationRunner::class.java.name)

    /**
     * Migrate [dataSource] when [driverClassName] names the Postgres driver,
     * scanning [locations] for the service's own migration scripts.
     *
     * @throws Exception if migration fails, so the server never starts
     *   half-migrated.
     */
    fun run(dataSource: DataSource, driverClassName: String, locations: String) {
        if (!driverClassName.contains("postgresql", ignoreCase = true)) {
            log.info(
                "Migrations skipped: driver '$driverClassName' is not Postgres — " +
                    "Exposed owns this schema (see MigrationRunner for why)."
            )
            return
        }

        log.info("Running migrations against Postgres...")
        Flyway.configure()
            .dataSource(dataSource)
            // Service-private, so Witchwood's db/migration/V1__init.sql cannot
            // collide with this service's V1 at the same version. See the class
            // KDoc.
            .locations(locations)
            // Adopts a pre-existing database that has no flyway_schema_history
            // (every current deployment) by baselining it at 0, then applies
            // V1 onward. An empty database is not baselined and simply runs
            // every migration from the start.
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()

        log.info("Migrations complete.")
    }
}
