package com.graphenelab.photosync.data.local.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.graphenelab.photosync.domain.model.SessionDataModel
import com.graphenelab.photosync.domain.model.CloudSpaceCredentials
import com.graphenelab.photosync.domain.repositroy.ISessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit
import kotlinx.serialization.encodeToString

@Singleton
class SessionRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : ISessionRepository {

    companion object {
        private const val PREF_NAME = "encrypted_session_prefs"
        private const val SESSION_KEY = "session_data"
        private const val TAG = "SessionRepository"
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
        private const val CLOUD_CREDENTIALS_KEY = "cloud_space_credentials"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        createEncryptedPreferencesWithRecovery()
    }

    private fun createEncryptedPreferencesWithRecovery(): SharedPreferences {
        return try {
            createEncryptedPreferences()
        } catch (e: GeneralSecurityException) {
            recreateSessionPreferences(e)
        } catch (e: SecurityException) {
            recreateSessionPreferences(e)
        } catch (e: IOException) {
            recreateSessionPreferences(e)
        }
    }

    private fun createEncryptedPreferences(): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun recreateSessionPreferences(exception: Exception): SharedPreferences {
        Log.w(TAG, "Resetting unreadable encrypted session storage", exception)
        // Android also invalidates its cached SharedPreferences and removes the .bak file.
        // Never delete the wrapping key: token and CSE key stores use the same alias.
        if (!context.deleteSharedPreferences(PREF_NAME)) {
            throw IOException("Unable to clear encrypted session storage", exception)
        }
        // Retry once and propagate failure; session secrets must never fall back to plaintext.
        return createEncryptedPreferences()
    }

    override suspend fun saveSession(session: SessionDataModel) = withContext(Dispatchers.IO) {
        try {
            val sessionJson = json.encodeToString(session)
            encryptedPrefs.edit {
                putString(SESSION_KEY, sessionJson)
            }
            Log.d(TAG, "Session saved successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session", e)
            throw e
        }
    }

    override suspend fun saveCommunicationSession(session: com.graphenelab.communication.crypto.Session) = withContext(Dispatchers.IO) {
        val sessionData = SessionDataModel(
            clientId = session.clientId,
            serverId = session.serverId,
            deviceKey = session.deviceKey,
            encryptionType = session.encryptionType,
            symmetricKey = session.symmetricKey?.encoded,
            iv = session.iv,
            pin = session.pin,
            privateKey = session.privateKey?.encoded,
            qrKey = session.qRkey,
            publicKeyB64 = session.publicKeyB64
        )
        saveSession(sessionData)
    }

    override suspend fun loadSession(): SessionDataModel? = withContext(Dispatchers.IO) {
        try {
            val sessionJson = encryptedPrefs.getString(SESSION_KEY, null)
            if (sessionJson != null) {
                Log.d(TAG, "Session loaded successfully")
                json.decodeFromString<SessionDataModel>(sessionJson)
            } else {
                Log.d(TAG, "No saved session found")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session", e)
            null
        }
    }

    override suspend fun hasSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            encryptedPrefs.contains(SESSION_KEY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check session presence", e)
            false
        }
    }

    override suspend fun loadCommunicationSession(): com.graphenelab.communication.crypto.Session? = withContext(Dispatchers.IO) {
        try {
            val sessionData = loadSession() ?: return@withContext null

            val session = com.graphenelab.communication.crypto.Session()
            session.clientId = sessionData.clientId
            session.serverId = sessionData.serverId
            session.deviceKey = sessionData.deviceKey
            session.encryptionType = sessionData.encryptionType

            // Handle symmetric key conversion
            sessionData.symmetricKey?.let { keyData ->
                if (sessionData.encryptionType != null) {
                    val secretKey = javax.crypto.spec.SecretKeySpec(keyData, 0, keyData.size, sessionData.encryptionType)
                    session.symmetricKey = secretKey
                }
            }

            session.iv = sessionData.iv
            sessionData.pin?.let { session.pin = it }

            // Handle private key conversion
            sessionData.privateKey?.let { keyData ->
                try {
                    val keyFactory = java.security.KeyFactory.getInstance("RSA")
                    val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyData)
                    val privateKey = keyFactory.generatePrivate(keySpec)
                    session.privateKey = privateKey
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore private key", e)
                }
            }

            session.qRkey = sessionData.qrKey
            session.publicKeyB64 = sessionData.publicKeyB64

            Log.d(TAG, "Communication session loaded successfully")
            session
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load communication session", e)
            null
        }
    }

    override suspend fun saveCloudSpaceCredentials(credentials: CloudSpaceCredentials) = withContext(Dispatchers.IO) {
        try {
            val credentialsJson = json.encodeToString(credentials)
            encryptedPrefs.edit {
                putString(CLOUD_CREDENTIALS_KEY, credentialsJson)
            }
            Log.d(TAG, "Cloud space credentials saved successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cloud space credentials", e)
            throw e
        }
    }

    override suspend fun loadCloudSpaceCredentials(): CloudSpaceCredentials? = withContext(Dispatchers.IO) {
        try {
            val credentialsJson = encryptedPrefs.getString(CLOUD_CREDENTIALS_KEY, null)
            if (credentialsJson != null) {
                Log.d(TAG, "Cloud space credentials loaded successfully")
                json.decodeFromString<CloudSpaceCredentials>(credentialsJson)
            } else {
                Log.d(TAG, "No saved cloud space credentials found")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cloud space credentials", e)
            null
        }
    }

    override suspend fun hasCloudSpaceCredentials(): Boolean = withContext(Dispatchers.IO) {
        try {
            encryptedPrefs.contains(CLOUD_CREDENTIALS_KEY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check cloud credentials presence", e)
            false
        }
    }

    override fun clearAuthState() {
        try {
            encryptedPrefs.edit(commit = true) {
                remove(SESSION_KEY)
                remove(CLOUD_CREDENTIALS_KEY)
            }
            Log.d(TAG, "Persisted auth state cleared successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear persisted auth state", e)
        }
    }

    /**
     * Manually clear all session data - useful for logout or when corruption is detected
     */
    suspend fun clearSession() = withContext(Dispatchers.IO) {
        try {
            encryptedPrefs.edit { clear() }
            Log.d(TAG, "Session data cleared successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear session data", e)
        }
    }
}
