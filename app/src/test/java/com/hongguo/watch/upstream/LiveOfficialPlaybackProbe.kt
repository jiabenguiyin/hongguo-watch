package com.hongguo.watch.upstream

import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assume
import org.junit.Test

/**
 * Live probe against fqnovel (not a CI assertion). Run:
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
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        val device = mapOf(
            "device_id" to VendorConstants.device.getValue("device_id"),
            "iid" to VendorConstants.device.getValue("iid"),
            "cdid" to (VendorConstants.device["cdid"] ?: UUID.randomUUID().toString()),
        )
        val now = System.currentTimeMillis()
        println("PROBE episode=$episodeId device_id=${device["device_id"]} now=$now")
        println("PROBE signer=MobilePlayback.signer (PlayerSigner / no MetaSec)")

        val attempts = mutableListOf<String>()
        try {
            RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(28)) {
                val ladder = MobilePlayback.ladder(
                    episodeId,
                    device,
                    http,
                    MobilePlayback.signer,
                    now,
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
