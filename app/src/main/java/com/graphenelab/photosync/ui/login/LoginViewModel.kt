package com.graphenelab.photosync.ui.login

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.graphenelab.photosync.data.repository.CloudSpaceInitializationException
import com.graphenelab.photosync.data.repository.SessionExpiredException
import com.graphenelab.photosync.data.repository.UnauthorizedException
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationStatus
import com.graphenelab.photosync.domain.repositroy.IOauthTokenRepository
import com.graphenelab.photosync.manager.CloudSpaceInitializationController
import com.graphenelab.photosync.manager.CloudSpaceInitializationProgress
import com.graphenelab.photosync.manager.interfaces.IOAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val oAuthManager: IOAuthManager,
    private val initializationController: CloudSpaceInitializationController,
    private val oauthTokenRepository: IOauthTokenRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.AwaitingIamSignIn)
    val uiState = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<LoginEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()
    private var pairingProcessJob: Job? = null
    private var pairingCredentialsResolved = false

    fun getAuthIntent(): Intent {
        return oAuthManager.getAuthIntent()
    }

    fun handleAuthResult(intent: Intent) {
        if (pairingCredentialsResolved) return
        startPairingProcess(
            loadingState = LoginUiState.ObtainingIamTokens,
            recoveryAction = LoginRecoveryAction.RETURN_TO_SIGN_IN
        ) {
            oAuthManager.exchangeCodeForTokens(intent)
            currentCoroutineContext().ensureActive()
            _uiState.value = LoginUiState.CheckingCloudSpaceInitializationState
            initializationController
                .waitForCloudSpaceCredentials()
                .collectCloudSpaceProgress()
        }
    }

    // Request failures resume checking; only exhausted initialization allows a new POST.
    fun retry() {
        when (val state = _uiState.value) {
            is LoginUiState.Error -> when (state.recoveryAction) {
                LoginRecoveryAction.RETURN_TO_SIGN_IN -> resetLoginState()
                LoginRecoveryAction.CHECK_CLOUD_SPACE -> startPairingProcess {
                    initializationController
                        .waitForCloudSpaceCredentials()
                        .collectCloudSpaceProgress()
                }
            }

            is LoginUiState.RetryLimitReached -> {
                if (!state.state.manualRetryAllowed) return
                startPairingProcess(LoginUiState.RequestingCloudSpaceInitialization) {
                    initializationController.initializeCloudSpace()
                    currentCoroutineContext().ensureActive()
                    _uiState.value = LoginUiState.CheckingCloudSpaceInitializationState
                    initializationController
                        .waitForCloudSpaceCredentials()
                        .collectCloudSpaceProgress()
                }
            }

            LoginUiState.SessionExpired -> resetLoginState()
            else -> Unit
        }
    }

    // Also called when the user cancels the zero-knowledge setup screen.
    fun resetLoginState() {
        pairingProcessJob?.cancel()
        pairingProcessJob = null
        pairingCredentialsResolved = false
        _uiState.value = LoginUiState.AwaitingIamSignIn
    }

    private fun startPairingProcess(
        loadingState: LoginUiState = LoginUiState.CheckingCloudSpaceInitializationState,
        recoveryAction: LoginRecoveryAction = LoginRecoveryAction.CHECK_CLOUD_SPACE,
        operation: suspend () -> Unit
    ) {
        pairingProcessJob?.cancel()
        // Update before launching so repeated retry taps cannot start duplicate work.
        _uiState.value = loadingState
        pairingProcessJob = viewModelScope.launch {
            try {
                operation()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                currentCoroutineContext().ensureActive()
                when {
                    exception is UnauthorizedException || exception is SessionExpiredException ->
                        sessionExpired()

                    exception is CloudSpaceInitializationException &&
                        exception.state.status == CloudSpaceInitializationStatus.FAILED -> {
                        val state = exception.state
                        _uiState.value = if (state.manualRetryAllowed) {
                            LoginUiState.RetryLimitReached(state)
                        } else {
                            LoginUiState.SupportRequired(state)
                        }
                    }

                    else -> {
                        Log.w(TAG, "Login operation failed", exception)
                        _uiState.value = LoginUiState.Error(
                            exception.message ?: "Unable to complete sign-in",
                            recoveryAction
                        )
                    }
                }
            }
        }
    }

    private suspend fun Flow<CloudSpaceInitializationProgress>
            .collectCloudSpaceProgress() {
        collect { progress ->
            currentCoroutineContext().ensureActive()
            when (progress) {
                CloudSpaceInitializationProgress.FetchingCredentials ->
                    _uiState.value = LoginUiState.FetchingCloudPairingCredentials

                is CloudSpaceInitializationProgress.Preparing ->
                    _uiState.value = LoginUiState.WaitingForCloudSpaceInitialization(progress.state)

                is CloudSpaceInitializationProgress.RetryScheduled ->
                    _uiState.value = LoginUiState.RetryScheduled(progress.state)

                is CloudSpaceInitializationProgress.RetryLimitReached ->
                    _uiState.value = LoginUiState.RetryLimitReached(progress.state)

                is CloudSpaceInitializationProgress.SupportRequired ->
                    _uiState.value = LoginUiState.SupportRequired(progress.state)

                is CloudSpaceInitializationProgress.Ready -> {
                    if (!pairingCredentialsResolved) {
                        pairingCredentialsResolved = true
                        _events.emit(
                            LoginEvent.OAuthPairingCredentialsResolved(
                                qrEncrypted = progress.credentials.qrEncrypted,
                                pin = progress.credentials.pin
                            )
                        )
                    }
                }

                CloudSpaceInitializationProgress.SessionExpired -> sessionExpired()
                is CloudSpaceInitializationProgress.RequestFailed ->
                    _uiState.value = LoginUiState.Error(
                        progress.exception.message ?: "Unable to check cloud storage readiness",
                        LoginRecoveryAction.CHECK_CLOUD_SPACE
                    )
            }
        }
    }

    private fun sessionExpired() {
        oauthTokenRepository.clearTokens()
        _uiState.value = LoginUiState.SessionExpired
    }

    private companion object {
        const val TAG = "LoginViewModel"
    }
}
