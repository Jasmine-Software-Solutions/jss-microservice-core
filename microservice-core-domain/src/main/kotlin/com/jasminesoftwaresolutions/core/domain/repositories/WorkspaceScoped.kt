package com.jasminesoftwaresolutions.core.domain.repositories

import com.jasminesoftwaresolutions.core.domain.models.IWorkspaceScoped
import java.util.UUID

/**
 * A repository whose rows are partitioned by `(tenantId, workspaceId)`, with a
 * single-row read that enforces it.
 *
 * There are exactly two ways to load one row through this interface, and their
 * names say which is which:
 *
 *  - [findById] **with a scope** — `findById(tenantId, workspaceId, id)` — is
 *    the read to reach for whenever the id arrived **from a request**. An id is
 *    not evidence of scope: the caller can name any row that exists, and a row
 *    in another workspace is a row they have no grant for. The grant cannot
 *    substitute for this check — it is built from the caller's own scope, so it
 *    passes for whatever id was named.
 *  - [findByIdUnscoped] is the escape hatch, and it is deliberately awkward to
 *    reach. It is correct only where the row's scope is **already established**
 *    some other way: a row reached from a parent, a row the caller just
 *    created, or a worker whose event carries the scope. Each call site says
 *    which of those it is.
 *
 * A service may have a fourth legitimate case — a surface reached by a session
 * that has **no workspace selected** at all, where the scope has to come from
 * the row named in the path. That read is the entry point of the handler: it is
 * not an id trusted from the request body, it is the resource being addressed,
 * and every read after it is scoped.
 *
 * The inherited one-argument [findById] is neither, and it is deprecated to a
 * compile **error**. The failure it used to allow is invisible at runtime — a
 * bare id loads a row in any tenant and any workspace, and the result is
 * indistinguishable from a legitimate read — so the compiler is the only place
 * left that can catch it. Its complement is the audit: every call site of
 * [findByIdUnscoped] is a deliberate opt-out, and
 * `grep -rn "findByIdUnscoped" server/ interfaces/ workers/` lists all of them
 * with their reasons written down beside them.
 *
 * One gap worth knowing, since it is the shape a future regression would take:
 * the deprecation fires on the *static* type. A reference held as
 * [IIdentifiedRepository] rather than as this interface reaches the one-argument
 * read with no diagnostic at all. Widening a variable's type, or passing one of
 * these repositories to a function that takes the base interface, silently
 * steps around the backstop.
 *
 * The check is in memory rather than in SQL, deliberately. Every implementation
 * already loads by primary key, so the alternative is a second query shape per
 * repository that buys no extra safety — a row read by id either carries the
 * caller's scope or it does not. The cost of a miss is one fetched row that is
 * then discarded, which is the work the unscoped read was already doing.
 */
interface IWorkspaceScopedRepository<T : IWorkspaceScoped> : IIdentifiedRepository<T> {

    /**
     * The unscoped read. **Do not call this with an id that came from a
     * request** — use `findById(tenantId, workspaceId, id)`.
     *
     * Reach for it only when the row's scope is already established, which is
     * one of three things: the row was reached from a parent that was itself
     * scoped, the row was just created by this same call path, or the caller is
     * a worker and the event it is handling carries the scope. Say which, in a
     * comment, at the call site.
     */
    fun findByIdUnscoped(id: UUID): T?

    /**
     * The row [id] names, or `null` if [id] belongs to a different tenant or a
     * different workspace.
     *
     * `null` rather than a thrown error, so callers keep the shape they already
     * have: every call site turns an absent row into a 404, and a row in a
     * foreign workspace has to be indistinguishable from one that was never
     * there — otherwise the response itself reports that the id exists.
     */
    fun findById(tenantId: UUID, workspaceId: UUID, id: UUID): T? =
        findByIdUnscoped(id)?.takeIf { it.tenantId == tenantId && it.workspaceId == workspaceId }

    /**
     * Deprecated to an error: this is the read [findByIdUnscoped] replaces.
     *
     * It exists only so that every surviving call compiles into a diagnostic
     * naming the reason, rather than into a silently unscoped query. There is no
     * legitimate new caller — deleting it and the backstop with it would be
     * worse, because the failure mode is silence.
     */
    @Deprecated(
        "Unscoped by id. An id from a request is not evidence of scope: use " +
            "findById(tenantId, workspaceId, id). Use findByIdUnscoped(id) only where the " +
            "row's scope is already established — a row reached from a parent, a row just " +
            "created, or a worker whose event carries the scope.",
        level = DeprecationLevel.ERROR,
    )
    override fun findById(id: UUID): T? = findByIdUnscoped(id)
}
