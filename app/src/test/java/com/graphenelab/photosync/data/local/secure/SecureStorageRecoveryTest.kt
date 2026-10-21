package com.graphenelab.photosync.data.local.secure

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.graphenelab.photosync.R
import com.graphenelab.photosync.data.repository.OauthTokenRepository
import com.graphenelab.photosync.domain.repositroy.IAppSettingsRepository
import com.graphenelab.photosync.domain.repositroy.ICseMasterKeyRepository
import com.graphenelab.photosync.domain.repositroy.ISessionRepository
import com.graphenelab.photosync.ui.startup.StartupViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.security.KeyStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Android preferences are real; only the device's encryption provider is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SecureStorageRecoveryTest {
    private lateinit var context: RecordingContext
    private val masterKey = mockk<MasterKey>()
    private val opens = mutableMapOf<String, Int>()
    private var openEncryptedPreferences: (String) -> SharedPreferences = { preferences(it) }

    @Before
    fun setUp() {
        context = RecordingContext(RuntimeEnvironment.getApplication())
        // Robolectric cannot reproduce a physical device's hardware-backed Keystore.
        // Inject its observed failure at the real AndroidX factory boundary instead.
        mockkConstructor(MasterKey.Builder::class)
        every { anyConstructed<MasterKey.Builder>().build() } returns masterKey
        mockkStatic(EncryptedSharedPreferences::class)
        every {
            EncryptedSharedPreferences.create(
                any<Context>(), any<String>(), masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } answers {
            val name = secondArg<String>()
            opens[name] = (opens[name] ?: 0) + 1
            openEncryptedPreferences(name)
        }
        // None of these recovery paths may delete or replace the shared wrapping key.
        mockkStatic(KeyStore::class)
        every { KeyStore.getInstance("AndroidKeyStore") } throws
            AssertionError("Recovery must preserve the shared Android Keystore key")
        preferences(CSE_PREFS).edit().putString("test_cloud_encryption_key", "preserve-me").commit()
        preferences(SESSION_PREFS).edit().putString("test_session", "preserve-me").commit()
    }

    @After
    fun tearDown() {
        unmockkStatic(KeyStore::class)
        unmockkStatic(EncryptedSharedPreferences::class)
        unmockkConstructor(MasterKey.Builder::class)
    }

    @Test
    fun unreadableTokenKeysetResetsOnlyTokenPreferencesAndAllowsSignInAgain() {
        preferences(TOKEN_PREFS).edit().putString("access_token", "stale-access-token")
            .putString("refresh_token", "stale-refresh-token").commit()
        failFirstTokenOpen(AEADBadTagException())
        val storage = TokenStorage(context)

        assertNull(storage.getAccessToken())
        assertNull(storage.getRefreshToken())
        assertEquals(listOf(TOKEN_PREFS), context.deletedPreferences)
        assertEquals(2, opens[TOKEN_PREFS])
        assertOtherStoresPreserved()

        storage.saveTokens("new-access-token", "new-refresh-token")
        assertEquals("new-access-token", storage.getAccessToken())
        assertEquals("new-refresh-token", storage.getRefreshToken())
        val reopenedStorage = TokenStorage(context)
        assertEquals("new-access-token", reopenedStorage.getAccessToken())
        assertEquals("new-refresh-token", reopenedStorage.getRefreshToken())
    }

    @Test
    fun corruptedTokenStorageDuringStartupResolvesToLoginInsteadOfCrashing() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            failFirstTokenOpen(AEADBadTagException())
            val sessions = mockk<ISessionRepository>()
            coEvery { sessions.hasSession() } returns false
            coEvery { sessions.hasCloudSpaceCredentials() } returns false
            val settings = mockk<IAppSettingsRepository>()
            every { settings.isEncryptionEnabled() } returns true
            val encryptionKeys = mockk<ICseMasterKeyRepository>()
            val viewModel = StartupViewModel(
                OauthTokenRepository(TokenStorage(context)), sessions, encryptionKeys, settings
            )
            store.put("startup", viewModel)

            assertEquals("login", viewModel.uiState.first { it.startDestination != null }.startDestination)
            assertOtherStoresPreserved()
            verify(exactly = 0) { encryptionKeys.hasKey() }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun sessionRecoveryNeverDeletesTheKeyUsedByOtherStores() = runTest {
        preferences(TOKEN_PREFS).edit().putString("access_token", "preserve-access-token").commit()
        openEncryptedPreferences = { name ->
            if (name == SESSION_PREFS && opens[name] == 1) throw AEADBadTagException()
            preferences(name)
        }

        assertNull(SessionRepository(context).loadSession())
        assertEquals(listOf(SESSION_PREFS), context.deletedPreferences)
        assertEquals(2, opens[SESSION_PREFS])
        assertEquals("preserve-me", preferences(CSE_PREFS).getString("test_cloud_encryption_key", null))
        assertEquals("preserve-access-token", preferences(TOKEN_PREFS).getString("access_token", null))
        verify(exactly = 0) { KeyStore.getInstance("AndroidKeyStore") }
    }

    @Test
    fun backupRulesExcludeDeviceBoundStoresFromCloudBackupAndDeviceTransfer() {
        val expectedFiles = setOf(
            "$TOKEN_PREFS.xml", "$SESSION_PREFS.xml", "$CSE_PREFS.xml",
            "${SESSION_PREFS}_fallback.xml"
        )
        assertEquals(expectedFiles, excludedPreferences(R.xml.backup_rules, "full-backup-content"))
        assertEquals(expectedFiles, excludedPreferences(R.xml.data_extraction_rules, "cloud-backup"))
        assertEquals(expectedFiles, excludedPreferences(R.xml.data_extraction_rules, "device-transfer"))
    }

    @Test
    fun healthyTokensCanBeSavedReadClearedAndSavedAgain() {
        val storage = TokenStorage(context)
        storage.saveTokens("test-access-token", "test-refresh-token")
        assertEquals("test-access-token", storage.getAccessToken())
        assertEquals("test-refresh-token", storage.getRefreshToken())
        assertTrue(context.deletedPreferences.isEmpty())

        storage.clearTokens()
        assertNull(storage.getAccessToken())
        assertNull(storage.getRefreshToken())
        storage.saveTokens("replacement-access-token", "replacement-refresh-token")
        assertEquals("replacement-access-token", TokenStorage(context).getAccessToken())
        assertOtherStoresPreserved()
    }

    @Test
    fun malformedTokenKeysetAlsoRecoversByDiscardingOnlyTokenPreferences() {
        failFirstTokenOpen(IOException("Malformed encrypted token keyset"))
        assertNull(TokenStorage(context).getAccessToken())
        assertEquals(2, opens[TOKEN_PREFS])
        assertEquals(listOf(TOKEN_PREFS), context.deletedPreferences)
        assertOtherStoresPreserved()
    }

    @Test
    fun unreadableAccessTokenValueDiscardsTheRefreshTokenAsWell() {
        val damagedPreferences = mockk<SharedPreferences>()
        every { damagedPreferences.getString("access_token", null) } throws
            SecurityException("Unable to decrypt value", AEADBadTagException())
        preferences(TOKEN_PREFS).edit().putString("refresh_token", "stale-refresh-token").commit()
        openEncryptedPreferences = { name ->
            if (name == TOKEN_PREFS && opens[name] == 1) damagedPreferences else preferences(name)
        }
        val storage = TokenStorage(context)

        assertNull(storage.getAccessToken())
        assertNull(storage.getRefreshToken())
        assertEquals(listOf(TOKEN_PREFS), context.deletedPreferences)
        assertOtherStoresPreserved()
    }

    @Test
    fun unreadableRefreshTokenValueAlsoDiscardsTheCachedAccessToken() {
        val damagedPreferences = mockk<SharedPreferences>()
        every { damagedPreferences.getString("access_token", null) } returns "old-access-token"
        every { damagedPreferences.getString("refresh_token", null) } throws
            SecurityException("Unable to decrypt value", AEADBadTagException())
        openEncryptedPreferences = { name ->
            if (name == TOKEN_PREFS && opens[name] == 1) damagedPreferences else preferences(name)
        }
        val storage = TokenStorage(context)

        assertEquals("old-access-token", storage.getAccessToken())
        assertNull(storage.getRefreshToken())
        assertNull(storage.getAccessToken())
        assertEquals(listOf(TOKEN_PREFS), context.deletedPreferences)
        assertOtherStoresPreserved()
    }

    @Test
    fun persistentEncryptionFailureIsBoundedAndNeverWritesPlaintextTokens() {
        openEncryptedPreferences = { throw GeneralSecurityException("Keystore unavailable") }
        val storage = TokenStorage(context)

        assertNull(storage.getAccessToken())
        assertEquals(2, opens[TOKEN_PREFS])
        assertFailsWith<GeneralSecurityException> {
            storage.saveTokens("must-not-be-persisted", "must-not-be-persisted")
        }
        assertEquals(4, opens[TOKEN_PREFS])
        assertTrue(preferences(TOKEN_PREFS).all.isEmpty())
        assertOtherStoresPreserved()
    }

    @Test
    fun clearingUnreadableTokensDoesNotNeedToOpenEncryptedStorage() {
        preferences(TOKEN_PREFS).edit().putString("access_token", "stale-access-token").commit()
        openEncryptedPreferences = { throw AEADBadTagException() }

        TokenStorage(context).clearTokens()

        assertTrue(opens.isEmpty())
        assertTrue(preferences(TOKEN_PREFS).all.isEmpty())
        assertEquals(listOf(TOKEN_PREFS), context.deletedPreferences)
        assertOtherStoresPreserved()
    }

    @Test
    fun unrelatedProgrammingErrorsAreNotSilentlyConvertedIntoSignedOutState() {
        openEncryptedPreferences = { throw IllegalStateException("Unexpected programming error") }

        assertFailsWith<IllegalStateException> { TokenStorage(context).getAccessToken() }
        assertTrue(context.deletedPreferences.isEmpty())
    }

    @Test
    fun concurrentReadersShareOneTokenRecovery() {
        failFirstTokenOpen(AEADBadTagException())
        val storage = TokenStorage(context)
        val readers = Executors.newFixedThreadPool(4)
        try {
            val results = readers.invokeAll(List(12) { Callable { storage.getAccessToken() } })
            results.forEach { assertNull(it.get(5, TimeUnit.SECONDS)) }
            assertEquals(2, opens[TOKEN_PREFS])
            assertEquals(listOf(TOKEN_PREFS), context.deletedPreferences)
            assertOtherStoresPreserved()
        } finally {
            readers.shutdownNow()
        }
    }

    @Test
    fun failedTokenDeletionCannotExposeOldTokensAndCanRecoverOnceDeletionWorks() {
        preferences(TOKEN_PREFS).edit().putString("access_token", "stale-access-token")
            .putString("refresh_token", "stale-refresh-token").commit()
        context.canDeletePreferences = false
        failFirstTokenOpen(AEADBadTagException())
        val storage = TokenStorage(context)

        assertNull(storage.getAccessToken())
        assertNull(storage.getRefreshToken())
        assertEquals(1, opens[TOKEN_PREFS])
        context.canDeletePreferences = true
        assertNull(storage.getAccessToken())
        assertNull(storage.getRefreshToken())
        storage.saveTokens("new-access-token", "new-refresh-token")
        assertEquals("new-access-token", storage.getAccessToken())
        assertOtherStoresPreserved()
    }

    @Test
    fun persistentSessionEncryptionFailureDoesNotFallBackToPlaintext() = runTest {
        openEncryptedPreferences = { throw GeneralSecurityException("Keystore unavailable") }
        val sessions = SessionRepository(context)

        assertNull(sessions.loadSession())
        assertEquals(2, opens[SESSION_PREFS])
        assertFailsWith<GeneralSecurityException> {
            sessions.saveCloudSpaceCredentials(
                com.graphenelab.photosync.domain.model.CloudSpaceCredentials("test-qr", 123456)
            )
        }
        assertEquals(4, opens[SESSION_PREFS])
        assertTrue("${SESSION_PREFS}_fallback" !in context.requestedPreferences)
        assertEquals("preserve-me", preferences(CSE_PREFS).getString("test_cloud_encryption_key", null))
        verify(exactly = 0) { KeyStore.getInstance("AndroidKeyStore") }
    }

    private fun failFirstTokenOpen(exception: Exception) {
        openEncryptedPreferences = { name ->
            if (name == TOKEN_PREFS && opens[name] == 1) throw exception
            preferences(name)
        }
    }

    private fun preferences(name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun assertOtherStoresPreserved() {
        assertEquals("preserve-me", preferences(CSE_PREFS).getString("test_cloud_encryption_key", null))
        assertEquals("preserve-me", preferences(SESSION_PREFS).getString("test_session", null))
        verify(exactly = 0) { KeyStore.getInstance("AndroidKeyStore") }
    }

    private fun excludedPreferences(resource: Int, section: String): Set<String> {
        val excluded = mutableSetOf<String>()
        context.resources.getXml(resource).use { xml ->
            var inSection = false
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG) {
                    if (xml.name == section) inSection = true
                    if (inSection && xml.name == "exclude" && xml.getAttributeValue(null, "domain") == "sharedpref") {
                        excluded += xml.getAttributeValue(null, "path")
                    }
                } else if (xml.eventType == XmlPullParser.END_TAG && xml.name == section) {
                    inSection = false
                }
                xml.next()
            }
        }
        return excluded
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val deletedPreferences = mutableListOf<String>()
        val requestedPreferences = mutableListOf<String>()
        var canDeletePreferences = true

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            requestedPreferences += name
            return super.getSharedPreferences(name, mode)
        }

        override fun deleteSharedPreferences(name: String): Boolean {
            deletedPreferences += name
            return canDeletePreferences && super.deleteSharedPreferences(name)
        }
    }

    private companion object {
        const val TOKEN_PREFS = "secret_shared_prefs"
        const val SESSION_PREFS = "encrypted_session_prefs"
        const val CSE_PREFS = "secure_app_settings"
    }
}
