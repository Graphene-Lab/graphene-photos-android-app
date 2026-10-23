package com.graphenelab.photosync.data.network.common

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Requests a new access token from the IAM provider with the OAuth 2.0 `refresh_token` grant.
 *
 * The endpoint and the client id are the same ones the sign-in flow uses, so the token issued here
 * belongs to the very session that [com.graphenelab.photosync.manager.OAuthManager] started.
 *
 * The call deliberately runs on its own bare client, which carries no authentication interceptor.
 * Sending a refresh through the interceptor that reacts to `401` would make a refresh trigger
 * another refresh and recurse.
 */
class IamTokenRefreshClient internal constructor(
    private val callFactory: Call.Factory,
    private val tokenEndpoint: HttpUrl,
    private val clientId: String,
    private val json: Gson = Gson(),
) : ITokenRefreshClient {

    constructor() : this(bareClient(), TOKEN_ENDPOINT.toHttpUrl(), CLIENT_ID)

    override fun refresh(refreshToken: String): RefreshedTokens {
        val form = FormBody.Builder()
            .add("grant_type", GRANT_TYPE)
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()

        val request = Request.Builder()
            .url(tokenEndpoint)
            .post(form)
            .header("Accept", "application/json")
            .build()

        val response = try {
            callFactory.newCall(request).execute()
        } catch (failure: IOException) {
            // No answer at all. Nothing here shows the credential is unusable, so the stored session
            // must survive and a later attempt can still recover it.
            throw TokenRefreshException.Transient(
                "Token refresh could not reach the identity provider",
                failure,
            )
        }

        return response.use { delivered ->
            val body = try {
                delivered.body?.string()
            } catch (failure: IOException) {
                throw TokenRefreshException.Transient("Token refresh response could not be read", failure)
            }

            if (!delivered.isSuccessful) {
                throw rejection(delivered.code, body)
            }

            val payload = parse(body)
                ?: throw TokenRefreshException.Transient("Token refresh response carried no access token")

            val accessToken = payload.accessToken
            if (accessToken.isNullOrBlank()) {
                // A success without a token cannot be used, but it is not proof that the credential
                // is dead either, so keep the session for a later retry.
                throw TokenRefreshException.Transient("Token refresh response carried no access token")
            }

            RefreshedTokens(
                accessToken = accessToken,
                refreshToken = payload.refreshToken?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** Parses a success body that may be absent or empty without letting the parser throw. */
    private fun parse(body: String?): RefreshPayload? {
        if (body.isNullOrBlank()) return null
        return try {
            json.fromJson(body, RefreshPayload::class.java)
        } catch (failure: JsonParseException) {
            throw TokenRefreshException.Transient("Malformed token refresh response", failure)
        }
    }

    /**
     * Maps an unsuccessful answer onto the two kinds of failure. Only a rejection that names the
     * credential counts as permanent; everything else stays retryable.
     */
    private fun rejection(status: Int, body: String?): TokenRefreshException {
        val errorCode = errorCodeOf(body)
        val permanentlyRejected = status in PERMANENT_STATUS_CODES || errorCode in PERMANENT_ERROR_CODES

        return if (permanentlyRejected) {
            val suffix = if (errorCode != null) ", error=$errorCode" else ""
            TokenRefreshException.Permanent(
                "Identity provider rejected the refresh token (HTTP $status$suffix)"
            )
        } else {
            TokenRefreshException.Transient("Identity provider returned HTTP $status during token refresh")
        }
    }

    /** Reads the OAuth error code from a body that may be absent, blank, or not JSON at all. */
    private fun errorCodeOf(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            json.fromJson(body, RefreshPayload::class.java)?.error
        } catch (failure: JsonParseException) {
            null
        } catch (failure: IllegalStateException) {
            null
        }
    }

    /** Token payload, trimmed to the fields a refresh response can carry. */
    private class RefreshPayload {
        @SerializedName("access_token")
        var accessToken: String? = null

        @SerializedName("refresh_token")
        var refreshToken: String? = null

        @SerializedName("error")
        var error: String? = null
    }

    private companion object {
        /** Same token endpoint as `OAuthManager.serviceConfig`. */
        private const val TOKEN_ENDPOINT =
            "https://id.graphenelab.cloud/realms/cloud/protocol/openid-connect/token"

        /** Same registered client as the authorization request in `OAuthManager`. */
        private const val CLIENT_ID = "cloud-mobile-app"
        private const val GRANT_TYPE = "refresh_token"

        /**
         * OAuth 2.0 responses that state the grant itself is invalid. The credential cannot be used
         * again, so the session has to be dropped rather than retried.
         */
        private val PERMANENT_STATUS_CODES = setOf(400, 401, 403)
        private val PERMANENT_ERROR_CODES = setOf("invalid_grant", "invalid_client", "unauthorized_client")

        /** A bare client on purpose: no [AuthInterceptor], so a refresh can never recurse. */
        private fun bareClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
