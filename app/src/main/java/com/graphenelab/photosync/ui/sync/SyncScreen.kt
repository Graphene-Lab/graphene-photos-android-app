package com.graphenelab.photosync.ui.sync

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.graphenelab.photosync.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    syncViewModel: SyncViewModel = hiltViewModel(),
    onScreenDisplayed: (() -> Unit)? = null,
    onNavigateToProfile: () -> Unit,
    onNavigateToScanSetup: () -> Unit,
    onNavigateToFolders: () -> Unit
) {
    val context = LocalContext.current
    var showAutoSyncInfo by rememberSaveable { mutableStateOf(false) }
    var showFullScanInfo by rememberSaveable { mutableStateOf(false) }

    val autoSyncInfo = stringResource(R.string.sync_auto_info)
    val fullScanInfo = stringResource(R.string.sync_manual_subtitle)

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
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.sync_settings_cd))
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
                            if (uiState.sessionMetrics.successful > 0 || uiState.sessionMetrics.failed > 0) {
                                Text(
                                    text = stringResource(R.string.sync_success, uiState.sessionMetrics.successful),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (uiState.sessionMetrics.failed > 0) {
                                    Text(
                                        text = stringResource(R.string.sync_failed_photos) + ": " + uiState.sessionMetrics.failed,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            } else {
                                if (uiState.sessionMetrics.noPhotosFoundToSync) {
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
                            if (uiState.currentFolderName != null) {
                                Text(
                                    text = stringResource(R.string.sync_scanning_folder, uiState.currentFolderName!!),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Row {
                                Text(
                                    text = stringResource(R.string.sync_uploaded_photos),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = uiState.folderMetrics.successful.toString(),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            Row {
                                Text(
                                    text = stringResource(R.string.sync_failed_photos),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = uiState.folderMetrics.failed.toString(),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            Row {
                                Text(
                                    text = stringResource(R.string.sync_discovered_photos),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = uiState.folderMetrics.discovered.toString(),
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

                val (autoSyncAnnotated, autoSyncInlineMap) = infoLabel(
                    label = stringResource(R.string.sync_auto_label),
                    onInfoClick = { showAutoSyncInfo = true }
                )

                ListItem(
                    headlineContent = {
                        Text(
                            text = autoSyncAnnotated,
                            inlineContent = autoSyncInlineMap,
                            style = MaterialTheme.typography.titleMedium
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

                InfoDialog(
                    show = showAutoSyncInfo,
                    onDismiss = { showAutoSyncInfo = false },
                    text = autoSyncInfo
                )

                InfoDialog(
                    show = showFullScanInfo,
                    onDismiss = { showFullScanInfo = false },
                    text = fullScanInfo
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                val (fullScanAnnotated, fullScanInlineMap) = infoLabel(
                    label = stringResource(R.string.sync_manual_title),
                    onInfoClick = { showFullScanInfo = true }
                )

                ListItem(
                    headlineContent = {
                        Text(
                            text = fullScanAnnotated,
                            inlineContent = fullScanInlineMap,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                val isFullScanning = uiState.isFullScanInProgress
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (isFullScanning) syncViewModel.onStopFullScanButtonClicked() else syncViewModel.onStartFullScanButtonClicked(context)
                        },
                        enabled = true,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isFullScanning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isFullScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = LocalContentColor.current,
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.sync_stop_full_scan),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.sync_start_full_scan),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onNavigateToFolders,
                        modifier = Modifier.weight(0.8f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.sync_choose_folder),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.sync_uploaded_photos_question),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = syncViewModel::onExplorerButtonClicked,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .heightIn(min = 48.dp)
                ) {
                    Text(
                        text = if (uiState.isExplorerInstalled) stringResource(R.string.sync_open_explorer) else stringResource(R.string.sync_download_explorer),
                        textAlign = TextAlign.Center
                    )
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

@Composable
private fun InfoDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    text: String
) {
    if (show) {
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_ok))
                }
            }
        )
    }
}

@Composable
private fun infoLabel(
    label: String,
    onInfoClick: () -> Unit
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inlineMap = mapOf(
        "info" to InlineTextContent(
            Placeholder(20.sp, 20.sp, PlaceholderVerticalAlign.Center)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Info",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onInfoClick() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
    val annotatedString = buildAnnotatedString {
        append(label)
        append(" ")
        appendInlineContent("info", "[info]")
    }
    return annotatedString to inlineMap
}
