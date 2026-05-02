package com.graphenelab.photosync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.graphenelab.photosync.ui.auth.AuthScreen
import com.graphenelab.photosync.ui.auth.AuthZeroKnowledgeScreen
import com.graphenelab.photosync.ui.login.LoginRoute
import com.graphenelab.photosync.ui.oauth.OAuthZeroKnowledgeSetupScreen
import com.graphenelab.photosync.ui.profile.ProfileScreen
import com.graphenelab.photosync.ui.mnemonic.MnemonicScreen
//import com.graphenelab.photosync.ui.subscription.SubscriptionScreen
import com.graphenelab.photosync.ui.sync.SyncScreen
import com.graphenelab.photosync.ui.scan.ScanScreen
import com.graphenelab.photosync.ui.folders.FoldersScreen


@Composable
fun AppNavigation(
    startDestination: String,
    onInitialDestinationDisplayed: () -> Unit
) {
    val navController = rememberNavController()


    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {

        composable("auth") {
            val qrEncrypted = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<String>("qr_encrypted")
            AuthScreen(
                qrEncrypted = qrEncrypted,
                onContinue = { pin ->
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("qr_encrypted", qrEncrypted)
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("auth_pin", pin)
                    navController.navigate("auth_zk")
                }
            )
        }
        composable("auth_zk") {
            val qrEncrypted = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<String>("qr_encrypted")
            val pin = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<String>("auth_pin")
                .orEmpty()

            AuthZeroKnowledgeScreen(
                qrEncrypted = qrEncrypted,
                pin = pin,
                onAuthenticationSuccess = {
                    navController.navigate("sync") {
                        popUpTo("auth") {
                            inclusive = true
                        }
                    }
                },
                onAuthenticationSuccessWithoutCse = {
                    navController.navigate("sync") {
                        popUpTo("auth") {
                            inclusive = true
                        }
                    }
                }
            )
        }
        composable("scan") {
            ScanScreen(
                onNavigateToResult = { qrCode ->
                    navController.currentBackStackEntry?.savedStateHandle?.set("qr_encrypted", qrCode)
                    navController.navigate("auth")
                }
            )
        }
        composable(
            route = "sync",
//            deepLinks = listOf(navDeepLink { uriPattern = "test://debug/sync-screen/{content}" }),
        ) { backStackEntry ->
            SyncScreen(
                onScreenDisplayed = if (startDestination == "sync") {
                    onInitialDestinationDisplayed
                } else {
                    null
                },
                onNavigateToProfile = {
                    navController.navigate("profile")
                },
                onNavigateToScanSetup = {
                    navController.navigate("folders?isFromScanSetup=true")
                }
            )
        }

        composable("login") { backStackEntry ->
            val oauthZkCancelled by backStackEntry.savedStateHandle
                .getStateFlow("oauth_zk_cancelled", false)
                .collectAsState()

            LoginRoute(
                onScreenDisplayed = if (startDestination == "login") {
                    onInitialDestinationDisplayed
                } else {
                    null
                },
                oauthZkCancelled = oauthZkCancelled,
                onOauthZkCancelConsumed = {
                    backStackEntry.savedStateHandle["oauth_zk_cancelled"] = false
                },
                onOAuthPairingCredentialsResolved = { qrEncrypted, pin ->
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("oauth_qr_encrypted", qrEncrypted)
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("oauth_pin", pin)
                    navController.navigate("oauth_zk")
                },
                onNavigateToScan = {
                    navController.navigate("scan")
                }
            )
        }

        composable("oauth_zk") {
            val qrEncrypted = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<String>("oauth_qr_encrypted")
            val pin = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<Int>("oauth_pin")

            OAuthZeroKnowledgeSetupScreen(
                qrEncrypted = qrEncrypted,
                pin = pin,
                onAuthenticationSuccess = {
                    navController.navigate("sync") {
                        popUpTo("login") {
                            inclusive = true
                        }
                    }
                },
                onAuthenticationSuccessWithoutCse = {
                    navController.navigate("mnemonic") {
                        popUpTo("login") {
                            inclusive = true
                        }
                    }
                },
                onCancel = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("oauth_zk_cancelled", true)
                    navController.popBackStack("login", false)
                }
            )
        }

        composable("profile") {
            ProfileScreen(
                onLogout = {
                    navController.navigate("login") {
                        popUpTo(0) // Clear the entire back stack
                    }
                },
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToSubscription = {
                    navController.navigate("subscription")
                },
                onNavigateToFolders = {
                    navController.navigate("folders")
                }
            )
        }

        composable(
            "folders?isFromScanSetup={isFromScanSetup}",
            arguments = listOf(navArgument("isFromScanSetup") { defaultValue = false; type = NavType.BoolType })
        ) { backStackEntry ->
            val isFromScanSetup = backStackEntry.arguments?.getBoolean("isFromScanSetup") ?: false
            FoldersScreen(
                isFromScanSetup = isFromScanSetup,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("subscription") {
//            SubscriptionScreen()
        }

        composable("mnemonic") {
            MnemonicScreen(
                onScreenDisplayed = if (startDestination == "mnemonic") {
                    onInitialDestinationDisplayed
                } else {
                    null
                },
                onMnemonicConfirmed = {
                    navController.navigate("sync") {
                        popUpTo("mnemonic") {
                            inclusive = true
                        }
                    }
                }
            )
        }
    }
}
