package com.graphenelab.photosync.data.network.common

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException

/**
 * Exercises the real `AuthInterceptor` through the real OkHttp call machinery against a stub
 * transport. The last interceptor in the chain never proceeds further, so no socket is ever opened
 * and nothing can reach a network; every reply is taken from [queued], in order, and every request
 * that actually leaves the interceptor is recorded in [sent].
 */
class AuthInterceptorTest {

    private class Reply(val code: Int, val body: String? = null, val failure: IOException? = null)

    private val queued = ArrayDeque<Reply>()
    private val sent = mutableListOf<Request>()

    private lateinit var store: FakeTokenStore
    private lateinit var refresher: FakeRefreshClient
    private lateinit var interceptor: AuthInterceptor
    private lateinit var client: OkHttpClient
    private var clockNanos = 0L

    @BeforeEach
    fun setUp() {
        queued.clear()
        sent.clear()
        clockNanos = 0L
        store = FakeTokenStore(initialAccessToken = "expired", initialRefreshToken = "R")
        refresher = FakeRefreshClient()
        val coordinator = TokenRefreshCoordinator(store, refresher).apply {
            clock = { clockNanos }
        }
        interceptor = AuthInterceptor(store, coordinator)
        client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                sent += request
                val reply = queued.removeFirst()
                reply.failure?.let { throw it }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(reply.code)
                    .message("stub")
                    .body((reply.body ?: "").toResponseBody(JSON_TYPE))
                    .build()
            })
            .build()
        // The resend has to start as a brand-new call: the incoming chain can only be proceeded once.
        interceptor.clientProvider = { client }
    }

    private fun send(path: String = "me/cloud-space/status"): Response =
        client.newCall(Request.Builder().url(BASE_URL + path).get().build()).execute()

    /** Moves the shared clock so a cooldown that blocked recovery has clearly elapsed. */
    private fun advanceClockMillis(millis: Long) {
        clockNanos += millis * NANOS_PER_MILLI
    }

    @Test
    fun anExpiredTokenIsReplacedAndTheOriginalRequestIsSentAgainExactlyOnce() {
        refresher.behavior = { RefreshedTokens("fresh", null) }
        queued += Reply(401)
        queued += Reply(200, """{"status":"READY"}""")

        val response = send()

        assertEquals(200, response.code)
        assertEquals(1, refresher.calls)
        assertEquals(0, store.clearCount)
        assertEquals(2, sent.size)
        assertEquals("Bearer expired", sent[0].header("Authorization"))
        assertEquals("Bearer fresh", sent[1].header("Authorization"))
        // The resend kept the original method and target, only the token changed.
        assertEquals("GET", sent[1].method)
        assertEquals(BASE_URL + "me/cloud-space/status", sent[1].url.toString())
        assertEquals("fresh", store.accessToken)
    }

    @Test
    fun aRotatedRefreshTokenIsStoredTogetherWithTheNewAccessToken() {
        refresher.behavior = { RefreshedTokens("fresh", "R2") }
        queued += Reply(401)
        queued += Reply(200, "{}")

        assertEquals(200, send("me/subscription-plan").code)

        assertEquals("fresh", store.accessToken)
        assertEquals("R2", store.refreshToken)
        assertEquals("Bearer fresh", sent[1].header("Authorization"))
    }

    @Test
    fun aRequestWithNoTokenAtAllIsSentAnonymouslyAndNothingIsRefreshed() {
        store.accessToken = null
        store.refreshToken = null
        queued += Reply(401)

        val response = send()

        assertEquals(401, response.code)
        assertEquals(0, refresher.calls)
        assertEquals(1, sent.size)
        // No stale credential is ever attached.
        assertNull(sent[0].header("Authorization"))
    }

    @Test
    fun aStaleAuthorizationFromTheCallerIsReplacedByTheStoredToken() {
        refresher.behavior = { RefreshedTokens("fresh", null) }
        queued += Reply(401)
        queued += Reply(200, "{}")

        val request = Request.Builder()
            .url(BASE_URL + "me/cloud-space/credentials")
            .header("Authorization", "Bearer stale-from-caller")
            .get()
            .build()
        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertNotNull(sent[0].header("Authorization"))
        assertEquals("Bearer expired", sent[0].header("Authorization"))
        assertEquals(1, refresher.calls)
        assertEquals(2, sent.size)
    }

    @Test
    fun aRejectedRefreshKeepsTheFailedResponseAndDropsTheSession() {
        refresher.behavior = { throw TokenRefreshException.Permanent("invalid_grant") }
        queued += Reply(401)

        val response = send()

        assertEquals(401, response.code)
        assertEquals(1, refresher.calls)
        assertEquals(1, sent.size)
        assertEquals(1, store.clearCount)
        assertEquals(0, store.saveCount)
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
    }

    @Test
    fun aTransientRefreshFailureKeepsTheSessionAndALaterRequestStillRecovers() {
        refresher.behavior = { throw TokenRefreshException.Transient("connection reset") }
        queued += Reply(401)

        assertEquals(401, send().code)
        assertEquals(1, refresher.calls)
        assertEquals(0, store.clearCount)
        assertEquals(0, store.saveCount)
        assertEquals("expired", store.accessToken)
        assertEquals("R", store.refreshToken)

        // The cooldown holds, so a request sent straight away is not retried against the provider.
        val before = sent.size
        queued += Reply(401)
        assertEquals(401, send().code)
        assertEquals(1, refresher.calls)
        assertEquals(before + 1, sent.size)

        // Once the cooldown has passed, the same path recovers and the request succeeds.
        refresher.behavior = { RefreshedTokens("fresh", null) }
        advanceClockMillis(COOLDOWN_MS + 1)
        queued += Reply(401)
        queued += Reply(200, """{"status":"READY"}""")

        assertEquals(200, send().code)
        assertEquals(2, refresher.calls)
        assertEquals("fresh", store.accessToken)
    }

    @Test
    fun aResentRequestThatFailsAgainIsNotRefreshedAThirdTime() {
        refresher.behavior = { RefreshedTokens("fresh", null) }
        queued += Reply(401)
        queued += Reply(401)

        val response = send()

        assertEquals(401, response.code)
        // One refresh and one resend: the resent request is never refreshed again.
        assertEquals(1, refresher.calls)
        assertEquals(2, sent.size)
        assertEquals("Bearer fresh", sent[1].header("Authorization"))
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 204, 403, 500, 503])
    fun onlyAnExpiredTokenTriggersARefresh(status: Int) {
        queued += Reply(status, "{}")

        val response = send()

        assertEquals(status, response.code)
        assertEquals(0, refresher.calls)
        assertEquals(1, sent.size)
        assertEquals("Bearer expired", sent[0].header("Authorization"))
    }

    @Test
    fun aDeletedAccountConflictPassesThroughUntouched() {
        queued += Reply(409, """{"error_code":"USER_NOT_FOUND"}""")

        val response = send("me/cloud-space/status")

        // The interceptor must not swallow or re-request a deleted-account conflict: the repository
        // still maps it to "account no longer exists, please sign in again".
        assertEquals(409, response.code)
        assertEquals(0, refresher.calls)
        assertEquals(1, sent.size)
        assertEquals("Bearer expired", sent[0].header("Authorization"))
    }

    @Test
    fun aTransportFailurePassesThroughWithoutTouchingTheSession() {
        queued += Reply(0, null, IOException("connection reset"))

        val thrown = runCatching { send() }.exceptionOrNull()
        assertNotNull(thrown)
        assertEquals(0, refresher.calls)
        assertEquals(1, sent.size)
        assertEquals(0, store.clearCount)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun withoutAClientTheFailedResponseIsReturnedUnchanged() {
        interceptor.clientProvider = null
        queued += Reply(401)

        val response = client.newCall(Request.Builder().url(BASE_URL + "me/cloud-space/status").build()).execute()

        assertEquals(401, response.code)
        assertEquals(0, refresher.calls)
    }

    private companion object {
        const val BASE_URL = "https://api.invalid/"
        val JSON_TYPE = "application/json".toMediaType()
        const val NANOS_PER_MILLI = 1_000_000L
        const val COOLDOWN_MS = 5_000L
    }
}
