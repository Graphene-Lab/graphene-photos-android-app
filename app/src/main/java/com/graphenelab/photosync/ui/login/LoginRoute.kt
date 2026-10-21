package com.graphenelab.photosync.ui.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun LoginRoute(
    onOAuthPairingCredentialsResolved: (qrEncrypted: String, pin: Int) -> Unit,
    oauthZkCancelled: Boolean,
    onOauthZkCancelConsumed: () -> Unit,
    onNavigateToScan: () -> Unit,
    onScreenDisplayed: (() -> Unit)? = null,
    viewModel: LoginViewModel = hiltViewModel()
) {
    LaunchedEffect(oauthZkCancelled) {
        if (!oauthZkCancelled) return@LaunchedEffect
        viewModel.resetLoginState()
        onOauthZkCancelConsumed()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LoginEvent.OAuthPairingCredentialsResolved -> {
                    onOAuthPairingCredentialsResolved(event.qrEncrypted, event.pin)
                }
            }
        }
    }

    LoginScreen(
        onNavigateToScan = onNavigateToScan,
        onScreenDisplayed = onScreenDisplayed,
        viewModel = viewModel
    )
}
