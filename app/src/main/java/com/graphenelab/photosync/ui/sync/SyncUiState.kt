package com.graphenelab.photosync.ui.sync

import com.graphenelab.photosync.common.PhotoSyncStatusManager
import com.graphenelab.photosync.common.SyncMetrics

// UI State class for the ViewModel
data class SyncUiState(
    val isFullScanInProgress: Boolean = false,
    val isBackgroundSyncScheduled: Boolean = false,
    val statusText: String = "Ready.",
    val folderMetrics: SyncMetrics = SyncMetrics(),
    val sessionMetrics: SyncMetrics = SyncMetrics(),
    val progress: PhotoSyncStatusManager.PhotoProgress? = null,
    val permissionDenied: Boolean = false,
    val startFullScanButtonClicked: Boolean = false,
    val syncFromNowButtonClicked: Boolean = false,
    val isExplorerInstalled: Boolean = false,
    val currentFolderName: String? = null
)
