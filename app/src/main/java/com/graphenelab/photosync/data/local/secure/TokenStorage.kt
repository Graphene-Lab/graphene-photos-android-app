package com.graphenelab.photosync.data.local.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.auth0.android.jwt.JWT
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject

class TokenStorage @Inject constructor(@ApplicationContext private val context: Context) {

    private val storageLock = Any()
    private var sharedPreferences: SharedPreferences? = null
    private var resetRequired = false

    private val masterKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun getEncryptedPreferences(): SharedPreferences {
        if (resetRequired) clearTokens()
        return sharedPreferences ?: EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also { sharedPreferences = it }
    }

    fun saveTokens(accessToken: String, refreshToken: String) {
        withTokenPreferences { preferences ->
            with(preferences.edit()) {
                putString("access_token", accessToken)
                putString("refresh_token", refreshToken)
                apply()
            }
        }
    }

    fun getAccessToken(): String? = readToken("access_token")

    fun getRefreshToken(): String? = readToken("refresh_token")

    fun clearTokens() {
        synchronized(storageLock) {
            // This file holds only IAM tokens and their keysets. No decryption is needed to clear it.
            // Keep the shared Keystore key and the other encrypted preference files intact.
            sharedPreferences = null
            resetRequired = true
            if (!context.deleteSharedPreferences(PREF_NAME)) {
                throw IOException("Unable to clear encrypted sign-in storage")
            }
            resetRequired = false
        }
    }

    private fun readToken(key: String): String? = try {
        withTokenPreferences { it.getString(key, null) }
    } catch (exception: Exception) {
        when (exception) {
            is GeneralSecurityException, is SecurityException, is IOException -> {
                // A persistent storage failure means signed out. Token writes still fail visibly
                // if encryption is unavailable, rather than persisting credentials as plaintext.
                Log.w(TAG, "Encrypted sign-in storage is unavailable; sign-in is required", exception)
                null
            }
            else -> throw exception
        }
    }

    private fun <T> withTokenPreferences(operation: (SharedPreferences) -> T): T =
        synchronized(storageLock) {
            try {
                operation(getEncryptedPreferences())
            } catch (exception: GeneralSecurityException) {
                operation(recreateTokenPreferences(exception))
            } catch (exception: SecurityException) {
                operation(recreateTokenPreferences(exception))
            } catch (exception: IOException) {
                operation(recreateTokenPreferences(exception))
            }
        }

    private fun recreateTokenPreferences(exception: Exception): SharedPreferences {
        Log.w(TAG, "Resetting unreadable encrypted sign-in storage", exception)
        clearTokens()
        // Retry once. Reuse the existing wrapping key; other stores also depend on it.
        return getEncryptedPreferences()
    }

    fun getEmail(): String? {
        val accessToken = getAccessToken()
        return if (accessToken != null) {
            try {
                val jwt = JWT(accessToken)
                jwt.getClaim("email").asString()
            } catch (e: Exception) {
                // Handle potential decoding errors
                null
            }
        } else {
            null
        }
    }

    private companion object {
        const val PREF_NAME = "secret_shared_prefs"
        const val TAG = "TokenStorage"
    }
}
