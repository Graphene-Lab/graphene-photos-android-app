package com.graphenelab.photosync.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.graphenelab.photosync.R
import com.graphenelab.photosync.ui.common.SyncedPhotosDeleteHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onLogout: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToSubscription: () -> Unit,
    onNavigateToFolders: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    SyncedPhotosDeleteHandler(
        photoUrisToDelete = uiState.photoUrisToDelete,
        onDeletePermissionResult = viewModel::onDeletePermissionResult,
        onPhotoUrisToDeleteConsumed = viewModel::clearPhotoUrisToDelete,
        logTag = "ProfileScreen"
    )

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ProfileEvent.NavigateToLogin -> {
                    event.toastMessage?.let {
                        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    }
                    onLogout()
                }
            }
        }
    }

    fun copyToClipboard(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.profile_copied_clipboard, label), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.profile_back_cd))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Only show Email and Subscription Plan if NOT in QR login mode
            if (!uiState.isQrLoginMode) {
                // Email Section
                Text(
                    text = stringResource(R.string.profile_email_label),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.email ?: stringResource(R.string.profile_loading),
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Cloud Credentials Section (always shown)
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.profile_cloud_credentials_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    when {
                        uiState.isLoadingCredentials -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.profile_loading_credentials))
                            }
                        }

                        uiState.credentialsError != null -> {
                            Text(
                                text = stringResource(R.string.common_error_prefix, uiState.credentialsError!!),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        uiState.cloudCredentials == null -> {
                            Text(
                                text = stringResource(R.string.profile_no_credentials),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        else -> {
                            val credentials = uiState.cloudCredentials
                            Text(
                                text = stringResource(R.string.profile_encrypted_qr),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = credentials?.qrEncrypted.orEmpty(),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    copyToClipboard(
                                        context.getString(R.string.profile_encrypted_qr),
                                        credentials?.qrEncrypted.orEmpty()
                                    )
                                }
                            ) {
                                Text(stringResource(R.string.profile_copy_qr))
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = stringResource(R.string.profile_pin_label),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = credentials?.pin?.toString()?.padStart(6, '0').orEmpty(),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    copyToClipboard(
                                        context.getString(R.string.profile_pin_label),
                                        credentials?.pin?.toString()?.padStart(6, '0').orEmpty()
                                    )
                                }
                            ) {
                                Text(stringResource(R.string.profile_copy_pin))
                            }

                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Current Plan Section (only show if NOT in QR login mode)
            if (!uiState.isQrLoginMode) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.profile_current_plan_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (!uiState.isLoadingPlan) {
                                IconButton(onClick = { viewModel.refreshSubscriptionPlan() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.profile_refresh_cd))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        when {
                            uiState.isLoadingPlan -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.profile_loading_plan))
                                }
                            }

                            uiState.planError != null -> {
                                Text(
                                    text = stringResource(R.string.common_error_prefix, uiState.planError!!),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            uiState.currentPlan != null -> {
                                val plan = uiState.currentPlan!!
                                Column {
                                    Text(
                                        text = plan.name,
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = plan.displayAmount,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = plan.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Button(onClick = onNavigateToSubscription) {
                //     Text("Manage Subscription")
                // }

                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Clear Synced Photos Section - Only show for Android 11+ (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var showDeleteConfirmDialog by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.profile_clear_photos_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.profile_clear_photos_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { showDeleteConfirmDialog = true },
                            enabled = !uiState.isDeletingSyncedPhotos,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            if (uiState.isDeletingSyncedPhotos) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onError
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.common_delete_cd)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (uiState.isDeletingSyncedPhotos) stringResource(R.string.profile_deleting) else stringResource(R.string.profile_clear_synced_photos))
                        }

                        uiState.deletedPhotosCount?.let { count ->
                            Spacer(modifier = Modifier.height(8.dp))
                            if (count > 0) {
                                Text(
                                    text = stringResource(R.string.profile_delete_success, count),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.profile_no_synced_photos),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        uiState.deleteError?.let { error ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.common_error_prefix, error),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (showDeleteConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirmDialog = false },
                        title = { Text(stringResource(R.string.profile_delete_photos_dialog_title)) },
                        text = {
                            Text(stringResource(R.string.profile_delete_photos_dialog_body))
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showDeleteConfirmDialog = false
                                    viewModel.deleteSyncedPhotos()
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text(stringResource(R.string.profile_delete_button))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirmDialog = false }) {
                                Text(stringResource(R.string.profile_cancel_button))
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.profile_choose_folders_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.profile_choose_folders_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onNavigateToFolders
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.profile_choose_folders_button))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            DangerZoneSection(
                isDeletingAccount = uiState.isDeletingAccount,
                deleteAccountError = uiState.deleteAccountError,
                onDeleteAccountConfirmed = { viewModel.deleteAccount() },
                onLogoutClick = {
                    viewModel.logout()
                    onLogout()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

}

@Composable
internal fun DangerZoneSection(
    isDeletingAccount: Boolean,
    deleteAccountError: String?,
    onDeleteAccountConfirmed: () -> Unit,
    onLogoutClick: () -> Unit
) {
    var showDeleteAccountConfirmDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.profile_danger_zone_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.profile_danger_zone_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { showDeleteAccountConfirmDialog = true },
                enabled = !isDeletingAccount,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("delete_account_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                if (isDeletingAccount) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.profile_deleting_account))
                } else {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.profile_delete_account))
                }
            }

            if (showDeleteAccountConfirmDialog) {
                AlertDialog(
                    onDismissRequest = {
                        if (!isDeletingAccount) {
                            showDeleteAccountConfirmDialog = false
                        }
                    },
                    title = { Text(stringResource(R.string.profile_delete_account_dialog_title)) },
                    text = {
                        Text(stringResource(R.string.profile_delete_account_dialog_body))
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteAccountConfirmDialog = false
                                onDeleteAccountConfirmed()
                            },
                            enabled = !isDeletingAccount,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.profile_delete_permanently))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showDeleteAccountConfirmDialog = false },
                            enabled = !isDeletingAccount
                        ) {
                            Text(stringResource(R.string.profile_cancel_button))
                        }
                    }
                )
            }

            deleteAccountError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.common_error_prefix, error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onLogoutClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.profile_logout))
            }
        }
    }
}


@Preview
@Composable
fun ProfileScreenPreview() {
    ProfileScreen({}, {}, {}, {})
}
