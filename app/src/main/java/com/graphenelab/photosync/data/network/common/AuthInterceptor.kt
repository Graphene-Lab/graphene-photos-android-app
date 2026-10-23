package com.graphenelab.photosync.data.network.common

import com.graphenelab.photosync.domain.repositroy.IOauthTokenRepository
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Marks the single resend that follows a successful token refresh.
 *
 * The mark travels with the request rather than being kept in shared state, so it stays correct when
 * several requests are in flight at the same time and cannot be confused by another thread.
 */
private object RetryAttemptTag

/**
 * Attaches the stored IAM access token to every outgoing request and recovers from an expired one.
 *
 * When a request comes back `401 Unauthorized`, this interceptor asks [TokenRefreshCoordinator] for a
 * fresh access token and then sends the original request one more time with that token. A request that
 * already carries the resend mark is never refreshed again, so an expired token costs at most one
 * refresh and one resend instead of looping. Only `401` counts as an expired token: every other
 * status code, including the `409 USER_NOT_FOUND` used for a deleted account, is passed through
 * untouched so the repositories keep their own meaning for it.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val oauthTokenRepository: IOauthTokenRepository,
    private val refreshCoordinator: TokenRefreshCoordinator,
) : Interceptor {

    /**
     * Supplies the client that hosts this interceptor, wired after construction by
     * [com.graphenelab.photosync.di.NetworkModule] because the client and the interceptor refer to
     * each other. A resend has to start as a brand-new call, since the incoming chain can only be
     * proceeded once. With no client available the failed response is returned unchanged.
     */
    @Volatile
    internal var clientProvider: (() -> OkHttpClient?)? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.tag(RetryAttemptTag::class.java) != null) {
            // Already the resend for this request: it carries the token it should use, and a second
            // refresh for the same request is not allowed.
            return chain.proceed(request)
        }

        val sentAccessToken = oauthTokenRepository.getAccessToken()
        val response = chain.proceed(withAuthorization(request, sentAccessToken))

        if (response.code != HTTP_UNAUTHORIZED) {
            return response
        }

        val client = clientProvider?.invoke() ?: return response

        val attempt = try {
            refreshCoordinator.attempt(sentAccessToken)
        } catch (_: Exception) {
            // A decision that went wrong must not lose or replace what the server already answered.
            return response
        }

        return when (attempt) {
            is RefreshAttempt.GiveUp -> response

            is RefreshAttempt.RetryWith -> {
                // Release the failed response before resending, so no connection is left held by a
                // reply that has already been superseded.
                response.close()
                client.newCall(resend(request, attempt.accessToken)).execute()
            }
        }
    }

    /** Replaces any credential already on the request with [accessToken], or makes it anonymous. */
    private fun withAuthorization(request: Request, accessToken: String?): Request {
        val builder = request.newBuilder()
        if (accessToken.isNullOrEmpty()) {
            // Never send a stale or caller-supplied credential: with no token the request stays
            // anonymous, which is what lets a later attempt recover from the refresh token.
            builder.removeHeader("Authorization")
        } else {
            builder.setHeader("Authorization", "Bearer $accessToken")
        }
        return builder.build()
    }

    /** The one and only resend of the original request, carrying the recovered token. */
    private fun resend(request: Request, accessToken: String): Request = request.newBuilder()
        .setHeader("Authorization", "Bearer $accessToken")
        .tag(RetryAttemptTag::class.java, RetryAttemptTag)
        .build()

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
