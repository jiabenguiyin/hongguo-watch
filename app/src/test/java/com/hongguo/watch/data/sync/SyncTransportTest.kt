package com.hongguo.watch.data.sync

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

class SyncTransportTest {
    @Test
    fun pairingSharesOnlyThePublicCodeAndDoesNotExposeSecretsInDiagnosticStrings() = runBlocking {
        var pollRequest: Request? = null
        val client = clientWithResponse { request ->
            when (request.url.encodedPath) {
                "/v1/pair/start" -> reply(request, body = ticketJson().toString())
                "/v1/pair/poll" -> {
                    pollRequest = request
                    reply(
                        request,
                        body = JSONObject()
                            .put("status", "approved")
                            .put("token", TOKEN)
                            .put("accountId", ACCOUNT_ID)
                            .put("accountName", "tester")
                            .toString(),
                    )
                }
                else -> reply(request, code = 404)
            }
        }
        val transport = SyncTransport("HTTPS://EXAMPLE.COM:443", client)
        assertTrue(transport.configured)
        val ticket = transport.beginPairing()
        assertEquals(BASE, transport.origin)
        assertEquals("${BASE}pair?user_code=$USER_CODE", ticket.verificationUri)
        assertEquals("${BASE}pair", ticket.verificationUriBase)
        assertFalse(ticket.toString().contains(DEVICE_CODE))
        val result = transport.poll(ticket) as PairingResult.Approved
        val request = checkNotNull(pollRequest)
        val body = Buffer()
        checkNotNull(request.body).writeTo(body)
        assertEquals(DEVICE_CODE, JSONObject(body.readUtf8()).getString("deviceCode"))
        assertFalse(request.url.toString().contains(DEVICE_CODE))
        assertEquals(null, request.header("Authorization"))
        assertEquals(TOKEN, result.session.token)
        assertEquals(ACCOUNT_ID, result.session.accountId)
        assertFalse(result.toString().contains(TOKEN))
    }

    @Test
    fun pairingRejectsAnotherOriginAndAdditionalQueryParameters() {
        val invalidUrls = listOf(
            "http://example.com/pair?user_code=$USER_CODE",
            "https://other.example/pair?user_code=$USER_CODE",
            "${BASE}pair?user_code=$USER_CODE&deviceCode=$DEVICE_CODE",
        )
        for (url in invalidUrls) {
            val transport = SyncTransport(BASE, clientWithResponse { request ->
                reply(request, body = ticketJson().put("verificationUri", url).toString())
            })
            assertThrows(SyncServiceException::class.java) { runBlocking { transport.beginPairing() } }
        }
        assertFalse(SyncTransport("http://example.com").configured)
        assertFalse(SyncTransport("https://@example.com").configured)
    }

    @Test
    fun pollingCanRetryRateLimitAndAuthenticatedRequestsRejectAnExpiredOrForeignSession() {
        var authHeader: String? = null
        val client = clientWithResponse { request ->
            if (request.url.encodedPath == "/v1/pair/poll") {
                reply(request, code = 429)
            } else {
                authHeader = request.header("Authorization")
                reply(request, code = 401)
            }
        }
        val transport = SyncTransport(BASE, client)
        val ticket = PairingTicket(DEVICE_CODE, USER_CODE, "${BASE}pair?user_code=$USER_CODE", "${BASE}pair", 300, 3)
        assertEquals(PairingResult.Pending, runBlocking { transport.poll(ticket) })
        val session = SyncSession(BASE, TOKEN, ACCOUNT_ID, "tester")
        assertThrows(SyncUnauthorizedException::class.java) { runBlocking { transport.exchange(session, JSONObject()) } }
        assertEquals("Bearer $TOKEN", authHeader)
        val foreignSession = session.copy(origin = "https://other.example/")
        val offline = SyncTransport(BASE, clientWithResponse { reply(it, code = 500) })
        assertThrows(SyncUnauthorizedException::class.java) { runBlocking { offline.exchange(foreignSession, JSONObject()) } }
    }

    @Test
    fun serviceRedirectIsNeverFollowed() {
        MockWebServer().use { service ->
            MockWebServer().use { destination ->
                destination.enqueue(MockResponse().setBody(ticketJson().toString()))
                service.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/other")))
                val client = OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .addInterceptor { chain ->
                        // Route the original request to the local server without requiring a test CA.
                        val request = chain.request()
                        chain.proceed(request.newBuilder().url(service.url(request.url.encodedPath)).build())
                    }
                    .build()
                val transport = SyncTransport(BASE, client)
                assertThrows(SyncServiceException::class.java) { runBlocking { transport.beginPairing() } }
                assertEquals(1, service.requestCount)
                assertEquals(0, destination.requestCount)
            }
        }
    }

    private fun ticketJson(): JSONObject = JSONObject()
        .put("deviceCode", DEVICE_CODE)
        .put("userCode", USER_CODE)
        .put("verificationUri", "${BASE}pair?user_code=$USER_CODE")
        .put("verificationUriBase", "${BASE}pair")
        .put("expiresIn", 300)
        .put("interval", 3)

    private fun clientWithResponse(respond: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()

    private fun reply(request: Request, code: Int = 200, body: String = ""): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Test response")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private companion object {
        const val BASE = "https://example.com/"
        const val USER_CODE = "WXYZ-ABCD"
        val DEVICE_CODE = "d".repeat(43)
        val TOKEN = "t".repeat(43)
        val ACCOUNT_ID = "u".repeat(22)
    }
}
