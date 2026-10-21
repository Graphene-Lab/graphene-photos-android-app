package com.graphenelab.photosync.ui.login

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import com.graphenelab.photosync.R
import com.graphenelab.photosync.data.repository.CloudSpaceInitializationException
import com.graphenelab.photosync.data.repository.NetworkException
import com.graphenelab.photosync.data.repository.SessionExpiredException
import com.graphenelab.photosync.domain.model.CloudSpaceCredentials
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationState
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationStatus
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationStatus.*
import com.graphenelab.photosync.domain.repositroy.ICloudSpaceRepository
import com.graphenelab.photosync.domain.repositroy.IOauthTokenRepository
import com.graphenelab.photosync.manager.CloudSpaceInitializationController
import com.graphenelab.photosync.manager.interfaces.IOAuthManager
import com.graphenelab.photosync.ui.theme.AppTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Real route, screen, ViewModel and controller; only OAuth/backend/storage are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
@LooperMode(LooperMode.Mode.PAUSED)
class LoginScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = mockk<ICloudSpaceRepository>()
    private val tokens = mockk<IOauthTokenRepository>(relaxed = true)
    private val auth = mockk<IOAuthManager>()
    private val store = ViewModelStore()
    private val authResults = TestAuthResults()
    private lateinit var viewModel: LoginViewModel
    private var scanNavigations = 0
    private var screenDisplays = 0
    private var cancellationsConsumed = 0
    private var oauthZkCancelled by mutableStateOf(false)
    private var routeVisible by mutableStateOf(true)
    private val resolutions = mutableListOf<CloudSpaceCredentials>()
    private val credentials = CloudSpaceCredentials("local-test-qr", 123456)

    @Before
    fun setUp() {
        // Retrofit in this app uses OkHttp. Any accidental real HTTP request fails immediately.
        mockkConstructor(OkHttpClient::class)
        every { anyConstructed<OkHttpClient>().newCall(any()) } throws
            AssertionError("Live network access is forbidden in screen tests")
        every { auth.getAuthIntent() } returns Intent("test.IAM_SIGN_IN")
        coEvery { auth.exchangeCodeForTokens(any()) } returns Unit
        viewModel = LoginViewModel(auth, CloudSpaceInitializationController(repository), tokens)
        store.put("login", viewModel)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides authResults) {
                AppTheme(darkTheme = false, dynamicColor = false) {
                    if (routeVisible) {
                        LoginRoute(
                            onOAuthPairingCredentialsResolved = { qr, pin ->
                                resolutions += CloudSpaceCredentials(qr, pin)
                            },
                            oauthZkCancelled = oauthZkCancelled,
                            onOauthZkCancelConsumed = {
                                cancellationsConsumed++
                                oauthZkCancelled = false
                            },
                            onNavigateToScan = { scanNavigations++ },
                            onScreenDisplayed = { screenDisplays++ },
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        try {
            compose.runOnIdle { store.clear() }
        } finally {
            unmockkConstructor(OkHttpClient::class)
        }
    }

    @Test
    fun signInAndQrButtonsUseTheRealScreenHandlers() {
        compose.onNodeWithText(text(R.string.login_sign_in_email)).assertIsDisplayed().performClick()
        assertEquals(1, authResults.launches)
        assertEquals("test.IAM_SIGN_IN", authResults.intent?.action)
        compose.onNodeWithText(text(R.string.login_connect_qr)).performClick()
        assertEquals(1, scanNavigations)
        assertEquals(1, screenDisplays)
        coVerify(exactly = 0) { auth.exchangeCodeForTokens(any()) }
        coVerify(exactly = 0) { repository.getCloudSpaceInitializationState() }
    }

    @Test
    fun privacyDialogOpensAndDismissesFromTheExistingButtons() {
        click(R.string.login_learn_more)
        compose.onNodeWithText(text(R.string.login_privacy_title)).assertIsDisplayed()
        click(R.string.common_ok)
        compose.onNodeWithText(text(R.string.login_privacy_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.login_sign_in_email)).assertIsDisplayed()
        assertEquals(1, screenDisplays)
    }

    @Test
    fun canceledIamActivityKeepsSignInAvailableWithoutStartingBackendWork() {
        signIn(Activity.RESULT_CANCELED)
        compose.onNodeWithText(text(R.string.login_sign_in_email)).assertIsDisplayed()
        coVerify(exactly = 0) { auth.exchangeCodeForTokens(any()) }
        coVerify(exactly = 0) { repository.getCloudSpaceInitializationState() }
    }

    @Test
    fun tokenExchangeStatusCheckAndCredentialsEachShowTheirOwnLoadingMessage() {
        val exchange = CompletableDeferred<Unit>()
        val status = CompletableDeferred<CloudSpaceInitializationState>()
        val pairingCredentials = CompletableDeferred<CloudSpaceCredentials>()
        coEvery { auth.exchangeCodeForTokens(any()) } coAnswers { exchange.await() }
        coEvery { repository.getCloudSpaceInitializationState() } coAnswers {
            Result.success(status.await())
        }
        coEvery { repository.getCloudSpaceCredentials() } coAnswers {
            Result.success(pairingCredentials.await())
        }

        signIn()
        assertLoading(R.string.login_auth_in_progress)
        coVerify(exactly = 0) { repository.getCloudSpaceInitializationState() }
        compose.runOnIdle { exchange.complete(Unit) }
        assertLoading(R.string.login_cloud_space_checking)
        compose.runOnIdle { status.complete(setup(READY)) }
        assertLoading(R.string.login_cloud_space_fetching_credentials)
        compose.runOnIdle { pairingCredentials.complete(credentials) }
        compose.waitForIdle()
        assertEquals(listOf(credentials), resolutions)

        // A repeated platform callback cannot navigate or obtain credentials a second time.
        compose.runOnIdle { authResults.complete() }
        compose.waitForIdle()
        assertEquals(listOf(credentials), resolutions)
        coVerify(exactly = 1) { auth.exchangeCodeForTokens(any()) }
        coVerify(exactly = 1) { repository.getCloudSpaceCredentials() }
    }

    @Test
    fun iamFailureTryAgainReturnsToSignInWithoutCheckingCloudStorage() {
        coEvery { auth.exchangeCodeForTokens(any()) } throws NetworkException("IAM unavailable")
        signIn()
        compose.onNodeWithText("IAM unavailable").assertIsDisplayed()
        click(R.string.login_try_again)
        compose.onNodeWithText(text(R.string.login_sign_in_email)).assertIsDisplayed()
        coVerify(exactly = 1) { auth.exchangeCodeForTokens(any()) }
        coVerify(exactly = 0) { repository.getCloudSpaceInitializationState() }
    }

    @Test
    fun pendingAndInProgressPollUntilReadyWithoutRequestingInitialization() {
        coEvery { repository.getCloudSpaceInitializationState() } returnsMany listOf(
            Result.success(setup(PENDING)),
            Result.success(setup(IN_PROGRESS)),
            Result.success(setup(READY))
        )
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)
        signIn()
        assertLoading(R.string.login_cloud_space_preparing)
        advanceAndroidTime(5_000)
        assertLoading(R.string.login_cloud_space_preparing)
        coVerify(exactly = 2) { repository.getCloudSpaceInitializationState() }
        advanceAndroidTime(5_000)
        assertEquals(listOf(credentials), resolutions)
        coVerify(exactly = 3) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun scheduledRecoveryShowsWaitingWithoutOfferingOrSendingManualRetry() {
        val scheduled = setup(RETRY_SCHEDULED).copy(nextRetryAt = Instant.now().plusSeconds(60))
        coEvery { repository.getCloudSpaceInitializationState() } returnsMany listOf(
            Result.success(scheduled), Result.success(setup(READY))
        )
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)
        signIn()
        assertLoading(R.string.login_cloud_space_retry_scheduled)
        advanceAndroidTime(45_000)
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        assertEquals(emptyList(), resolutions)
        advanceAndroidTime(16_000)
        assertEquals(listOf(credentials), resolutions)
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun exhaustedRetryButtonRequestsOneNewAttemptAndThenChecksReadiness() {
        val initialization = CompletableDeferred<CloudSpaceInitializationState>()
        coEvery { repository.getCloudSpaceInitializationState() } returnsMany listOf(
            Result.success(exhausted()), Result.success(setup(READY))
        )
        coEvery { repository.initializeCloudSpace() } coAnswers {
            Result.success(initialization.await())
        }
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)
        signIn()
        compose.onNodeWithText(text(R.string.login_cloud_space_retry_limit_reached)).assertIsDisplayed()
        // Invoke the same real button callback twice before recomposition, as rapid taps can do.
        val retryClick = compose.onNodeWithText(text(R.string.login_cloud_space_retry_setup))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { retryClick(); retryClick() }
        assertLoading(R.string.login_cloud_space_initializing)
        coVerify(exactly = 1) { repository.initializeCloudSpace() }
        compose.runOnIdle { initialization.complete(setup(IN_PROGRESS)) }
        compose.waitForIdle()
        assertEquals(listOf(credentials), resolutions)
        coVerify(exactly = 2) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 1) { auth.exchangeCodeForTokens(any()) }
    }

    @Test
    fun supportRequiredShowsHelpMessageWithoutAnyRetryButton() {
        // retryable alone must never grant permission for a manual POST.
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(
            setup(FAILED).copy(errorCode = "CLOUD_SPACE_CONFIGURATION_INVALID", retryable = true)
        )
        signIn()
        compose.onNodeWithText(text(R.string.login_cloud_space_support_required)).assertIsDisplayed()
        assertNoRetryButtons()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
        coVerify(exactly = 0) { repository.getCloudSpaceCredentials() }
    }

    @Test
    fun statusFailureTryAgainChecksReadinessWithoutRepeatingIamOrPostingSetup() {
        coEvery { repository.getCloudSpaceInitializationState() } returnsMany listOf(
            Result.failure(NetworkException("Status unavailable")), Result.success(setup(READY))
        )
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)
        signIn()
        compose.onNodeWithText("Status unavailable").assertIsDisplayed()
        click(R.string.login_try_again)
        compose.waitForIdle()
        assertEquals(listOf(credentials), resolutions)
        coVerify(exactly = 2) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 1) { auth.exchangeCodeForTokens(any()) }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun credentialsFailureTryAgainRechecksReadinessBeforeFetchingCredentials() {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } returnsMany listOf(
            Result.failure(NetworkException("Credentials unavailable")), Result.success(credentials)
        )
        signIn()
        compose.onNodeWithText("Credentials unavailable").assertIsDisplayed()
        click(R.string.login_try_again)
        compose.waitForIdle()
        assertEquals(listOf(credentials), resolutions)
        coVerify(exactly = 2) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 2) { repository.getCloudSpaceCredentials() }
        coVerify(exactly = 1) { auth.exchangeCodeForTokens(any()) }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun ambiguousSetupPostFailureTryAgainChecksStatusInsteadOfPostingTwice() {
        coEvery { repository.getCloudSpaceInitializationState() } returnsMany listOf(
            Result.success(exhausted()), Result.success(setup(READY))
        )
        coEvery { repository.initializeCloudSpace() } returns
            Result.failure(NetworkException("Setup response lost"))
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)
        signIn()
        click(R.string.login_cloud_space_retry_setup)
        compose.onNodeWithText("Setup response lost").assertIsDisplayed()
        click(R.string.login_try_again)
        compose.waitForIdle()
        assertEquals(listOf(credentials), resolutions)
        coVerify(exactly = 1) { repository.initializeCloudSpace() }
        coVerify(exactly = 2) { repository.getCloudSpaceInitializationState() }
    }

    @Test
    fun setupPostTimeoutShowsErrorAndTryAgainOnlyChecksReadiness() {
        coEvery { repository.getCloudSpaceInitializationState() } returnsMany listOf(
            Result.success(exhausted()), Result.success(setup(READY))
        )
        coEvery { repository.initializeCloudSpace() } coAnswers { awaitCancellation() }
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)
        signIn()
        click(R.string.login_cloud_space_retry_setup)
        assertLoading(R.string.login_cloud_space_initializing)
        advanceAndroidTime(16_000)
        compose.onNodeWithText("Cloud storage request timed out").assertIsDisplayed()
        click(R.string.login_try_again)
        compose.waitForIdle()
        assertEquals(listOf(credentials), resolutions)
        coVerify(exactly = 1) { repository.initializeCloudSpace() }
    }

    @Test
    fun expiredSessionClearsTokensAndSignInAgainReturnsToTheSignInScreen() {
        coEvery { repository.getCloudSpaceInitializationState() } returns
            Result.failure(SessionExpiredException("Account unavailable"))
        signIn()
        compose.onNodeWithText(text(R.string.login_session_expired)).assertIsDisplayed()
        verify(exactly = 1) { tokens.clearTokens() }
        click(R.string.login_sign_in_again)
        compose.onNodeWithText(text(R.string.login_sign_in_email)).assertIsDisplayed()
        click(R.string.login_sign_in_email)
        assertEquals(2, authResults.launches)
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
    }

    @Test
    fun credentialsSetupRaceReturnsToWaitingAndEventuallyHandsOffOnce() {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } returnsMany listOf(
            Result.failure(CloudSpaceInitializationException(setup(RETRY_SCHEDULED), 503)),
            Result.success(credentials)
        )
        signIn()
        assertLoading(R.string.login_cloud_space_retry_scheduled)
        advanceAndroidTime(5_000)
        assertEquals(listOf(credentials), resolutions)
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun cancelingZeroKnowledgeSetupResetsLoginAndIgnoresLateCredentials() {
        val lateCredentials = CompletableDeferred<CloudSpaceCredentials>()
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } coAnswers {
            withContext(NonCancellable) { Result.success(lateCredentials.await()) }
        }
        signIn()
        assertLoading(R.string.login_cloud_space_fetching_credentials)
        compose.runOnIdle { oauthZkCancelled = true }
        compose.onNodeWithText(text(R.string.login_sign_in_email)).assertIsDisplayed()
        assertEquals(1, cancellationsConsumed)
        compose.runOnIdle { lateCredentials.complete(credentials) }
        compose.waitForIdle()
        assertEquals(emptyList(), resolutions)
        compose.onNodeWithText(text(R.string.login_sign_in_email)).assertIsDisplayed()
    }

    @Test
    fun leavingRouteAndClearingItsViewModelCancelsPollingAndPreventsHandoff() {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(PENDING))
        signIn()
        assertLoading(R.string.login_cloud_space_preparing)
        compose.runOnIdle { routeVisible = false; store.clear() }
        advanceAndroidTime(30_000)
        assertEquals(emptyList(), resolutions)
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 0) { repository.getCloudSpaceCredentials() }
    }

    @Test
    fun accidentalRealHttpClientRequestsAreBlocked() {
        val error = assertFailsWith<AssertionError> {
            OkHttpClient.Builder().build().newCall(
                Request.Builder().url("https://unexpected-network.invalid/").build()
            )
        }
        assertEquals("Live network access is forbidden in screen tests", error.message)
    }

    private fun signIn(resultCode: Int = Activity.RESULT_OK) {
        click(R.string.login_sign_in_email)
        compose.runOnIdle { authResults.complete(resultCode) }
        compose.waitForIdle()
    }

    private fun click(id: Int) {
        compose.onNodeWithText(text(id)).assertIsDisplayed().performClick()
    }

    private fun assertLoading(message: Int) {
        compose.onNodeWithText(text(message)).assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.login_sign_in_email)).assertDoesNotExist()
        assertNoRetryButtons()
    }

    private fun assertNoRetryButtons() {
        listOf(R.string.login_try_again, R.string.login_cloud_space_retry_setup, R.string.login_sign_in_again)
            .forEach { compose.onNodeWithText(text(it)).assertDoesNotExist() }
    }

    private fun advanceAndroidTime(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        compose.waitForIdle()
    }

    private fun setup(status: CloudSpaceInitializationStatus) = CloudSpaceInitializationState(
        status = status,
        attempts = 1,
        nextRetryAt = null,
        errorCode = null,
        retryable = status != FAILED
    )

    private fun exhausted() = setup(FAILED).copy(errorCode = "CLOUD_SPACE_RETRY_LIMIT_REACHED")

    private fun text(id: Int) = compose.activity.getString(id)

    /** Supplies the platform callback to rememberLauncherForActivityResult without opening IAM. */
    private class TestAuthResults : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var launches = 0
        var intent: Intent? = null
        private var requestCode = 0

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            launches++
            this.requestCode = requestCode
            intent = input as Intent
        }

        fun complete(resultCode: Int = Activity.RESULT_OK) {
            dispatchResult(requestCode, resultCode, Intent("test.IAM_RESULT"))
        }
    }
}
