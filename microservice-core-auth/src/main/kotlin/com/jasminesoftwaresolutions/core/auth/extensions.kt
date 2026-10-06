package com.jasminesoftwaresolutions.core.auth

import com.jasminesoftwaresolutions.core.auth.authorization.WorkspaceAuthentication
import com.jasminesoftwaresolutions.core.auth.services.SessionAuthService
import com.jasminesoftwaresolutions.witchwood.evaluation.snapshot.resources.CompiledResourcePath
import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import io.javalin.http.UnauthorizedResponse
import java.util.UUID

/**
 * Retrieve or create the [WorkspaceAuthentication] for the current request.
 *
 * **Staff surfaces only.** This resolves a *workspace* as well as a principal,
 * and answers 401 when none is selected — see [SessionAuthService.resolveAuthContext].
 * Surfaces reached by a principal who never selected one — whether because it
 * holds no grant on any workspace or because it is not meant to have one at all
 * — must not call this; they take [requirePrincipalId] and derive their scope
 * from the row named in the path instead.
 *
 * When `tenant_id`/`workspace_id` are absent (the browser client), the values
 * come from the `<namespace>_tenant` / `<namespace>_workspace` cookies. API
 * clients pass them explicitly via query params or the request body.
 */
fun Context.requireAuth(): WorkspaceAuthentication {
    return this.attributeOrCompute("authContext") {
        val sessionAuth = SessionAuthService.instance
            ?: throw IllegalStateException("SessionAuthService not configured")
        sessionAuth.resolveAuthContext(this)
    }!!
}

/**
 * The authenticated principal for the current request, with no workspace.
 *
 * For the surfaces that have no workspace to resolve. `requireAuth()` is
 * unusable on them by construction — it 401s when nothing is selected, which
 * is a normal state for a principal arriving at a public surface — so those
 * handlers take the principal the before-filter authenticated and derive their
 * scope from the row named in the path. Single definition, shared, so the
 * reasoning above lives in one place rather than being re-derived at each call
 * site.
 */
fun Context.requirePrincipalId(): UUID =
    attribute("principalId") ?: throw UnauthorizedResponse("Authentication required")

/**
 * Witchwood evaluation that **fails closed**: anything thrown — Witchwood
 * unreachable, an evaluation error — counts as a denial.
 *
 * Being unable to ask the question is not an answer. The alternative shape,
 * letting the exception escape, turns a policy-store outage into a 500 that
 * reads as an outage while the endpoint answers to nobody in particular; and
 * the opposite shape, treating an error as an allow, is worse. This mirrors
 * what the per-row list filters here already did with their `try/catch`, now
 * shared by the single-resource gates too.
 */
fun WorkspaceAuthentication.allowed(action: String, resource: CompiledResourcePath): Boolean =
    try {
        evaluate(action, resource)
    } catch (_: Exception) {
        false
    }

/** [allowed], as a gate: throws [ForbiddenResponse] unless the principal holds [action] on [resource]. */
fun WorkspaceAuthentication.authorize(action: String, resource: CompiledResourcePath) {
    if (!allowed(action, resource)) throw ForbiddenResponse("Insufficient privileges: $action")
}
