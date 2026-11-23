// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.proxy

import com.hongguo.proxy.metasec.MetaSecUnidbgEngine
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Server-side MetaSec signer for the playback proxy.
 *
 * ## Private host layout (never commit into git)
 * ```
 * /opt/hongguo-playback-proxy/metasec/          # METASEC_HOME
 *   lib/arm64-v8a/libmetasec_ml.so
 *   lib/arm64-v8a/libc++_shared.so
 *   license.b64                                 # MSSDK license for aid 8662
 *   refso/data/ms_16777218.bin                  # package-signature blob
 *   base.apk                                    # stub ok (empty file)
 *   msdata/                                     # runtime state (METASEC_DATA)
 * ```
 *
 * Extract with `proxy/scripts/metasec-extract.sh /path/to/com.phoenix.read.apk`.
 *
 * ## Env
 * - `METASEC_HOME` — assets root (default `/opt/hongguo-playback-proxy/metasec`)
 * - `METASEC_DATA` — msdata dir (default `$METASEC_HOME/msdata`)
 * - `METASEC_BACKEND` — `unicorn2` (default, smaller) or `dynarmic`
 * - `METASEC_LICENSE` — optional override instead of `license.b64`
 * - `METASEC_UNIDBG_URL` — optional HTTP sidecar; used if local .so missing
 * - `METASEC_STRICT=1` — do not fall back to PlayerSigner when MetaSec fails
 */
object MetaSecBridge {
    const val ENV_HOME = "METASEC_HOME"
    const val ENV_URL = "METASEC_UNIDBG_URL"
    const val DEFAULT_HOME = "/opt/hongguo-playback-proxy/metasec"

    private val engine = AtomicReference<MetaSecUnidbgEngine?>(null)
    private val initError = AtomicReference<String?>(null)

    fun home(): File =
        File(System.getenv(ENV_HOME)?.trim().orEmpty().ifEmpty { DEFAULT_HOME })

    fun soFile(): File = File(home(), "lib/arm64-v8a/libmetasec_ml.so")

    fun isLocalReady(): Boolean =
        soFile().isFile && soFile().length() > 100_000L &&
            (File(home(), "license.b64").isFile || !System.getenv("METASEC_LICENSE").isNullOrBlank())

    fun isConfigured(): Boolean =
        isLocalReady() || !System.getenv(ENV_URL).isNullOrBlank()

    fun statusLabel(): String = when {
        engine.get() != null -> "metasec-unidbg"
        isLocalReady() -> "metasec-local-ready"
        !System.getenv(ENV_URL).isNullOrBlank() -> "metasec-http"
        else -> "playersigner"
    }

    /** Prefer registered device_id / install_id for MetaSec init. */
    fun bindDevice(deviceId: String, installId: String) {
        if (deviceId.isNotBlank()) System.setProperty("metasec.runtime.did", deviceId)
        if (installId.isNotBlank()) System.setProperty("metasec.runtime.iid", installId)
    }

    /**
     * Sign a full request URL (+ optional body) into MetaSec header map
     * (`X-Argus`, `X-Ladon`, `X-Khronos`, …). Returns null to fall back.
     */
    @Synchronized
    fun signHeaders(url: String, body: ByteArray?): Map<String, String>? {
        if (url.isBlank()) return null
        if (isLocalReady()) {
            val headers = signLocal(url, body)
            if (headers != null) return headers
            if (System.getenv("METASEC_STRICT") == "1") {
                throw IllegalStateException(
                    "MetaSec local sign failed: ${initError.get() ?: "unknown"}",
                )
            }
            System.err.println("[MetaSec] local sign failed (${initError.get()}); falling back")
            return null
        }
        val base = System.getenv(ENV_URL)?.trim().orEmpty()
        if (base.isEmpty()) return null
        return signHttp(base, url, body)
    }

    private fun ensureEngine(deviceId: String, installId: String): MetaSecUnidbgEngine {
        engine.get()?.let { return it }
        synchronized(this) {
            engine.get()?.let { return it }
            val created = MetaSecUnidbgEngine(java.lang.Boolean.getBoolean("metasec.verbose"))
            created.initFull(deviceId, installId, System.getenv("METASEC_CHANNEL") ?: "google")
            try {
                created.k3(67108866, 0, 0L, null, null)
            } catch (_: Throwable) {
            }
            engine.set(created)
            initError.set(null)
            System.err.println("[MetaSec] unidbg engine ready (home=${home().absolutePath})")
            return created
        }
    }

    private fun signLocal(url: String, body: ByteArray?): Map<String, String>? {
        return try {
            val did = System.getProperty("metasec.runtime.did")
                ?: System.getenv("METASEC_DID")?.takeIf { it.isNotBlank() }
                ?: "3788282713867595"
            val iid = System.getProperty("metasec.runtime.iid")
                ?: System.getenv("METASEC_IID")?.takeIf { it.isNotBlank() }
                ?: "3788282713871691"
            val signer = ensureEngine(did, iid)
            val bytes = body ?: ByteArray(0)
            val rounds = System.getenv("METASEC_ROUNDS")?.toIntOrNull()?.coerceIn(1, 12) ?: 8
            val wipe = System.getenv("METASEC_WIPE") ?: "after"
            if (wipe == "before") MetaSecUnidbgEngine.wipeMsdata()
            val out = signer.doSign(url, false, bytes, rounds)
            if (wipe == "after") MetaSecUnidbgEngine.wipeMsdata()
            if (out == null || !out.keys.any { it.equals("X-Khronos", ignoreCase = true) }) {
                initError.set("doSign returned null/incomplete")
                null
            } else {
                LinkedHashMap(out)
            }
        } catch (t: Throwable) {
            initError.set(t.message ?: t.javaClass.simpleName)
            System.err.println("[MetaSec] signLocal error: $t")
            engine.set(null)
            null
        }
    }

    private fun signHttp(base: String, url: String, body: ByteArray?): Map<String, String>? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            val payload = JSONObject()
                .put("url", url)
                .put(
                    "body_b64",
                    if (body == null || body.isEmpty()) {
                        ""
                    } else {
                        java.util.Base64.getEncoder().encodeToString(body)
                    },
                )
            val req = Request.Builder()
                .url(base.trimEnd('/') + "/sign")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    initError.set("HTTP ${resp.code}")
                    return null
                }
                val root = JSONObject(text)
                val headersObj = root.optJSONObject("headers") ?: root
                val map = linkedMapOf<String, String>()
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = headersObj.optString(k)
                    if (v.isNotEmpty() && k.startsWith("X-", ignoreCase = true)) {
                        map[k] = v
                    }
                }
                map.takeIf { it.isNotEmpty() }
            }
        } catch (t: Throwable) {
            initError.set(t.message)
            null
        }
    }
}
