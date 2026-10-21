package com.graphenelab.photosync.ui.login

import com.graphenelab.photosync.domain.model.CloudSpaceInitializationState

sealed interface LoginUiState {
    data object AwaitingIamSignIn : LoginUiState
    data object ObtainingIamTokens : LoginUiState
    data object CheckingCloudSpaceInitializationState : LoginUiState
    data class WaitingForCloudSpaceInitialization(val state: CloudSpaceInitializationState) : LoginUiState
    data class RetryScheduled(val state: CloudSpaceInitializationState) : LoginUiState
    data object FetchingCloudPairingCredentials : LoginUiState
    data object RequestingCloudSpaceInitialization : LoginUiState
    data class RetryLimitReached(val state: CloudSpaceInitializationState) : LoginUiState
    data class SupportRequired(val state: CloudSpaceInitializationState) : LoginUiState
    data object SessionExpired : LoginUiState
    data class Error(
        val message: String,
        val recoveryAction: LoginRecoveryAction = LoginRecoveryAction.RETURN_TO_SIGN_IN
    ) : LoginUiState
}

enum class LoginRecoveryAction {
    RETURN_TO_SIGN_IN,
    CHECK_CLOUD_SPACE
}
