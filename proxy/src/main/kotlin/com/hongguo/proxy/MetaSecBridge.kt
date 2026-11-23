// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.proxy

/**
 * Server-side MetaSec / unidbg bridge — **not ready in-cloud**.
 *
 * ## TODO (operator)
 * 1. Place official `com.phoenix.read` APK (or extracted `libmetasec_ml.so`) on the
 *    **private** proxy host only — never commit into this GPL tree.
 * 2. Run a unidbg (or equivalent) signer process that exposes HTTP, e.g.
 *    `POST /sign` → MetaSec headers for a given URL + body.
 * 3. Set `METASEC_UNIDBG_URL=http://127.0.0.1:9xxx` so [isConfigured] becomes true
 *    and [signHeaders] delegates instead of returning null.
 *
 * Until then the proxy falls back to GPL [com.hongguo.watch.upstream.PlayerSigner],
 * which may still yield upstream empty HTTP 200 (「官方接口限流」).
 */
object MetaSecBridge {
    const val ENV_URL = "METASEC_UNIDBG_URL"

    fun isConfigured(): Boolean = !System.getenv(ENV_URL).isNullOrBlank()

    /**
     * @return MetaSec-equivalent request headers, or null to use PlayerSigner.
     */
    fun signHeaders(query: String, body: ByteArray?): Map<String, String>? {
        val base = System.getenv(ENV_URL)?.trim().orEmpty()
        if (base.isEmpty()) return null
        // TODO: HTTP call to unidbg sidecar. Do not ship MetaSec .so in this repo.
        throw UnsupportedOperationException(
            "METASEC_UNIDBG_URL is set ($base) but MetaSecBridge.signHeaders is not implemented yet",
        )
    }
}
