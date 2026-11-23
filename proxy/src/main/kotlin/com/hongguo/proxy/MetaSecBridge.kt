// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.proxy

import com.hongguo.proxy.metasec.MetaSecUnidbgEngine
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private val msInitialized = AtomicBoolean(false)
    private val initDid = AtomicReference<String?>(null)
    private val initIid = AtomicReference<String?>(null)
    private val initError = AtomicReference<String?>(null)

    fun home(): File =
        File(System.getenv(ENV_HOME)?.trim().orEmpty().ifEmpty { DEFAULT_HOME })

    fun soFile(): File = File(home(), "lib/arm64-v8a/libmetasec_ml.so")

    fun isLocalReady(): Boolean {
        if (System.getenv("METASEC_DISABLE") == "1") return false
        return soFile().isFile && soFile().length() > 100_000L &&
            (File(home(), "license.b64").isFile || !System.getenv("METASEC_LICENSE").isNullOrBlank())
    }

    fun isConfigured(): Boolean =
        isLocalReady() || !System.getenv(ENV_URL).isNullOrBlank()

    fun statusLabel(): String = when {
        msInitialized.get() -> "metasec-unidbg"
        engine.get() != null -> "metasec-so-loaded"
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

    /**
     * Load libmetasec only (JNI_OnLoad). Do **not** initFull here — that must use
     * device_id/iid from device_register or signatures soft-fail as empty-200.
     */
    fun warmUp() {
        if (!isLocalReady()) return
        try {
            loadEngine()
            System.err.println("[MetaSec] warm-up SO loaded (initFull deferred until register)")
        } catch (t: Throwable) {
            initError.set(t.message ?: t.javaClass.simpleName)
            System.err.println("[MetaSec] warmUp failed: $t")
        }
    }

    private fun loadEngine(): MetaSecUnidbgEngine {
        engine.get()?.let { return it }
        synchronized(this) {
            engine.get()?.let { return it }
            val created = MetaSecUnidbgEngine(java.lang.Boolean.getBoolean("metasec.verbose"))
            engine.set(created)
            System.err.println("[MetaSec] unidbg SO ready (home=${home().absolutePath})")
            return created
        }
    }

    private fun ensureInitialized(deviceId: String, installId: String): MetaSecUnidbgEngine {
        val eng = loadEngine()
        val same =
            msInitialized.get() &&
                initDid.get() == deviceId &&
                initIid.get() == installId
        if (same) return eng
        synchronized(this) {
            if (msInitialized.get() && initDid.get() == deviceId && initIid.get() == installId) {
                return eng
            }
            // Re-init when device identity changes (e.g. after device_register).
            val reinited = msInitialized.get()
            if (reinited) {
                MetaSecUnidbgEngine.wipeMsdata()
            }
            eng.initFull(deviceId, installId, System.getenv("METASEC_CHANNEL") ?: "google")
            try {
                val h = eng.k3(67108866, 0, 0L, null, null)
                if (h != null && h.value is Long) {
                    eng.handleVal = h.value as Long
                }
            } catch (_: Throwable) {
            }
            initDid.set(deviceId)
            initIid.set(installId)
            msInitialized.set(true)
            initError.set(null)
            System.err.println(
                "[MetaSec] initFull reinited=$reinited did=${deviceId.take(6)}… " +
                    "iid=${installId.take(6)}… handle=${eng.handleVal}",
            )
            return eng
        }
    }

    private fun signLocal(url: String, body: ByteArray?): Map<String, String>? {
        return try {
            val did = System.getProperty("metasec.runtime.did")
                ?: System.getenv("METASEC_DID")?.takeIf { it.isNotBlank() }
            val iid = System.getProperty("metasec.runtime.iid")
                ?: System.getenv("METASEC_IID")?.takeIf { it.isNotBlank() }
            if (did.isNullOrBlank() || iid.isNullOrBlank()) {
                initError.set("device_id/iid not bound — call bindDevice after register")
                System.err.println("[MetaSec] ${initError.get()}")
                return null
            }
            val signer = ensureInitialized(did, iid)
            val bytes = body ?: ByteArray(0)
            val rounds = System.getenv("METASEC_ROUNDS")?.toIntOrNull()?.coerceIn(1, 12) ?: 12
            val wipe = System.getenv("METASEC_WIPE")?.trim().orEmpty().ifEmpty { "none" }
            if (wipe == "before") MetaSecUnidbgEngine.wipeMsdata()
            val out = signer.doSign(url, false, bytes, rounds)
            if (wipe == "after") MetaSecUnidbgEngine.wipeMsdata()
            val argus = out?.entries?.firstOrNull { it.key.equals("X-Argus", ignoreCase = true) }?.value
            val khronos = out?.keys?.any { it.equals("X-Khronos", ignoreCase = true) } == true
            // Collector stub Argus is ~8 chars (e.g. "qFgEaw=="); real Argus is much longer.
            // Reject stubs so ProxyMain can fall back to Medusa V5 PlayerSigner.
            if (out == null || !khronos || argus.isNullOrBlank() || argus.length < 32) {
                initError.set(
                    "doSign stub/incomplete keys=${out?.keys} argusLen=${argus?.length ?: 0}",
                )
                System.err.println("[MetaSec] ${initError.get()}")
                null
            } else {
                System.err.println("[MetaSec] sign ok argusLen=${argus.length} keys=${out.keys}")
                LinkedHashMap(out)
            }
        } catch (t: Throwable) {
            initError.set(t.message ?: t.javaClass.simpleName)
            System.err.println("[MetaSec] signLocal error: $t")
            engine.set(null)
            msInitialized.set(false)
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
