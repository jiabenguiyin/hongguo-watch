package com.hongguo.watch.data.redfruit

import android.content.Context
import com.hongguo.watch.data.sync.EncryptedSessionFile
import com.hongguo.watch.upstream.DeviceRegister
import com.hongguo.watch.upstream.VendorConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

internal class RedfruitDevice(context: Context, private val client: OkHttpClient) {
    private val registration = EncryptedSessionFile(context, "redfruit-device", "hongguo_watch_redfruit_device")
    private val lock = Mutex()

    suspend fun identity(): Map<String, String> = lock.withLock {
        withContext(Dispatchers.IO) {
            val stored = registration.read()
            if (stored != null) return@withContext identityKeys.associateWith { stored.getString(it) }

            val seed = mapOf("cdid" to UUID.randomUUID().toString(), "openudid" to UUID.randomUUID().toString().replace("-", "").take(16))
            val profile = VendorConstants.device - setOf("device_id", "iid") + seed
            val body = DeviceRegister.payload(profile)
            val request = Request.Builder()
                .url(DeviceRegister.url())
                .post(body.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
                .apply { DeviceRegister.headers(body, profile).forEach { (key, value) -> header(key, value) } }
                .build()
            val registered = client.newCall(request).execute().use { reply ->
                if (!reply.isSuccessful) throw IOException("Redfruit device registration failed")
                val response = reply.body?.source() ?: throw IOException("Redfruit device registration was empty")
                if (response.request(64 * 1024 + 1L)) throw IOException("Redfruit device registration was oversized")
                DeviceRegister.parse(response.readUtf8(), reply.headers.values("Set-Cookie"))
                    ?: throw IOException("Redfruit device registration rejected")
            }
            val cookies = registered.cookies
                .filterKeys { it in setOf("store-region", "store-region-src", "ttreq") }
                .filterValues { it.length in 1..4_096 && it.all { char -> char in ' '..'~' } }
                .mapKeys { (key, _) -> "cookie_$key" }
            val identity = seed + mapOf("device_id" to registered.deviceId, "iid" to registered.installId) + cookies
            registration.write(JSONObject(identity))
            identity
        }
    }

    private companion object {
        val identityKeys = listOf(
            "device_id", "iid", "cdid", "openudid",
            "cookie_store-region", "cookie_store-region-src", "cookie_ttreq",
        )
    }
}
