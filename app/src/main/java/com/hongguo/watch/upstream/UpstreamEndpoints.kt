// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.upstream

/**
 * Optional upstream host overrides for the **playback signing proxy** (`proxy/`).
 *
 * On the watch APK these env vars are unset → built-in defaults (unchanged).
 * On a self-hosted proxy, operators can retarget when official domains shift:
 *
 * | Env | Default |
 * | --- | --- |
 * | `PROXY_REGISTER_HOST` | `https://log5-applog-lf.fqnovel.com` |
 * | `PROXY_PRELOAD_HOST` | `reading.snssdk.com` |
 * | `PROXY_MODEL_HOSTS` | comma-separated a,b,hl,lf |
 * | `PROXY_VIDEO_MODEL_HOSTS` | comma-separated a,c |
 * | `PROXY_VIDEO_TAB_HOST` | `api5-normal-sinfonlineb.fqnovel.com` |
 */
internal object UpstreamEndpoints {
    const val DEFAULT_REGISTER_HOST = "https://log5-applog-lf.fqnovel.com"
    const val DEFAULT_PRELOAD_HOST = "reading.snssdk.com"
    const val DEFAULT_VIDEO_TAB_HOST = "api5-normal-sinfonlineb.fqnovel.com"

    val DEFAULT_MODEL_HOSTS =
        listOf(
            "api5-normal-sinfonlinea.fqnovel.com",
            "api5-normal-sinfonlineb.fqnovel.com",
            "api5-normal-hl.fqnovel.com",
            "api5-normal-lf.fqnovel.com",
        )

    val DEFAULT_VIDEO_MODEL_HOSTS =
        listOf(
            "api5-normal-sinfonlinea.fqnovel.com",
            "api5-normal-sinfonlinec.fqnovel.com",
        )

    private fun env(name: String): String? =
        System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

    private fun csv(name: String, fallback: List<String>): List<String> {
        val raw = env(name) ?: return fallback
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.ifEmpty { fallback }
    }

    fun registerBaseUrl(): String =
        env("PROXY_REGISTER_HOST")?.trimEnd('/') ?: DEFAULT_REGISTER_HOST

    fun preloadHost(): String = env("PROXY_PRELOAD_HOST") ?: DEFAULT_PRELOAD_HOST

    fun modelHosts(): List<String> = csv("PROXY_MODEL_HOSTS", DEFAULT_MODEL_HOSTS)

    fun videoModelHosts(): List<String> =
        csv("PROXY_VIDEO_MODEL_HOSTS", DEFAULT_VIDEO_MODEL_HOSTS)

    fun videoTabHost(): String = env("PROXY_VIDEO_TAB_HOST") ?: DEFAULT_VIDEO_TAB_HOST

    /** Bases tried for GET video_tab ladder (host + reading fallback). */
    fun videoTabBases(): List<String> {
        val tab = videoTabHost()
        val reading = preloadHost()
        return listOf(
            "https://$tab/reading/bookapi/video_tab/multi_video_model/v1/",
            "https://$tab/reading/bookapi/video_tab/video_model/v1/",
            "https://$reading/reading/bookapi/video_tab/multi_video_model/v1/",
        )
    }

    /** Snapshot for `/health` (no secrets). */
    fun healthSnapshot(): Map<String, Any> =
        mapOf(
            "register_host" to registerBaseUrl(),
            "preload_host" to preloadHost(),
            "model_hosts" to modelHosts(),
            "video_model_hosts" to videoModelHosts(),
            "video_tab_host" to videoTabHost(),
        )
}
