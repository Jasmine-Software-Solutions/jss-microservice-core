package com.jasminesoftwaresolutions.core.auth.testutil

import com.jasminesoftwaresolutions.core.auth.WorkspaceAuthConfig
import com.jasminesoftwaresolutions.witchwood.evaluation.snapshot.resources.CompiledResourcePath
import java.util.UUID

/**
 * A service name that is no product's, so a test that passes with these values
 * cannot have come from a hardcoded product string surviving in the shared
 * code.
 *
 * The literal strings are pinned in each product instead — that is where the
 * policy data is defined, and a change to one of them changes what the deployed
 * policies match. What the shared tests assert is that the values *injected*
 * here are the ones the service evaluates with.
 */
object TestResources {
    const val SERVICE = "test.jasmine.software"

    fun tenant(tenantId: UUID, workspaceId: UUID? = null): CompiledResourcePath {
        val b = CompiledResourcePath.builder().service(SERVICE)
        b.one("tenant", tenantId)
        if (workspaceId != null) b.one("workspace", workspaceId)
        return b.build()
    }
}

fun testAuthConfig(cookieNamespace: String = "test") = WorkspaceAuthConfig(
    cookieNamespace = cookieNamespace,
    getWorkspace = "${TestResources.SERVICE}:GetWorkspace",
    listWorkspaces = "${TestResources.SERVICE}:ListWorkspaces",
    createWorkspace = "${TestResources.SERVICE}:CreateWorkspace",
    updateWorkspace = "${TestResources.SERVICE}:UpdateWorkspace",
    deleteWorkspace = "${TestResources.SERVICE}:DeleteWorkspace",
    tenantPath = TestResources::tenant,
)
