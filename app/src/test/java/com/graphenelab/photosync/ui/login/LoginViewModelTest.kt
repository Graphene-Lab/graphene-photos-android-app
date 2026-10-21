package com.graphenelab.photosync.ui.login

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModelStore
import com.graphenelab.photosync.data.repository.CloudSpaceInitializationException
import com.graphenelab.photosync.data.repository.NetworkException
import com.graphenelab.photosync.data.repository.SessionExpiredException
import com.graphenelab.photosync.data.repository.UnauthorizedException
import com.graphenelab.photosync.domain.model.CloudSpaceCredentials
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationState
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationStatus
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationStatus.*
import com.graphenelab.photosync.domain.repositroy.ICloudSpaceRepository
import com.graphenelab.photosync.domain.repositroy.IOauthTokenRepository
import com.graphenelab.photosync.manager.CloudSpaceInitializationController
import com.graphenelab.photosync.manager.interfaces.IOAuthManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authManager = mockk<IOAuthManager>()
    private val repository = mockk<ICloudSpaceRepository>()
    private val tokens = mockk<IOauthTokenRepository>(relaxed = true)
    private val intent = mockk<Intent>()
    private val credentials = CloudSpaceCredentials("local-test-qr", 123456)
    private val exhausted = setup(FAILED, "CLOUD_SPACE_RETRY_LIMIT_REACHED")
    private val store = ViewModelStore()
    private lateinit var viewModel: LoginViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any<Throwable>()) } returns 0
        coEvery { authManager.exchangeCodeForTokens(any()) } just Runs
        viewModel = LoginViewModel(authManager, CloudSpaceInitializationController(repository), tokens)
        store.put("login", viewModel)
    }

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test
    fun iamTokensStatusCheckAndPairingCredentialsHaveDistinctUiStates() = runTest(dispatcher) {
        val tokenExchange = CompletableDeferred<Unit>()
        val statusResponse = CompletableDeferred<CloudSpaceInitializationState>()
        val credentialsResponse = CompletableDeferred<CloudSpaceCredentials>()
        coEvery { authManager.exchangeCodeForTokens(intent) } coAnswers { tokenExchange.await() }
        coEvery { repository.getCloudSpaceInitializationState() } coAnswers {
            Result.success(statusResponse.await())
        }
        coEvery { repository.getCloudSpaceCredentials() } coAnswers {
            Result.success(credentialsResponse.await())
        }
        val events = collectEvents()
        assertEquals(LoginUiState.AwaitingIamSignIn, viewModel.uiState.value)

        viewModel.handleAuthResult(intent)
        runCurrent()
        assertEquals(LoginUiState.ObtainingIamTokens, viewModel.uiState.value)
        coVerify(exactly = 0) { repository.getCloudSpaceInitializationState() }

        tokenExchange.complete(Unit)
        runCurrent()
        assertEquals(LoginUiState.CheckingCloudSpaceInitializationState, viewModel.uiState.value)
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 0) { repository.getCloudSpaceCredentials() }

        statusResponse.complete(setup(READY))
        runCurrent()
        assertEquals(LoginUiState.FetchingCloudPairingCredentials, viewModel.uiState.value)
        assertTrue(events.isEmpty())

        credentialsResponse.complete(credentials)
        runCurrent()
        assertEquals(listOf(pairingEvent()), events)
        coVerify(exactly = 1) { authManager.exchangeCodeForTokens(intent) }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun oauthSuccessWaitsThroughPreparationAndRecoveryBeforePairingOnce() = runTest(dispatcher) {
        val tokenExchange = CompletableDeferred<Unit>()
        val credentialsRequest = CompletableDeferred<Unit>()
        coEvery { authManager.exchangeCodeForTokens(intent) } coAnswers { tokenExchange.await() }
        val pending = setup(PENDING)
        val scheduled = setup(RETRY_SCHEDULED)
        coEvery { repository.getCloudSpaceInitializationState() } returnsMany listOf(
            Result.success(pending), Result.success(scheduled), Result.success(setup(READY))
        )
        coEvery { repository.getCloudSpaceCredentials() } coAnswers {
            credentialsRequest.await()
            Result.success(credentials)
        }
        val events = collectEvents()

        viewModel.handleAuthResult(intent)
        runCurrent()
        assertEquals(LoginUiState.ObtainingIamTokens, viewModel.uiState.value)
        coVerify(exactly = 0) { repository.getCloudSpaceInitializationState() }

        tokenExchange.complete(Unit)
        runCurrent()
        assertEquals(LoginUiState.WaitingForCloudSpaceInitialization(pending), viewModel.uiState.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(LoginUiState.RetryScheduled(scheduled), viewModel.uiState.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(LoginUiState.FetchingCloudPairingCredentials, viewModel.uiState.value)
        assertTrue(events.isEmpty())

        credentialsRequest.complete(Unit)
        runCurrent()
        viewModel.handleAuthResult(intent)
        viewModel.retry()
        runCurrent()

        assertEquals(listOf(pairingEvent()), events)
        coVerify(exactly = 1) { authManager.exchangeCodeForTokens(intent) }
        coVerify(exactly = 1) { repository.getCloudSpaceCredentials() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun requestRetryChecksReadinessWithoutRepeatingOauthOrInitialization() = runTest(dispatcher) {
        val retryStatusResponse = CompletableDeferred<Unit>()
        var statusRequests = 0
        coEvery { repository.getCloudSpaceInitializationState() } coAnswers {
            if (statusRequests++ == 0) {
                Result.failure(NetworkException("Offline"))
            } else {
                retryStatusResponse.await()
                Result.success(setup(READY))
            }
        }
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)
        val events = collectEvents()
        viewModel.handleAuthResult(intent)
        runCurrent()
        assertEquals(LoginUiState.Error("Offline", LoginRecoveryAction.CHECK_CLOUD_SPACE), viewModel.uiState.value)

        viewModel.retry()
        viewModel.retry()
        runCurrent()

        assertEquals(LoginUiState.CheckingCloudSpaceInitializationState, viewModel.uiState.value)
        coVerify(exactly = 1) { authManager.exchangeCodeForTokens(intent) }
        retryStatusResponse.complete(Unit)
        runCurrent()

        assertEquals(listOf(pairingEvent()), events)
        coVerify(exactly = 1) { authManager.exchangeCodeForTokens(intent) }
        coVerify(exactly = 2) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun exhaustedSetupAllowsOnePostThenResumesWaiting() = runTest(dispatcher) {
        val pending = setup(PENDING)
        coEvery { repository.getCloudSpaceInitializationState() } returnsMany listOf(
            Result.success(exhausted), Result.success(pending), Result.success(setup(READY))
        )
        coEvery { repository.initializeCloudSpace() } returns Result.success(setup(PENDING))
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)
        val events = collectEvents()
        viewModel.handleAuthResult(intent)
        runCurrent()
        assertEquals(LoginUiState.RetryLimitReached(exhausted), viewModel.uiState.value)
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
        val retryStates = mutableListOf<LoginUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.toList(retryStates)
        }
        retryStates.clear()

        viewModel.retry()
        assertEquals(LoginUiState.RequestingCloudSpaceInitialization, viewModel.uiState.value)
        viewModel.retry()
        runCurrent()
        assertIs<LoginUiState.WaitingForCloudSpaceInitialization>(viewModel.uiState.value)
        assertEquals(
            listOf<LoginUiState>(
                LoginUiState.RequestingCloudSpaceInitialization,
                LoginUiState.CheckingCloudSpaceInitializationState,
                LoginUiState.WaitingForCloudSpaceInitialization(pending)
            ),
            retryStates
        )
        viewModel.retry()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf(pairingEvent()), events)
        coVerify(exactly = 1) { repository.initializeCloudSpace() }
        coVerify(exactly = 1) { authManager.exchangeCodeForTokens(intent) }
        coVerify(exactly = 3) { repository.getCloudSpaceInitializationState() }
    }

    @ParameterizedTest
    @EnumSource(value = CloudSpaceInitializationStatus::class, names = ["PENDING", "IN_PROGRESS", "RETRY_SCHEDULED"])
    fun waitingStatesDoNotAllowManualInitialization(status: CloudSpaceInitializationStatus) = runTest(dispatcher) {
        val waiting = setup(status).copy(nextRetryAt = Instant.now().plusSeconds(600))
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(waiting)
        viewModel.handleAuthResult(intent)
        runCurrent()
        val previousState = viewModel.uiState.value

        viewModel.retry()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(previousState, viewModel.uiState.value)
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
        viewModel.resetLoginState()
    }

    @Test
    fun permanentFailureRequiresSupportEvenWhenBackendRetryableIsTrue() = runTest(dispatcher) {
        val failed = setup(FAILED, "CLOUD_SPACE_CREDENTIALS_MISSING").copy(retryable = true)
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(failed)
        viewModel.handleAuthResult(intent)
        runCurrent()

        viewModel.retry()
        runCurrent()

        assertEquals(LoginUiState.SupportRequired(failed), viewModel.uiState.value)
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
    }

    @Test
    fun initializationTimeoutOffersStatusCheckInsteadOfRepeatingPost() = runTest(dispatcher) {
        coEvery { repository.getCloudSpaceInitializationState() } returnsMany listOf(
            Result.success(exhausted), Result.success(setup(READY))
        )
        coEvery { repository.initializeCloudSpace() } coAnswers { awaitCancellation() }
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)
        val events = collectEvents()
        viewModel.handleAuthResult(intent)
        runCurrent()
        viewModel.retry()
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(LoginRecoveryAction.CHECK_CLOUD_SPACE, assertIs<LoginUiState.Error>(viewModel.uiState.value).recoveryAction)

        viewModel.retry()
        runCurrent()

        assertEquals(listOf(pairingEvent()), events)
        coVerify(exactly = 1) { repository.initializeCloudSpace() }
        coVerify(exactly = 1) { authManager.exchangeCodeForTokens(intent) }
    }

    @Test
    fun initializationRefusalUsesItsTerminalState() = runTest(dispatcher) {
        val failed = setup(FAILED, "CLOUD_SPACE_CREDENTIALS_MISSING")
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(exhausted)
        coEvery { repository.initializeCloudSpace() } returns Result.failure(CloudSpaceInitializationException(failed, 409))
        viewModel.handleAuthResult(intent)
        runCurrent()

        viewModel.retry()
        runCurrent()
        viewModel.retry()
        runCurrent()

        assertEquals(LoginUiState.SupportRequired(failed), viewModel.uiState.value)
        coVerify(exactly = 1) { repository.initializeCloudSpace() }
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun expiredStatusOrCredentialsClearsTokensAndRequiresSignIn(duringCredentials: Boolean) = runTest(dispatcher) {
        if (duringCredentials) {
            coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
            coEvery { repository.getCloudSpaceCredentials() } returns Result.failure(SessionExpiredException("Missing account"))
        } else {
            coEvery { repository.getCloudSpaceInitializationState() } returns Result.failure(UnauthorizedException("Expired"))
        }
        val events = collectEvents()
        viewModel.handleAuthResult(intent)
        runCurrent()

        assertEquals(LoginUiState.SessionExpired, viewModel.uiState.value)
        verify(exactly = 1) { tokens.clearTokens() }
        assertTrue(events.isEmpty())
        viewModel.retry()
        assertEquals(LoginUiState.AwaitingIamSignIn, viewModel.uiState.value)
        coVerify(exactly = 1) { authManager.exchangeCodeForTokens(intent) }
        coVerify(exactly = 0) { repository.initializeCloudSpace() }
    }

    @Test
    fun sessionCanExpireDuringExplicitInitialization() = runTest(dispatcher) {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(exhausted)
        coEvery { repository.initializeCloudSpace() } returns Result.failure(SessionExpiredException("Missing account"))
        viewModel.handleAuthResult(intent)
        runCurrent()

        viewModel.retry()
        runCurrent()

        assertEquals(LoginUiState.SessionExpired, viewModel.uiState.value)
        verify(exactly = 1) { tokens.clearTokens() }
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
    }

    @Test
    fun oauthFailureReturnsToSignInOnRetry() = runTest(dispatcher) {
        coEvery { authManager.exchangeCodeForTokens(intent) } throws IllegalStateException("Token exchange failed")
        viewModel.handleAuthResult(intent)
        runCurrent()
        assertEquals(LoginUiState.Error("Token exchange failed", LoginRecoveryAction.RETURN_TO_SIGN_IN), viewModel.uiState.value)

        viewModel.retry()
        runCurrent()

        assertEquals(LoginUiState.AwaitingIamSignIn, viewModel.uiState.value)
        coVerify(exactly = 0) { repository.getCloudSpaceInitializationState() }
    }

    @Test
    fun resetCancelsPollingWithoutShowingAnError() = runTest(dispatcher) {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(PENDING))
        val events = collectEvents()
        viewModel.handleAuthResult(intent)
        runCurrent()

        viewModel.resetLoginState()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(LoginUiState.AwaitingIamSignIn, viewModel.uiState.value)
        assertTrue(events.isEmpty())
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        verify(exactly = 0) { tokens.clearTokens() }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun lateOauthSuccessOrFailureCannotChangeANewerAttempt(lateFailure: Boolean) = runTest(dispatcher) {
        val oldResult = CompletableDeferred<Unit>()
        val newIntent = mockk<Intent>()
        coEvery { authManager.exchangeCodeForTokens(intent) } coAnswers {
            withContext(NonCancellable) { oldResult.await() }
            if (lateFailure) throw IllegalStateException("Old attempt failed")
        }
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)
        val events = collectEvents()
        viewModel.handleAuthResult(intent)
        runCurrent()

        viewModel.handleAuthResult(newIntent)
        runCurrent()
        oldResult.complete(Unit)
        runCurrent()

        assertEquals(LoginUiState.FetchingCloudPairingCredentials, viewModel.uiState.value)
        assertEquals(listOf(pairingEvent()), events)
        coVerify(exactly = 1) { repository.getCloudSpaceInitializationState() }
        verify(exactly = 0) { tokens.clearTokens() }
    }

    @Test
    fun lateCredentialsAfterResetCannotPairOrShowAnError() = runTest(dispatcher) {
        val oldResult = CompletableDeferred<Unit>()
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } coAnswers {
            withContext(NonCancellable) { oldResult.await() }
            Result.success(credentials)
        }
        val events = collectEvents()
        viewModel.handleAuthResult(intent)
        runCurrent()

        viewModel.resetLoginState()
        oldResult.complete(Unit)
        runCurrent()

        assertEquals(LoginUiState.AwaitingIamSignIn, viewModel.uiState.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun cancelledOauthWaitDoesNotBecomeAnError() = runTest(dispatcher) {
        coEvery { authManager.exchangeCodeForTokens(intent) } coAnswers { awaitCancellation() }
        viewModel.handleAuthResult(intent)
        runCurrent()

        viewModel.resetLoginState()
        runCurrent()

        assertEquals(LoginUiState.AwaitingIamSignIn, viewModel.uiState.value)
        coVerify(exactly = 0) { repository.getCloudSpaceInitializationState() }
    }

    @Test
    fun zeroKnowledgeCancellationAllowsAnotherPairingAttempt() = runTest(dispatcher) {
        coEvery { repository.getCloudSpaceInitializationState() } returns Result.success(setup(READY))
        coEvery { repository.getCloudSpaceCredentials() } returns Result.success(credentials)
        val events = collectEvents()
        viewModel.handleAuthResult(intent)
        runCurrent()

        viewModel.resetLoginState()
        assertEquals(LoginUiState.AwaitingIamSignIn, viewModel.uiState.value)
        viewModel.handleAuthResult(intent)
        runCurrent()

        assertEquals(listOf(pairingEvent(), pairingEvent()), events)
        coVerify(exactly = 2) { authManager.exchangeCodeForTokens(intent) }
        verify(exactly = 0) { tokens.clearTokens() }
    }

    private fun TestScope.collectEvents(): MutableList<LoginEvent> = mutableListOf<LoginEvent>().also { events ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
    }

    private fun pairingEvent(): LoginEvent =
        LoginEvent.OAuthPairingCredentialsResolved(credentials.qrEncrypted, credentials.pin)

    private fun setup(status: CloudSpaceInitializationStatus, errorCode: String? = null) =
        CloudSpaceInitializationState(
            status = status,
            attempts = 5,
            nextRetryAt = null,
            errorCode = errorCode,
            retryable = status != FAILED && status != READY
        )
}
