package com.graphenelab.photosync.manager

import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import net.openid.appauth.*
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.core.net.toUri
import com.graphenelab.photosync.domain.repositroy.IOauthTokenRepository
import com.graphenelab.photosync.manager.interfaces.IOAuthManager
import dagger.hilt.android.scopes.ViewModelScoped

@ViewModelScoped
class OAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val oauthTokenRepository: IOauthTokenRepository
) : IOAuthManager {
    private val authService = AuthorizationService(context)
    private val serviceConfig = AuthorizationServiceConfiguration(
        "https://id.graphenelab.cloud/realms/cloud/protocol/openid-connect/auth".toUri(),
        "https://id.graphenelab.cloud/realms/cloud/protocol/openid-connect/token".toUri()
    )

    companion object {
        private const val TAG = "OAuthManager"
    }

    override fun getAuthIntent(): Intent {
        val authRequest = AuthorizationRequest.Builder(
            serviceConfig,
            "cloud-mobile-app",
            ResponseTypeValues.CODE,
            "com.graphenelab.photosync://redirect".toUri()
        ).setScope("openid profile")
            .setPrompt("login")
            .build()

        return authService.getAuthorizationRequestIntent(authRequest)
    }

    override suspend fun exchangeCodeForTokens(intent: Intent) {
        val resp = AuthorizationResponse.fromIntent(intent)
        val ex = AuthorizationException.fromIntent(intent)

        // First, check for an explicit exception from the authorization flow
        ex?.let { throw it }

        // If there's no exception, we must have a response. If not, it's an unexpected state.
        if (resp == null) {
            throw IllegalStateException("Intent did not contain a valid authorization response or exception.")
        }

        // Exchange the authorization code for tokens
        val tokenRequest = resp.createTokenExchangeRequest()
        val tokenResponse = suspendCancellableCoroutine<TokenResponse> { continuation ->
            authService.performTokenRequest(tokenRequest) { response, exception ->
                if (!continuation.isActive) return@performTokenRequest
                if (exception != null) {
                    continuation.resumeWithException(exception)
                } else if (response != null) {
                    continuation.resume(response)
                } else {
                    // Should not happen, but handle it defensively
                    continuation.resumeWithException(IllegalStateException("Token request yielded no response or exception."))
                }
            }
        }

        // Validate that the required tokens are present before using them
        val accessToken =
            requireNotNull(tokenResponse.accessToken) { "Access Token was null in the token response." }
        val refreshToken =
            requireNotNull(tokenResponse.refreshToken) { "Refresh Token was null in the token response." }

        // Save and return the validated access token
        currentCoroutineContext().ensureActive()
        oauthTokenRepository.saveTokens(accessToken, refreshToken)
        Log.w(TAG, "exchangeCodeForTokens: access token saved")
        if (BuildConfig.DEBUG) Log.d(TAG, "saved accessToken: $accessToken")
//        return accessToken
    }
}
