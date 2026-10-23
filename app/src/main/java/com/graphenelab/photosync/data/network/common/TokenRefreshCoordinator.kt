package com.graphenelab.photosync.data.network.common

import com.graphenelab.photosync.domain.repositroy.IOauthTokenRepository
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a request that was rejected with `401` should do next.
 *
 * * [RetryWith] resend the original request exactly once, carrying the token named here.
 * * [GiveUp] leave the response untouched so the caller sees the real failure and can recover.
 */
sealed interface RefreshAttempt {

    /** Resend with [accessToken], which was either freshly obtained or already stored. */
    data class RetryWith(val accessToken: String) : RefreshAttempt

    /** Nothing can be recovered for this request; keep the response as it came back. */
    data object GiveUp : RefreshAttempt
}

/**
 * Turns a stored refresh token into a usable access token, and decides what a request that was
 * rejected with `401` should do about it.
 *
 * The whole decision runs under one lock, so a burst of concurrent requests that all fail with the
 * same expired token costs a single refresh instead of one refresh each: the first caller refreshes,
 * the others find the replacement already stored and simply resend with it.
 *
 * Failures are kept apart on purpose. A rejected credential is terminal and is dropped so the user can
 * sign in again, while a network problem is not, so the session stays intact and the next attempt can
 * still succeed. A refresh that is still in flight when the user signs out, or when another account
 * takes over, checks what is actually stored before writing anything back, and discards its result
 * rather than resurrecting or overwriting a session it no longer belongs to.
 */
@Singleton
class TokenRefreshCoordinator @Inject constructor(
    private val tokenRepository: IOauthTokenRepository,
    private val refreshClient: ITokenRefreshClient,
) {

    /**
     * Monotonic clock used for the cooldown. Kept as an overridable property rather than a constructor
     * parameter because a defaulted parameter cannot be satisfied by the dependency graph; a test can
     * replace it to move time forward without waiting.
     */
    internal var clock: () -> Long = { System.nanoTime() }

    /** When the most recent refresh failed, in [clock] units; `null` once one succeeds. */
    private var lastFailureAt: Long? = null

    private val lock = ReentrantLock()

    /**
     * Decides how the caller should handle a request that came back `401` while carrying
     * [failedAccessToken]. Blocks while another thread is refreshing, then re-reads the stored state
     * instead of spending a second refresh token.
     */
    fun attempt(failedAccessToken: String?): RefreshAttempt {
        if (!lock.tryLock(MAX_WAITER_WAIT_MS, TimeUnit.MILLISECONDS)) {
            // A refresh is still running and did not finish in time. Returning the untouched response
            // is better than holding this request open indefinitely or starting a competing refresh.
            return RefreshAttempt.GiveUp
        }
        try {
            val storedAccessToken = tokenRepository.getAccessToken()
            if (!storedAccessToken.isNullOrBlank() && storedAccessToken != failedAccessToken) {
                // Someone already recovered this token, so this request only has to be resent with it.
                return RefreshAttempt.RetryWith(storedAccessToken)
            }

            if (isCoolingDown()) {
                // A refresh just failed; wait for the cooldown before spending another attempt.
                return RefreshAttempt.GiveUp
            }

            val refreshToken = tokenRepository.getRefreshToken()
            if (refreshToken.isNullOrBlank()) {
                // Nothing left to recover with: this device is signed out.
                return RefreshAttempt.GiveUp
            }

            return try {
                val refreshed = refreshClient.refresh(refreshToken)

                // Storage may have been cleared, or rewritten with another account's tokens, while
                // this refresh was in flight. If the token we spent is no longer the stored one, this
                // result belongs to a session that is gone and must not be written back.
                val stillStored = tokenRepository.getRefreshToken()
                if (stillStored != refreshToken) {
                    return RefreshAttempt.GiveUp
                }

                tokenRepository.saveTokens(
                    accessToken = refreshed.accessToken,
                    refreshToken = refreshed.refreshToken ?: refreshToken,
                )
                lastFailureAt = null
                RefreshAttempt.RetryWith(refreshed.accessToken)
            } catch (rejection: TokenRefreshException.Permanent) {
                // The credential is dead. Keeping it would only repeat the same rejection, so drop the
                // stored session and let the caller route the user back to sign-in.
                lastFailureAt = null
                tokenRepository.clearTokens()
                RefreshAttempt.GiveUp
            } catch (transient: TokenRefreshException.Transient) {
                // Nothing here shows the credential is unusable, so the session stays exactly as it
                // was and the next attempt after the cooldown can still succeed.
                lastFailureAt = clock()
                RefreshAttempt.GiveUp
            } catch (unexpected: Exception) {
                // Anything unrecognised is treated the same way as a transient failure: never destroy
                // a session that might still be recoverable.
                lastFailureAt = clock()
                RefreshAttempt.GiveUp
            }
        } finally {
            lock.unlock()
        }
    }

    private fun isCoolingDown(): Boolean {
        val failedAt = lastFailureAt ?: return false
        val elapsedMillis = (clock() - failedAt) / NANOS_PER_MILLI
        return elapsedMillis < REFRESH_COOLDOWN_MS
    }

    private companion object {
        /** Slightly longer than the refresh client's own call timeout, so a waiter sees the outcome. */
        const val MAX_WAITER_WAIT_MS = 11_000L

        /** Minimum pause after a failed refresh, so a burst of failures cannot hammer the provider. */
        const val REFRESH_COOLDOWN_MS = 5_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
