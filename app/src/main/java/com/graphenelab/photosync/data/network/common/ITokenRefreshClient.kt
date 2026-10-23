package com.graphenelab.photosync.data.network.common

/**
 * The pair produced by a successful refresh.
 *
 * [refreshToken] is the rotated token when the identity provider hands out a new one. It is `null`
 * when the response carries none, which means the token that was just spent stays valid and must be
 * kept in storage rather than dropped.
 */
class RefreshedTokens(
    val accessToken: String,
    val refreshToken: String?,
)

/**
 * Raised when a refresh cannot complete. The two kinds have to stay separated because they lead to
 * opposite decisions about the stored session.
 *
 * * [Permanent] proves the credential itself is rejected, so keeping it would only repeat the same
 *   failure and the user has to sign in again.
 * * [Transient] proves nothing about the credential, so storage stays untouched and a later attempt
 *   can still recover the session.
 */
sealed class TokenRefreshException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The refresh credential is invalid, revoked or expired. Sign-in recovery is required. */
    class Permanent(message: String, cause: Throwable? = null) : TokenRefreshException(message, cause)

    /** The attempt could not be completed or was not answered; retrying later is still worthwhile. */
    class Transient(message: String, cause: Throwable? = null) : TokenRefreshException(message, cause)
}

/**
 * Obtains a brand-new access token by spending a stored refresh token.
 *
 * Kept behind an interface so neither the interceptor nor the coordinator depends on a concrete HTTP
 * client, and so every outcome can be driven from a test without opening a socket.
 */
interface ITokenRefreshClient {

    /**
     * Exchanges [refreshToken] for a new access token.
     *
     * @return the new tokens on success
     * @throws TokenRefreshException.Permanent when the credential is rejected for good
     * @throws TokenRefreshException.Transient when the attempt could not be completed
     */
    fun refresh(refreshToken: String): RefreshedTokens
}
