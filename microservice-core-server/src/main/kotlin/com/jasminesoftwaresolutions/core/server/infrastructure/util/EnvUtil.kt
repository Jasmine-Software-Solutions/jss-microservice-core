package com.jasminesoftwaresolutions.core.server.infrastructure.util

/** Environment-variable helpers shared by every microservice's Env object. */
object EnvUtil {
    /** Reads an environment variable, returning "" when unset. */
    fun env(name: String): String = System.getenv(name) ?: ""

    /** Reads an environment variable, returning null when unset. */
    fun envOrNull(name: String): String? = System.getenv(name)

    /** Reads an environment variable as an Int, falling back to [default] when unset or invalid. */
    fun envInt(name: String, default: Int): Int = System.getenv(name)?.toIntOrNull() ?: default
}
