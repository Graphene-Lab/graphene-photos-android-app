package com.graphenelab.photosync.data.network.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What the coordinator decides when a request was rejected because its access token had expired: one
 * recovery per burst, terminal rejections dropped, network problems kept, and results that arrive too
 * late never written back.
 */
class TokenRefreshCoordinatorTest {

    private fun coordinator(
        store: FakeTokenStore,
        refresher: FakeRefreshClient,
        clockMillis: () -> Long = { 0L },
    ): TokenRefreshCoordinator = TokenRefreshCoordinator(store, refresher).apply {
        clock = { clockMillis() * NANOS_PER_MILLI }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** Mirrors the coordinator's own cooldown, which is private. */
        const val COOLDOWN_MS = 5_000L

        fun await(latch: CountDownLatch, what: String) {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "$what did not happen in time")
        }
    }

    @Test
    fun aValidRefreshTokenRecoversTheAccessTokenAndKeepsARotatedOne() {
        val store = FakeTokenStore(initialAccessToken = "expired", initialRefreshToken = "R")
        val refresher = FakeRefreshClient().apply { behavior = { RefreshedTokens("fresh", "R-rotated") } }

        val attempt = coordinator(store, refresher).attempt("expired")

        assertEquals(RefreshAttempt.RetryWith("fresh"), attempt)
        assertEquals(1, refresher.calls)
        assertEquals(0, store.clearCount)
        assertEquals(1, store.saveCount)
        assertEquals("fresh", store.accessToken)
        // A rotated refresh token replaces the one that was spent.
        assertEquals("R-rotated", store.refreshToken)
    }

    @Test
    fun withoutAnyRefreshTokenThereIsNothingToRecover() {
        val store = FakeTokenStore(initialAccessToken = "expired", initialRefreshToken = null)
        val refresher = FakeRefreshClient()

        val attempt = coordinator(store, refresher).attempt("expired")

        assertEquals(RefreshAttempt.GiveUp, attempt)
        assertEquals(0, refresher.calls)
        assertEquals(0, store.saveCount)
        assertEquals(0, store.clearCount)
        assertEquals("expired", store.accessToken)
    }

    @Test
    fun aBlankRefreshTokenIsTreatedAsAbsent() {
        val store = FakeTokenStore(initialAccessToken = "expired", initialRefreshToken = "   ")
        val refresher = FakeRefreshClient()

        assertEquals(RefreshAttempt.GiveUp, coordinator(store, refresher).attempt("expired"))
        assertEquals(0, refresher.calls)
    }

    @Test
    fun aRejectedCredentialDropsTheSessionSoSignInBecomesRequired() {
        val store = FakeTokenStore(initialAccessToken = "expired", initialRefreshToken = "revoked")
        val refresher = FakeRefreshClient().apply {
            behavior = { throw TokenRefreshException.Permanent("invalid_grant") }
        }

        val attempt = coordinator(store, refresher).attempt("expired")

        assertEquals(RefreshAttempt.GiveUp, attempt)
        assertEquals(1, refresher.calls)
        assertEquals(1, store.clearCount)
        assertEquals(0, store.saveCount)
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
    }

    @Test
    fun aTransientFailureKeepsTheSessionAndRecoversAfterTheCooldown() {
        val store = FakeTokenStore(initialAccessToken = "expired", initialRefreshToken = "R")
        var now = 0L
        val refresher = FakeRefreshClient().apply {
            behavior = { throw TokenRefreshException.Transient("connection reset") }
        }
        val coordinator = coordinator(store, refresher, clockMillis = { now })

        assertEquals(RefreshAttempt.GiveUp, coordinator.attempt("expired"))
        assertEquals(1, refresher.calls)
        // A network problem discards nothing: the session stays exactly as it was.
        assertEquals(0, store.clearCount)
        assertEquals(0, store.saveCount)
        assertEquals("expired", store.accessToken)
        assertEquals("R", store.refreshToken)

        // Straight away the cooldown holds, so no second attempt is burned.
        assertEquals(RefreshAttempt.GiveUp, coordinator.attempt("expired"))
        assertEquals(1, refresher.calls)

        refresher.behavior = { RefreshedTokens("fresh", null) }
        now += COOLDOWN_MS + 1
        assertEquals(RefreshAttempt.RetryWith("fresh"), coordinator.attempt("expired"))
        assertEquals(2, refresher.calls)
        assertEquals("fresh", store.accessToken)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun anUnexpectedFailureAlsoKeepsTheSessionIntact() {
        val store = FakeTokenStore(initialAccessToken = "expired", initialRefreshToken = "R")
        val refresher = FakeRefreshClient().apply {
            behavior = { throw IllegalStateException("parser exploded") }
        }

        assertEquals(RefreshAttempt.GiveUp, coordinator(store, refresher).attempt("expired"))
        assertEquals(1, refresher.calls)
        assertEquals(0, store.clearCount)
        assertEquals(0, store.saveCount)
        assertEquals("R", store.refreshToken)
    }

    @Test
    fun whenTheTokenWasAlreadyRecoveredTheRequestOnlyResends() {
        val store = FakeTokenStore(initialAccessToken = "fresh", initialRefreshToken = "R")
        val refresher = FakeRefreshClient().apply {
            behavior = { RefreshedTokens("must-not-be-used", null) }
        }

        val attempt = coordinator(store, refresher).attempt("expired")

        assertEquals(RefreshAttempt.RetryWith("fresh"), attempt)
        // The replacement was already stored, so no refresh token was spent at all.
        assertEquals(0, refresher.calls)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun aBurstOfConcurrentFailuresCostsASingleRefreshOperation() {
        val store = FakeTokenStore(initialAccessToken = "expired", initialRefreshToken = "R")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val refresher = FakeRefreshClient().apply {
            onRefresh = { started.countDown() }
            behavior = {
                assertTrue(release.await(5, TimeUnit.SECONDS), "refresh was never released")
                RefreshedTokens("fresh", null)
            }
        }
        val coordinator = TokenRefreshCoordinator(store, refresher)
        val pool = Executors.newFixedThreadPool(5)

        try {
            val first = pool.submit<RefreshAttempt> { coordinator.attempt("expired") }
            await(started, "refresh")

            // Four more requests fail with the same expired token while the first one is refreshing.
            val waiters = (1..4).map { pool.submit<RefreshAttempt> { coordinator.attempt("expired") } }
            Thread.sleep(250)
            release.countDown()

            assertEquals(RefreshAttempt.RetryWith("fresh"), first.get(5, TimeUnit.SECONDS))
            waiters.forEach { waiter ->
                assertEquals(RefreshAttempt.RetryWith("fresh"), waiter.get(5, TimeUnit.SECONDS))
            }
            // One refresh for the whole burst; the rest only resent with the token it produced.
            assertEquals(1, refresher.calls)
            assertEquals(1, store.saveCount)
            assertEquals("fresh", store.accessToken)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun aLateRefreshDoesNotRestoreTokensAfterSignOut() {
        val store = FakeTokenStore(initialAccessToken = "expired", initialRefreshToken = "R")
        val refresher = FakeRefreshClient().apply {
            behavior = {
                // The user signed out while this refresh was in flight.
                store.clearTokens()
                RefreshedTokens("stale-fresh", null)
            }
        }

        val attempt = coordinator(store, refresher).attempt("expired")

        assertEquals(RefreshAttempt.GiveUp, attempt)
        assertEquals(1, refresher.calls)
        // Nothing may be written back over a signed-out device.
        assertEquals(0, store.saveCount)
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
    }

    @Test
    fun aLateRefreshDoesNotOverwriteAnotherAccountSession() {
        val store = FakeTokenStore(initialAccessToken = "expired", initialRefreshToken = "R")
        val refresher = FakeRefreshClient().apply {
            behavior = {
                // A different account took over while this refresh was running.
                store.saveTokens("other-access", "other-refresh")
                RefreshedTokens("stale-access", null)
            }
        }

        val attempt = coordinator(store, refresher).attempt("expired")

        assertEquals(RefreshAttempt.GiveUp, attempt)
        assertEquals(1, refresher.calls)
        // The new account's tokens stay exactly as that account wrote them.
        assertEquals("other-access", store.accessToken)
        assertEquals("other-refresh", store.refreshToken)
        assertEquals(1, store.saveCount)
    }
}
