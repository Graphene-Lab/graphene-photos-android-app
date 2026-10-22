package com.graphenelab.photosync.ui.folders

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.graphenelab.photosync.R
import com.graphenelab.photosync.domain.model.ImageFolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    isFromScanSetup: Boolean = false,
    onNavigateBack: () -> Unit,
    viewModel: FoldersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val error = uiState.error

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The grant state can change while the user is in system settings.
        viewModel.refreshFolders()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.onPermissionResult() }

    LaunchedEffect(Unit) {
        viewModel.setPermissionLauncher(permissionLauncher)
        viewModel.onScreenShown()
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        settingsLauncher.launch(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.folders_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.folders_back_cd)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (isFromScanSetup && uiState.availableFolders.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.startFullScan()
                        onNavigateBack()
                    },
                    icon = { },
                    text = { Text(stringResource(R.string.sync_start_full_scan)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.permissionState == FoldersPermissionState.Required -> {
                    PhotoPermissionRequired(
                        onGrantClick = { viewModel.requestPhotoAccess() },
                        onOpenSettingsClick = { openAppSettings() }
                    )
                }

                uiState.permissionState == FoldersPermissionState.Checking || uiState.isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.folders_loading))
                    }
                }

                error != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.common_error_prefix, error),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.refreshFolders() }) {
                            Text(stringResource(R.string.folders_retry))
                        }
                    }
                }

                uiState.availableFolders.isEmpty() -> {
                    // Reached only when access is enough to query and the query really returned
                    // no photo folder, so the manage-access action stays reachable.
                    val limited = uiState.permissionState == FoldersPermissionState.Limited
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(
                                if (limited) R.string.folders_limited_access else R.string.folders_no_folders
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { openAppSettings() }) {
                            Text(stringResource(R.string.folders_manage_permissions))
                        }
                    }
                }

                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (uiState.permissionState == FoldersPermissionState.Limited) {
                            LimitedAccessBanner(onClick = { openAppSettings() })
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            items(uiState.availableFolders, key = { it.bucketId }) { folder ->
                                val isSelected = uiState.selectedFolderIds.contains(folder.bucketId)
                                FolderItem(
                                    folder = folder,
                                    isSelected = isSelected,
                                    onToggle = { selected ->
                                        viewModel.toggleFolder(folder.bucketId, selected)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoPermissionRequired(
    onGrantClick: () -> Unit,
    onOpenSettingsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.folders_permission_required_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.folders_permission_required_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onGrantClick) {
            Text(stringResource(R.string.folders_permission_grant))
        }
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onOpenSettingsClick) {
            Text(stringResource(R.string.folders_permission_open_settings))
        }
    }
}

@Composable
private fun LimitedAccessBanner(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = stringResource(R.string.folders_limited_access),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun FolderItem(
    folder: ImageFolder,
    isSelected: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onToggle(!isSelected) },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.folders_item_photos_count, folder.photoCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = isSelected,
                onCheckedChange = { onToggle(it) }
            )
        }
    }
}
