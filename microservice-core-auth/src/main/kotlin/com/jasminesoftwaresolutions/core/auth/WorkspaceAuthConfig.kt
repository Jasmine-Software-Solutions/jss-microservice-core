package com.jasminesoftwaresolutions.core.auth

import com.jasminesoftwaresolutions.witchwood.evaluation.snapshot.resources.CompiledResourcePath
import java.util.UUID

/**
 * The service-specific data the shared workspace layer is parameterized by.
 *
 * Everything here is *policy data* rather than logic: the action strings and
 * resource paths are authored in Witchwood's database, and a change to one of
 * them is a change to what the deployed policies match — not a refactor. They
 * must stay byte-identical across this seam, which is also why they are
 * injected rather than derived: the action prefix is the service's own
 * `*Actions.SERVICE` (`forms.jasmine.software`, `hiring.jasmine.software`, …),
 * and `GetWorkspace` under one prefix is a different grant from `GetWorkspace`
 * under another.
 *
 * @param cookieNamespace prefixes the workspace-selection cookies, producing
 *   `<namespace>_tenant` and `<namespace>_workspace`. Two services sharing a
 *   parent domain must not share these cookies: the cookie is the selection,
 *   and a service that read another's would resolve a workspace id that does
 *   not exist in its own database.
 * @param getWorkspace the workspace-scoped action that opens one workspace;
 *   also the gate `resolveAuthContext` applies to any addressed pair.
 * @param listWorkspaces the tenant-scoped action that makes a tenant's
 *   workspaces listable (intersected with [getWorkspace] per row).
 * @param createWorkspace the tenant-scoped action that makes a tenant
 *   manageable — with [listWorkspaces], it is what separates the two empty
 *   states.
 * @param updateWorkspace the workspace-scoped action gating a rename.
 * @param deleteWorkspace the workspace-scoped action gating an archive.
 * @param tenantPath the service's resource builder: `(tenantId, workspaceId?)`
 *   where the workspace segment is omitted when null. Building the path is
 *   service-specific because the *service name* is part of it; the shape is
 *   not, and both paths must line up with the policies exactly — resource
 *   paths match at exact length, which is what keeps the tenant-scoped and
 *   workspace-scoped grants independent.
 */
data class WorkspaceAuthConfig(
    val cookieNamespace: String,
    val getWorkspace: String,
    val listWorkspaces: String,
    val createWorkspace: String,
    val updateWorkspace: String,
    val deleteWorkspace: String,
    val tenantPath: (tenantId: UUID, workspaceId: UUID?) -> CompiledResourcePath,
)
