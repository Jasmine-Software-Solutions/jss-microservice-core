package com.jasminesoftwaresolutions.core.domain.models

import java.util.UUID

/**
 * A domain row that lives inside exactly one workspace.
 *
 * The partitioning key is `(tenantId, workspaceId)`, and it is carried on the
 * row itself rather than resolved through a parent — so "which workspace does
 * this belong to" is answerable from the row alone, without a second query.
 * That is the property the scoped single-row read depends on: it can decide
 * whether a row is in scope after loading it, rather than having to scope the
 * query.
 *
 * The point of the type is to make *which* entities are partitioned something
 * the compiler knows. Before it, that inventory lived in a grep: models
 * happened to declare the same two `UUID` fields, and the way to find out which
 * was to search for them.
 *
 * One model deliberately does not implement it: [IWorkspace] is scoped by
 * tenant **alone** — a workspace is the partition, so it cannot be inside one,
 * and its repository is not an [com.jasminesoftwaresolutions.core.domain.repositories.IWorkspaceScopedRepository].
 *
 * A third case belongs to individual services rather than this library: a model
 * reached only through a parent row that is itself partitioned may carry no
 * scope columns at all, inheriting the parent's scope instead of duplicating
 * it. Those models are outside this inventory on purpose — duplicating the
 * columns is what would let them disagree with the parent.
 */
interface IWorkspaceScoped : IIdentified {
    var tenantId: UUID
    var workspaceId: UUID
}
