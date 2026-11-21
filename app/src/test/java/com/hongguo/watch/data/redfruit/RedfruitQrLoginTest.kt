package com.hongguo.watch.data.redfruit

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.Base64

class RedfruitQrLoginTest {
    @Test
    fun confirmsAndVerifiesAnOfficialAccountBeforeReturningIt() = runBlocking {
        var polls = 0
        val login = login { request ->
            when (request.url.encodedPath) {
                "/passport/web/get_qrcode/" -> qrReply(request)
                "/passport/web/check_qrconnect/" -> {
                    assertEquals("website-qr-token", request.url.queryParameter("token"))
                    assertEquals("csrf-only", request.header("x-tt-passport-csrf-token"))
                    when (++polls) {
                        1 -> ok(request, JSONObject().put("status", "new"))
                        2 -> ok(request, JSONObject().put("status", 2))
                        else -> ok(request, JSONObject().put("status", 3).put("redirect_url", "/confirm"))
                    }
                }
                "/confirm" -> reply(request, 302)
                    .addHeader("Location", "/login-done")
                    .addHeader("Set-Cookie", "sessionid=website-session; Path=/; Secure; HttpOnly")
                    .addHeader("Set-Cookie", "install_id=web-device; Path=/; Secure")
                    .header("x-tt-token", "first-party-app-token")
                    .build()
                "/login-done" -> reply(request).build()
                "/passport/account/info/v2/" -> {
                    assertEquals(null, request.url.queryParameter("token"))
                    assertEquals(
                        setOf("passport_csrf_token=csrf-only", "sessionid=website-session", "install_id=web-device"),
                        request.header("Cookie")?.split("; ")?.toSet(),
                    )
                    ok(request, JSONObject().put("user_id_str", "7705824595416190014"))
                }
                else -> throw AssertionError("Unexpected official QR request")
            }
        }

        val ticket = login.begin()
        assertEquals(60, ticket.expiresIn)
        assertArrayEquals(IMAGE, ticket.image)
        assertEquals(RedfruitQrResult.Pending(false), login.poll())
        assertEquals(RedfruitQrResult.Pending(true), login.poll())
        val result = login.poll() as RedfruitQrResult.Approved
        assertEquals("sessionid=website-session", result.credentials.cookie)
        assertEquals("first-party-app-token", result.credentials.token)
        assertFalse(result.toString().contains("website-session"))
        assertEquals(RedfruitQrResult.Expired, login.poll())
    }

    @Test
    fun acceptsTheOfficialDataUrlQrShape() = runBlocking {
        val login = login { request ->
            when (request.url.encodedPath) {
                "/passport/web/get_qrcode/" -> qrReply(request)
                else -> throw AssertionError("Unexpected QR request")
            }
        }
        assertArrayEquals(IMAGE, login.begin().image)
    }

    @Test
    fun neverFollowsAConfirmationAddressOutsideTheOfficialHttpsOrigin() {
        val rejected = listOf("http://hongguoduanju.com/confirm", "https://other.example/confirm", "https://hongguoduanju.com:444/")
        for (redirect in rejected) {
            var requests = 0
            val login = login { request ->
                ++requests
                when (request.url.encodedPath) {
                    "/passport/web/get_qrcode/" -> qrReply(request)
                    "/passport/web/check_qrconnect/" -> ok(
                        request, JSONObject().put("status", "confirmed").put("redirect_url", redirect),
                    )
                    else -> throw AssertionError("An untrusted destination received a request")
                }
            }
            assertThrows(IOException::class.java) { runBlocking { login.begin(); login.poll() } }
            assertEquals(2, requests)
        }
    }

    @Test
    fun aGuestProfileDoesNotBecomeAnApprovedLogin() {
        val login = login { request ->
            when (request.url.encodedPath) {
                "/passport/web/get_qrcode/" -> qrReply(request)
                "/passport/web/check_qrconnect/" -> reply(request, data = JSONObject().put("status", "confirmed"))
                    .addHeader("Set-Cookie", "sessionid=website-session; Path=/; Secure")
                    .build()
                "/passport/account/info/v2/" -> ok(request, JSONObject().put("user_id", 0))
                else -> throw AssertionError("Unexpected QR request")
            }
        }
        assertThrows(IOException::class.java) { runBlocking { login.begin(); login.poll() } }
    }

    @Test
    fun clearingAnAttemptDropsItsCookiesAndPreventsFurtherPolling() = runBlocking {
        var starts = 0
        val login = login { request ->
            assertEquals("/passport/web/get_qrcode/", request.url.encodedPath)
            assertEquals(null, request.header("Cookie"))
            starts++
            qrReply(request)
        }
        login.begin()
        login.clear()
        assertEquals(RedfruitQrResult.Expired, login.poll())
        login.begin()
        assertEquals(2, starts)
    }

    private fun login(respond: (Request) -> Response): RedfruitQrLogin =
        RedfruitQrLogin(OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build())

    private fun qrReply(request: Request): Response = reply(
        request,
        data = JSONObject().put("expire_time", 60).put("token", "website-qr-token")
            .put("qrcode", "data:image/png;base64," + Base64.getEncoder().encodeToString(IMAGE)),
    ).addHeader("Set-Cookie", "passport_csrf_token=csrf-only; Path=/; Secure").build()

    private fun ok(request: Request, data: JSONObject): Response = reply(request, data = data).build()

    private fun reply(request: Request, code: Int = 200, data: JSONObject = JSONObject()): Response.Builder =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(JSONObject().put("message", "success").put("data", data).toString().toResponseBody())

    private companion object {
        // The production parser only needs a bounded PNG signature and IHDR dimensions.
        // Keeping this fixture binary avoids requiring java.desktop in unit-test workers.
        val IMAGE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x0d,
            0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x20,
            0x00, 0x00, 0x00, 0x20,
            0x08, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
    }
}
