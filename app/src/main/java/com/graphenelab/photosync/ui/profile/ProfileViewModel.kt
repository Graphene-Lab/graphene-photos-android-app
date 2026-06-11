package com.graphenelab.photosync.ui.profile

import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.graphenelab.communication.crypto.SessionManager
import com.graphenelab.photosync.data.repository.SessionExpiredException
import com.graphenelab.photosync.data.repository.UnauthorizedException
import com.graphenelab.photosync.domain.repositroy.DeleteAccountResult
import com.graphenelab.photosync.domain.repositroy.IAppSettingsRepository
import com.graphenelab.photosync.domain.repositroy.ICloudSpaceRepository
import com.graphenelab.photosync.domain.repositroy.ICseMasterKeyRepository
import com.graphenelab.photosync.domain.repositroy.IOauthTokenRepository
import com.graphenelab.photosync.domain.repositroy.ISessionRepository
import com.graphenelab.photosync.domain.repositroy.ISyncRepository
import com.graphenelab.photosync.domain.usecase.GetSyncedPhotoUrisForDeletionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val oauthTokenRepository: IOauthTokenRepository,
    private val cloudSpaceRepository: ICloudSpaceRepository,
    private val cseMasterKeyRepository: ICseMasterKeyRepository,
    private val sessionRepository: ISessionRepository,
    private val appSettingsRepository: IAppSettingsRepository,
    private val syncRepository: ISyncRepository,
    private val getSyncedPhotoUrisForDeletion: GetSyncedPhotoUrisForDeletionUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<ProfileEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    companion object {
        private const val TAG = "ProfileViewModel"
    }

    init {
        loadUserProfile()
        loadCurrentSubscriptionPlan()
        loadCloudCredentials()
        checkLoginMode()
    }

    private fun loadUserProfile() {
        viewModelScope.launch {
            val userEmail = oauthTokenRepository.getEmail()
            _uiState.update { it.copy(email = userEmail) }
        }
    }

    private fun loadCurrentSubscriptionPlan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingPlan = true, planError = null) }
            cloudSpaceRepository.getCurrentSubscriptionPlan().onSuccess { currentPlan ->
                _uiState.update {
                    it.copy(
                        currentPlan = currentPlan,
                        isLoadingPlan = false
                    )
                }
            }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoadingPlan = false,
                            planError = e.message ?: "Failed to load subscription plan"
                        )
                    }
                }
        }
    }

    private fun loadCloudCredentials() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCredentials = true, credentialsError = null) }
            try {
                val credentials = sessionRepository.loadCloudSpaceCredentials()
                _uiState.update {
                    it.copy(
                        cloudCredentials = credentials,
                        isLoadingCredentials = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingCredentials = false,
                        credentialsError = e.message ?: "Failed to load credentials"
                    )
                }
            }
        }
    }

    private fun checkLoginMode() {
        viewModelScope.launch {
            // If user has no email (didn't login via OAuth), they're in QR mode
            val userEmail = oauthTokenRepository.getEmail()
            _uiState.update { it.copy(isQrLoginMode = userEmail.isNullOrBlank()) }
        }
    }

    fun refreshSubscriptionPlan() {
        loadCurrentSubscriptionPlan()
    }

    fun deleteSyncedPhotos() {
        viewModelScope.launch {
            Log.d(TAG, "deleteSyncedPhotos: Starting deletion process")
            _uiState.update { it.copy(isDeletingSyncedPhotos = true, deleteError = null, deletedPhotosCount = null, photoUrisToDelete = null) }
            try {
                // Only support Android 11+ (API 30+) for deletion
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    Log.w(TAG, "deleteSyncedPhotos: Deletion not supported on Android 10 and below")
                    _uiState.update {
                        it.copy(
                            isDeletingSyncedPhotos = false,
                            deleteError = "This feature requires Android 11 or higher"
                        )
                    }
                    return@launch
                }

                val allPhotoUris = getSyncedPhotoUrisForDeletion()

                if (allPhotoUris.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isDeletingSyncedPhotos = false,
                            deletedPhotosCount = 0,
                            deleteError = null
                        )
                    }
                    return@launch
                }

                // For Android 11+ (API 30+), return URIs for UI to create delete request
                Log.d(TAG, "deleteSyncedPhotos: Returning ${allPhotoUris.size} photo URIs for deletion (Android 11+)")
                _uiState.update {
                    it.copy(
                        isDeletingSyncedPhotos = false,
                        photoUrisToDelete = allPhotoUris
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteSyncedPhotos: Error during deletion", e)
                _uiState.update {
                    it.copy(
                        isDeletingSyncedPhotos = false,
                        deleteError = e.message ?: "Failed to delete synced photos"
                    )
                }
            }
        }
    }

    fun onDeletePermissionResult(granted: Boolean, photosCount: Int) {
        Log.d(TAG, "onDeletePermissionResult: granted=$granted, photosCount=$photosCount")
        _uiState.update {
            it.copy(
                photoUrisToDelete = null,
                deletedPhotosCount = if (granted) photosCount else null,
                deleteError = if (!granted) "Permission denied to delete photos" else null
            )
        }
    }

    fun clearPhotoUrisToDelete() {
        Log.d(TAG, "clearPhotoUrisToDelete: Clearing photo URIs")
        _uiState.update { it.copy(photoUrisToDelete = null) }
    }

    fun clearDeleteStatus() {
        Log.d(TAG, "clearDeleteStatus: Clearing delete status")
        _uiState.update { it.copy(deletedPhotosCount = null, deleteError = null, photoUrisToDelete = null) }
    }

    fun deleteAccount() {
        if (_uiState.value.isDeletingAccount) return

        viewModelScope.launch {
            _uiState.update { it.copy(isDeletingAccount = true, deleteAccountError = null) }
            try {
                cloudSpaceRepository.deleteCurrentUserAccount()
                    .onSuccess { result ->
                        when (result) {
                            DeleteAccountResult.Deleted,
                            DeleteAccountResult.UserNotFound -> {
                                clearLocalStateAfterAccountDeletion()
                                _events.emit(ProfileEvent.NavigateToLogin())
                            }
                        }
                    }
                    .onFailure { error ->
                        when (error) {
                            is SessionExpiredException,
                            is UnauthorizedException -> {
                                clearLocalStateAfterAccountDeletion()
                                _events.emit(
                                    ProfileEvent.NavigateToLogin(
                                        toastMessage = "Session expired. Please sign in again."
                                    )
                                )
                            }

                            else -> {
                                _uiState.update {
                                    it.copy(
                                        deleteAccountError = error.message
                                            ?: "Unable to delete account. Please try again."
                                    )
                                }
                            }
                        }
                    }
            } finally {
                _uiState.update { it.copy(isDeletingAccount = false) }
            }
        }
    }

    private suspend fun clearLocalStateAfterAccountDeletion() = withContext(Dispatchers.IO) {
        oauthTokenRepository.clearTokens()
        cseMasterKeyRepository.clearKey()
        sessionRepository.clearAuthState()
        syncRepository.clearAllData()
        SessionManager.clearSession()
        appSettingsRepository.setEncryptionEnabled(true)
    }

    fun logout() {
        oauthTokenRepository.clearTokens()
        cseMasterKeyRepository.clearKey()
        sessionRepository.clearAuthState()
        SessionManager.clearSession()
        appSettingsRepository.setEncryptionEnabled(true)
    }
}
