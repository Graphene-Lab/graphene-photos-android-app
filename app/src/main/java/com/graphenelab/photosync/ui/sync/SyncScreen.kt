package com.graphenelab.photosync.ui.sync

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.graphenelab.photosync.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    syncViewModel: SyncViewModel = hiltViewModel(),
    onScreenDisplayed: (() -> Unit)? = null,
    onNavigateToProfile: () -> Unit,
    onNavigateToScanSetup: () -> Unit
) {
    val context = LocalContext.current

    val uiState by syncViewModel.uiState.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->

        Log.d("SyncScreen", "Permission result: $permissions")
        syncViewModel.handlePermissionResult(permissions)


    }

    // One-time launcher setup
    LaunchedEffect(Unit) {
        onScreenDisplayed?.invoke()
        withFrameNanos { }
        syncViewModel.onScreenStarted()
        syncViewModel.setPermissionLauncher(permissionLauncher)
    }

    LaunchedEffect(syncViewModel) {
        syncViewModel.events.collect { event ->
            when (event) {
                is SyncEvent.NavigateToScanSetup -> onNavigateToScanSetup()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(Icons.Default.Person, contentDescription = stringResource(R.string.sync_profile_cd))
                    }
                }
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(text = stringResource(R.string.sync_status_label), style = MaterialTheme.typography.titleMedium)
                        if (!uiState.isFullScanInProgress) {
                            if (uiState.completedPhotos > 0) {
                                Text(
                                    text = stringResource(R.string.sync_success, uiState.completedPhotos),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                if (uiState.noPhotosToSync) {
                                    Text(
                                        text = stringResource(R.string.sync_no_photos),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.sync_ready),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }

                            }

                        } else {
                            Text(
                                text = stringResource(R.string.sync_in_progress),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Row {
                                Text(
                                    text = stringResource(R.string.sync_uploaded_photos),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = uiState.completedPhotos.toString(),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            Row {
                                Text(
                                    text = stringResource(R.string.sync_failed_photos),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = uiState.failedPhotos.toString(),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            Row {
                                Text(
                                    text = stringResource(R.string.sync_discovered_photos),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = uiState.totalPhotosToBeUploaded.toString(),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }

                            Column {
                                Text(text = stringResource(R.string.sync_progress_info))
                                Text(text = uiState.progress?.let { progress ->
                                    "${progress.filename} ${progress.currentChunk}/${progress.totalChunks}"
                                } ?: stringResource(R.string.sync_progress_waiting))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))

                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.sync_auto_label),
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    supportingContent = {
                        Text(
                            stringResource(R.string.sync_auto_subtitle),
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = uiState.isBackgroundSyncScheduled,
                            onCheckedChange = syncViewModel::onFromNowSyncToggled,
                            enabled = !uiState.isFullScanInProgress
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                Text(stringResource(R.string.sync_manual_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.sync_manual_subtitle),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                val isFullScanning = uiState.isFullScanInProgress
                Button(
                    onClick = {
                        if (isFullScanning) syncViewModel.onStopFullScanButtonClicked() else syncViewModel.onStartFullScanButtonClicked(
                            context
                        )
                    },
                    enabled = true,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFullScanning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(48.dp)
                ) {
                    if (isFullScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = LocalContentColor.current,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.sync_stop_full_scan))
                    } else {
                        Text(stringResource(R.string.sync_start_full_scan))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = syncViewModel::onExplorerButtonClicked,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(48.dp)
                ) {
                    Text(if (uiState.isExplorerInstalled) stringResource(R.string.sync_open_explorer) else stringResource(R.string.sync_download_explorer))
                }

                if (uiState.permissionDenied) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))
                    Text(
                        text = stringResource(R.string.sync_permission_denied),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
