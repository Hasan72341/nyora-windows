package com.nyora.windows.ai

import java.net.URI

/**
 * Policy for endpoints that receive a user-provided AI credential.
 *
 * A custom provider may be any HTTPS origin. Plain HTTP is deliberately limited
 * to explicit loopback hosts so local runtimes such as Ollama and LM Studio keep
 * working without ever sending an API key over a LAN or the public internet.
 * Callers must also use a redirect-disabled client; otherwise a safe request
 * could be replayed to a different origin after this validation has completed.
 */
object AiEndpointPolicy {

    const val INVALID_ENDPOINT_MESSAGE =
        "Use an HTTPS base URL, or http://localhost, http://127.0.0.1, or http://[::1] " +
            "for a local model. URLs cannot contain credentials, a query, or a fragment."

    /**
     * Returns a normalized base URL with no trailing slash, or null when [raw]
     * is not safe for an authenticated request. A blank value is intentionally
     * invalid here: providers handle their own default before invoking it.
     */
    fun normalizeBaseUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.trim()?.trim('[', ']') ?: return null
        if (
            uri.isOpaque ||
            uri.userInfo != null ||
            uri.rawQuery != null ||
            uri.rawFragment != null ||
            uri.port !in -1..65_535
        ) {
            return null
        }

        val isLoopback = host.equals("localhost", ignoreCase = true) ||
            host == "127.0.0.1" || host == "::1"
        if (scheme != "https" && !(scheme == "http" && isLoopback)) return null

        return value.trimEnd('/')
    }

    /** A presentation-ready validation message for the settings form. */
    fun validationError(raw: String): String? =
        if (raw.trim().isEmpty() || normalizeBaseUrl(raw) != null) null else INVALID_ENDPOINT_MESSAGE

    /**
     * Append [requiredPath] unless the normalized base already names that
     * endpoint. This accepts both provider roots and a pasted full endpoint.
     */
    fun requestUrl(baseUrl: String, requiredPath: String): String? {
        require(requiredPath.startsWith('/')) { "Required endpoint path must start with '/'" }
        val base = normalizeBaseUrl(baseUrl) ?: return null
        return if (base.endsWith(requiredPath)) base else "$base$requiredPath"
    }
}
