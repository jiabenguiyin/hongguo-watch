package com.hongguo.watch.upstream

import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test

/**
 * Dump live multi_video_detail keys that look like collect / favorite counts.
 *
 * `HONGGUO_LIVE_PROBE=1 ./gradlew :app:testDebugUnitTest --tests '*.LiveDetailCollectProbe'`
 */
class LiveDetailCollectProbe {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()
    private val signer = Signer()

    @Test
    fun dumpCollectFieldsFromDetail() {
        val enabled =
            System.getenv("HONGGUO_LIVE_PROBE") == "1" ||
                System.getProperty("hongguo.liveProbe") == "1"
        Assume.assumeTrue("Set HONGGUO_LIVE_PROBE=1 to run live detail collect probe", enabled)

        val query = System.getenv("HONGGUO_PROBE_QUERY")
            ?: System.getProperty("hongguo.probeQuery", "成为魂界之主后宗门全疯了")
        val forcedId = System.getenv("HONGGUO_PROBE_SERIES")
            ?: System.getProperty("hongguo.probeSeries", "")

        val seed = VendorConstants.device + mapOf("cdid" to UUID.randomUUID().toString())
        val registerBody = DeviceRegister.payload(seed)
        val registerReq = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(registerBody, seed).forEach { (k, v) -> registerReq.header(k, v) }
        registerReq.post(registerBody.toRequestBody("application/octet-stream".toMediaType()))

        val registered = http.newCall(registerReq.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            println("PROBE register http=${response.code} bytes=${text.length}")
            DeviceRegister.parse(text, response.headers("Set-Cookie"))
        }
        requireNotNull(registered) { "device_register failed" }
        val device = mutableMapOf(
            "device_id" to registered.deviceId,
            "iid" to registered.installId,
            "cdid" to seed.getValue("cdid"),
        )
        println("PROBE device_id=${registered.deviceId} iid=${registered.installId}")

        val identity = VendorConstants.device.toMutableMap()
        identity.putAll(device)

        val seriesId = if (forcedId.isNotBlank()) {
            forcedId.trim()
        } else {
            searchSeriesId(identity, registered.cookies, query)
                ?: error("search miss for query=$query")
        }
        println("PROBE series_id=$seriesId query=$query")

        val detailBody = JSONObject()
            .put(
                "biz_param",
                JSONObject()
                    .put("detail_page_version", 0)
                    .put("disable_digg_stat", false)
                    .put("need_all_video_definition", false)
                    .put("need_mp4_align", false)
                    .put("source", 7)
                    .put("use_os_player", false)
                    .put("use_server_dns", false),
            )
            .put("dr_scene", "preload")
            .put("series_id", seriesId)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val signed = signer.sign(
            "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_detail/preload/v1",
            identity,
            body = detailBody,
        )
        val detailReq = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> detailReq.header(k, v) }
        registered.cookies.forEach { (k, v) -> detailReq.addHeader("Cookie", "$k=$v") }
        detailReq.header("User-Agent", VendorConstants.VIDEO_UA)
        detailReq.post(detailBody.toRequestBody("application/json; charset=utf-8".toMediaType()))

        val root = http.newCall(detailReq.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            println("PROBE detail http=${response.code} bytes=${text.length}")
            JSONObject(text)
        }
        println("PROBE detail code=${root.optInt("code")} message=${root.optString("message")}")
        val seriesNode = root.optJSONObject("data")?.optJSONObject(seriesId)
        requireNotNull(seriesNode) { "missing data[$seriesId]; top=${root.keys().asSequence().toList()}" }
        val videoData = seriesNode.optJSONObject("video_data")
        requireNotNull(videoData) { "missing video_data" }

        println("PROBE seriesNode keys=${seriesNode.keys().asSequence().toList().sorted()}")
        println("PROBE video_data keys=${videoData.keys().asSequence().toList().sorted()}")
        dumpInteresting(seriesNode, "seriesNode")
        dumpInteresting(videoData, "video_data")
        walkNestedStats(videoData, "video_data")
        walkNestedStats(seriesNode, "seriesNode")

        dumpBookapiMultiDetail(identity, registered.cookies, seriesId)
        dumpBookapiDirectory(identity, registered.cookies, seriesId)
    }

    private fun searchSeriesId(
        identity: Map<String, String>,
        cookies: Map<String, String>,
        query: String,
    ): String? {
        val values = identity.toMutableMap()
        for (k in listOf("version_code", "manifest_version_code", "update_version_code", "pv_player")) {
            values[k] = "72232"
        }
        values["version_name"] = "7.2.2.32"
        values.putAll(
            mapOf(
                "query" to query,
                "count" to "10",
                "offset" to "0",
                "tab_type" to "11",
                "bookshelf_search_plan" to "4",
                "use_correct" to "true",
                "user_is_login" to "0",
            ),
        )
        val signed = signer.sign(
            "https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",
            values,
        )
        val req = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> req.header(k, v) }
        cookies.forEach { (k, v) -> req.addHeader("Cookie", "$k=$v") }
        req.header("User-Agent", VendorConstants.VIDEO_UA)
        val root = http.newCall(req.get().build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            println("PROBE search http=${response.code} bytes=${text.length}")
            JSONObject(text)
        }
        val tabs = root.optJSONArray("search_tabs")
            ?: root.optJSONObject("data")?.optJSONArray("search_tabs")
            ?: return null
        for (i in 0 until tabs.length()) {
            val tab = tabs.optJSONObject(i) ?: continue
            if (tab.optInt("tab_type") != 11) continue
            val data = tab.optJSONArray("data") ?: continue
            for (j in 0 until data.length()) {
                val cell = data.optJSONObject(j) ?: continue
                val video = cell.optJSONArray("video_data")?.optJSONObject(0)
                val id = video?.optString("series_id").orEmpty()
                    .ifBlank { cell.optString("book_id") }
                    .ifBlank { cell.optString("search_result_id") }
                val title = cell.optJSONObject("search_high_light")
                    ?.optJSONObject("title")
                    ?.optString("text")
                    .orEmpty()
                    .ifBlank { video?.optString("title").orEmpty() }
                println("PROBE search hit id=$id title=$title")
                if (id.matches(Regex("^[1-9][0-9]{5,29}$"))) return id
            }
        }
        return null
    }

    private fun dumpBookapiMultiDetail(
        identity: Map<String, String>,
        cookies: Map<String, String>,
        seriesId: String,
    ) {
        val values = identity.toMutableMap()
        values["book_id"] = seriesId
        val signed = signer.sign(
            "https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/multi-detail/v1/",
            values,
        )
        val req = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> req.header(k, v) }
        cookies.forEach { (k, v) -> req.addHeader("Cookie", "$k=$v") }
        req.header("User-Agent", VendorConstants.VIDEO_UA)
        runCatching {
            http.newCall(req.get().build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                println("PROBE multi-detail http=${response.code} bytes=${text.length}")
                if (text.isBlank()) return@use
                val root = JSONObject(text)
                println("PROBE multi-detail top keys=${root.keys().asSequence().toList().sorted()}")
                dumpInteresting(root, "multi-detail")
                walkNestedStats(root, "multi-detail")
            }
        }.onFailure {
            println("PROBE multi-detail FAIL ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun dumpBookapiDirectory(
        identity: Map<String, String>,
        cookies: Map<String, String>,
        seriesId: String,
    ) {
        // Alternate detail-ish endpoints used by TV clients.
        for (path in listOf(
            "/reading/bookapi/directory/all_items/v/",
            "/reading/bookapi/book/detail/v/",
            "/reading/bookapi/novel/book/detail/v/",
        )) {
            val values = identity.toMutableMap()
            values["book_id"] = seriesId
            values["series_id"] = seriesId
            val signed = signer.sign("https://api5-normal-sinfonlinea.fqnovel.com$path", values)
            val req = Request.Builder().url(signed.url)
            signed.headers.forEach { (k, v) -> req.header(k, v) }
            cookies.forEach { (k, v) -> req.addHeader("Cookie", "$k=$v") }
            req.header("User-Agent", VendorConstants.VIDEO_UA)
            runCatching {
                http.newCall(req.get().build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    println("PROBE alt $path http=${response.code} bytes=${text.length}")
                    if (text.isBlank() || !text.trimStart().startsWith("{")) return@use
                    val root = JSONObject(text)
                    dumpInteresting(root, "alt$path")
                    walkNestedStats(root, "alt$path")
                }
            }.onFailure {
                println("PROBE alt $path FAIL ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    private fun dumpInteresting(obj: JSONObject, label: String) {
        val interesting = obj.keys().asSequence().filter { key ->
            val k = key.lowercase()
            k.contains("collect") || k.contains("favorite") || k.contains("bookshelf") ||
                k.contains("digg") || k.contains("stat") || k.contains("cnt") ||
                k.contains("count") || k.contains("heat") || k.contains("hot") ||
                k.contains("follow") || k.contains("like") || k.contains("metric") ||
                k.contains("cover") || k.contains("secondary")
        }.sorted().toList()
        println("PROBE $label interesting keys=$interesting")
        for (key in interesting) {
            val value = obj.opt(key)
            when (value) {
                is JSONObject ->
                    println(
                        "PROBE $label.$key=OBJECT keys=${value.keys().asSequence().toList().sorted()}",
                    )
                is JSONArray -> {
                    println("PROBE $label.$key=ARRAY len=${value.length()}")
                    for (i in 0 until minOf(value.length(), 8)) {
                        println("PROBE $label.$key[$i]=${value.opt(i)}")
                    }
                }
                else -> println("PROBE $label.$key=$value")
            }
        }
    }

    private fun walkNestedStats(obj: JSONObject, path: String, depth: Int = 0) {
        if (depth > 3) return
        val keys = obj.keys().asSequence().toList()
        for (key in keys) {
            val value = obj.opt(key) ?: continue
            val k = key.lowercase()
            val interesting = k.contains("collect") || k.contains("favorite") ||
                k.contains("bookshelf") || k.contains("digg") || k.contains("stat") ||
                k.contains("cnt") || k.contains("count") || k.contains("metric") ||
                k.contains("secondary") || k.contains("cover_stat")
            when (value) {
                is JSONObject -> {
                    if (interesting || depth < 1) {
                        if (interesting) dumpInteresting(value, "$path.$key")
                        walkNestedStats(value, "$path.$key", depth + 1)
                    }
                }
                is JSONArray -> {
                    if (!interesting) continue
                    println("PROBE walk $path.$key array len=${value.length()}")
                    for (i in 0 until minOf(value.length(), 12)) {
                        when (val item = value.opt(i)) {
                            is JSONObject -> {
                                println(
                                    "PROBE walk $path.$key[$i] keys=" +
                                        item.keys().asSequence().toList().sorted(),
                                )
                                dumpInteresting(item, "$path.$key[$i]")
                            }
                            else -> println("PROBE walk $path.$key[$i]=$item")
                        }
                    }
                }
            }
        }
    }
}
