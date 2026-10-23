package com.graphenelab.photosync.data.network.common

import com.graphenelab.photosync.domain.repositroy.IOauthTokenRepository
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory stand-in for the encrypted token store, so no test here touches SharedPreferences, the
 * Android keystore, or a real device.
 */
internal class FakeTokenStore(
    initialAccessToken: String? = null,
    initialRefreshToken: String? = null,
) : IOauthTokenRepository {

    @Volatile
    var accessToken: String? = initialAccessToken

    @Volatile
    var refreshToken: String? = initialRefreshToken

    @Volatile
    var saveCount = 0
        private set

    @Volatile
    var clearCount = 0
        private set

    override fun saveTokens(accessToken: String, refreshToken: String) {
        saveCount++
        this.accessToken = accessToken
        this.refreshToken = refreshToken
    }

    override fun getAccessToken(): String? = accessToken

    override fun getRefreshToken(): String? = refreshToken

    override fun clearTokens() {
        clearCount++
        accessToken = null
        refreshToken = null
    }

    override fun getEmail(): String? = null
}

/**
 * Programmable refresh transport. Never opens a socket: every outcome, including a thrown
 * classification, is decided by [behavior], so a test can pin down exactly what was attempted.
 */
internal class FakeRefreshClient : ITokenRefreshClient {

    @Volatile
    var behavior: (String) -> RefreshedTokens = { RefreshedTokens("fresh", null) }

    /** Set from a test that needs to hold the first refresh open while other callers queue up. */
    @Volatile
    var onRefresh: (() -> Unit)? = null

    private val counter = AtomicInteger()

    val calls: Int
        get() = counter.get()

    override fun refresh(refreshToken: String): RefreshedTokens {
        counter.incrementAndGet()
        onRefresh?.invoke()
        return behavior(refreshToken)
    }
}
