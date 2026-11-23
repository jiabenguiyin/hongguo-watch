package com.hongguo.watch.upstream

import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume
import org.junit.Test

/**
 * Live end-to-end probe mirroring N3urda TV 0.16.14 play sequence:
 * device_register → register cookies → Medusa V5 (F21=312) →
 * preload/v1 + video_tab ladder.
 *
 * `HONGGUO_LIVE_PROBE=1 ./gradlew :app:testDebugUnitTest --tests '*.LiveOfficialPlaybackProbe'`
 */
class LiveOfficialPlaybackProbe {
    @Test
    fun probeOfficialLadder() {
        val enabled =
            System.getenv("HONGGUO_LIVE_PROBE") == "1" ||
                System.getProperty("hongguo.liveProbe") == "1"
        Assume.assumeTrue("Set HONGGUO_LIVE_PROBE=1 to run live fqnovel probe", enabled)
        val episodeId =
            System.getenv("HONGGUO_PROBE_EPISODE")
                ?: System.getProperty("hongguo.probeEpisode", "7675413202804411416")
        val http = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

        println("PROBE flow=register→cookies→MedusaV5→preload/v1→video_tab")
        println("PROBE field21=${V5Constants.field21} version=${VendorConstants.OFFICIAL_VERSION_CODE}")
        println("PROBE metasec_available=${MetaSecPlayback.isAvailable()} (expected false)")
        println("PROBE episode=$episodeId")

        val seed = VendorConstants.device + mapOf("cdid" to UUID.randomUUID().toString())
        val body = DeviceRegister.payload(seed)
        val registerReq = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, seed).forEach { (k, v) -> registerReq.header(k, v) }
        registerReq.post(body.toRequestBody("application/octet-stream".toMediaType()))

        val registered = try {
            http.newCall(registerReq.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                println("PROBE register http=${response.code} bytes=${text.length} setCookie=${response.headers("Set-Cookie").size}")
                DeviceRegister.parse(text, response.headers("Set-Cookie"))
            }
        } catch (e: Exception) {
            println("PROBE register FAIL ${e.javaClass.simpleName}: ${e.message}")
            null
        }

        val device = if (registered != null) {
            println(
                "PROBE register OK device_id=${registered.deviceId} iid=${registered.installId} " +
                    "cookies=${registered.cookies.keys.sorted()}",
            )
            seed + mapOf(
                "device_id" to registered.deviceId,
                "iid" to registered.installId,
            )
        } else {
            println("PROBE register skipped → seeded VendorConstants identity")
            seed
        }
        val cookies = registered?.cookies.orEmpty()
        val now = System.currentTimeMillis()
        val attempts = mutableListOf<String>()
        try {
            RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(32)) {
                val ladder = MobilePlayback.ladder(
                    episodeId,
                    device,
                    http,
                    MobilePlayback.signer,
                    now,
                    registerCookies = cookies,
                    onAttempt = { a ->
                        val line =
                            "attempt route=${a.route} outcome=${a.outcome} code=${a.code} " +
                                "elapsedMs=${a.elapsedMs} modelIssue=${a.modelIssue}"
                        attempts += line
                        println("PROBE $line")
                    },
                    onRoute = { tag -> println("PROBE success_route=$tag") },
                )
                println(
                    "PROBE OK renditions=${ladder.renditions.size} " +
                        "firstQuality=${ladder.renditions.firstOrNull()?.quality}",
                )
            }
        } catch (e: Exception) {
            println("PROBE FAIL ${e.javaClass.simpleName}: ${e.message}")
            e.suppressed.forEach {
                println("PROBE suppressed ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        println("PROBE attempts=${attempts.size}")
        attempts.forEach { println("PROBE_SUMMARY $it") }
    }
}
