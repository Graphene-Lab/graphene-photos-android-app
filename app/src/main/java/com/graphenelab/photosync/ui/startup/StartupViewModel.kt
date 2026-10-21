package com.graphenelab.photosync.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.graphenelab.photosync.common.AppStartupTrace
import com.graphenelab.photosync.domain.repositroy.IAppSettingsRepository
import com.graphenelab.photosync.domain.repositroy.ICseMasterKeyRepository
import com.graphenelab.photosync.domain.repositroy.IOauthTokenRepository
import com.graphenelab.photosync.domain.repositroy.ISessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class StartupViewModel @Inject constructor(
    private val oauthTokenRepository: IOauthTokenRepository,
    private val sessionRepository: ISessionRepository,
    private val cseMasterKeyRepository: ICseMasterKeyRepository,
    private val appSettingsRepository: IAppSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StartupUiState())
    val uiState: StateFlow<StartupUiState> = _uiState.asStateFlow()

    init {
        resolveStartDestination()
    }

    private fun resolveStartDestination() {
        viewModelScope.launch {
            AppStartupTrace.mark("StartupViewModel.resolve:start")
            val startDestination = resolveStartDestinationOnIo()

            _uiState.update { it.copy(startDestination = startDestination) }
            AppStartupTrace.mark("StartupViewModel.resolve:end -> $startDestination")
        }
    }

    fun onInitialDestinationDisplayed() {
        if (!_uiState.value.keepSplashVisible) return
        AppStartupTrace.mark("StartupViewModel.initialDestinationDisplayed")
        _uiState.update { it.copy(keepSplashVisible = false) }
    }

    private suspend fun resolveStartDestinationOnIo(): String = withContext(Dispatchers.IO) {
        coroutineScope {
            val hasSessionDeferred = async { sessionRepository.hasSession() }
            val hasQrCredentialsDeferred = async { sessionRepository.hasCloudSpaceCredentials() }
            val hasIamAccessTokenDeferred = async { !oauthTokenRepository.getAccessToken().isNullOrBlank() }
            val isEncryptionEnabledDeferred = async { appSettingsRepository.isEncryptionEnabled() }

            val hasSession = hasSessionDeferred.await()
            val hasQrCredentials = hasQrCredentialsDeferred.await()
            val hasIamAccessToken = hasIamAccessTokenDeferred.await()
            AppStartupTrace.mark("StartupViewModel.resolve:authStateLoaded")

            if (hasSession && (hasIamAccessToken || hasQrCredentials)) {
                val startDestination =
                    if (!isEncryptionEnabledDeferred.await() || cseMasterKeyRepository.hasKey()) {
                        "sync"
                    } else {
                        "mnemonic"
                    }
                AppStartupTrace.mark("StartupViewModel.resolve:encryptionStateLoaded")
                startDestination
            } else {
                "login"
            }
        }
    }
}
