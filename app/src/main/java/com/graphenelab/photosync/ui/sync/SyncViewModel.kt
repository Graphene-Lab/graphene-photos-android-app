package com.graphenelab.photosync.ui.sync

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.graphenelab.photosync.common.AppStartupTrace
import com.graphenelab.photosync.common.PhotoSyncStatusManager
import com.graphenelab.photosync.common.SyncStatusManager
import com.graphenelab.photosync.manager.PermissionSet
import com.graphenelab.photosync.manager.interfaces.IBackgroundSyncManager
import com.graphenelab.photosync.manager.interfaces.IExplorerAppManager
import com.graphenelab.photosync.manager.interfaces.IPermissionsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SyncEvent {
    object NavigateToScanSetup : SyncEvent()
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val backgroundSyncManager: IBackgroundSyncManager,
    private val permissionsManager: IPermissionsManager,
    private val explorerAppManager: IExplorerAppManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private val _events = Channel<SyncEvent>()
    val events = _events.receiveAsFlow()

    private var screenStarted = false

    init {
        // Observe all sync-related flows and combine them into a single UI state update.
        combine(
            SyncStatusManager.isSyncing,
            SyncStatusManager.currentFolderMetrics,
            SyncStatusManager.sessionMetrics,
            SyncStatusManager.currentFolderName,
            PhotoSyncStatusManager.currentPhotoProgress
        ) { isSyncing, folderMetrics, sessionMetrics, folderName, progress ->
            _uiState.update {
                it.copy(
                    isFullScanInProgress = isSyncing,
                    folderMetrics = folderMetrics,
                    sessionMetrics = sessionMetrics,
                    currentFolderName = folderName,
                    progress = progress
                )
            }
        }.launchIn(viewModelScope)
    }

    fun onScreenStarted() {
        if (screenStarted) return
        screenStarted = true
        AppStartupTrace.mark("SyncViewModel.onScreenStarted")
        refreshExplorerInstallState()

        backgroundSyncManager.getPeriodicSyncWorkInfoFlow()
            .onEach { isScheduled ->
                _uiState.update { it.copy(isBackgroundSyncScheduled = isScheduled) }
            }
            .launchIn(viewModelScope)
    }

    fun onFromNowSyncToggled(isEnabled: Boolean) {
        _uiState.update { it.copy(syncFromNowButtonClicked = true) }
        viewModelScope.launch {
            if (isEnabled) {
                backgroundSyncManager.schedulePeriodicSync()
            } else {
                backgroundSyncManager.cancelPeriodicSync()
            }
        }
    }

    fun onStartFullScanButtonClicked(context: Context) {
        _uiState.update { it.copy(startFullScanButtonClicked = true) }
        if (permissionsManager.hasPermissions(context, PermissionSet.SyncEssentials)) {
            startFullScan()

        } else {
            requestSyncPermissions()
        }
    }

    fun onStopFullScanButtonClicked() {
        _uiState.update {
            it.copy(
                isFullScanInProgress = false
            )
        }
        SyncStatusManager.updateSyncStatus(false)
        stopFullScan()
    }

    private fun requestSyncPermissions() {
        permissionsManager.requestPermissions(PermissionSet.SyncEssentials)
    }

    fun startFullScan() {
        _uiState.update {
            it.copy(
                permissionDenied = false,
            )
        }
        backgroundSyncManager.startFullScanService()
    }

    fun stopFullScan() {
        backgroundSyncManager.stopFullScanService()
    }

    fun refreshExplorerInstallState() {
        _uiState.update {
            it.copy(
                isExplorerInstalled = explorerAppManager.isExplorerInstalled()
            )
        }
    }

    fun onExplorerButtonClicked() {
        val isExplorerInstalled = explorerAppManager.isExplorerInstalled()
        _uiState.update {
            it.copy(
                isExplorerInstalled = isExplorerInstalled
            )
        }

        if (isExplorerInstalled) {
            explorerAppManager.openExplorer()
        } else {
            explorerAppManager.openExplorerDownloadPage()
        }
        refreshExplorerInstallState()
    }

    fun setPermissionLauncher(launcher: ActivityResultLauncher<Array<String>>) {
        permissionsManager.setLauncher(launcher)
    }

    fun handlePermissionResult(permissions: Map<String, Boolean>) {
        Log.d(
            "SyncViewModel",
            "handlePermissionResult: ${
                permissions.getOrDefault(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    false
                )
            }"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasMediaPermission = permissions.getOrDefault(Manifest.permission.READ_MEDIA_IMAGES, false)
                    || permissions.getOrDefault(Manifest.permission.READ_MEDIA_VIDEO, false)
                    || permissions.getOrDefault(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, false)

            if (hasMediaPermission) {
                if (_uiState.value.startFullScanButtonClicked) {
                    viewModelScope.launch {
                        _events.send(SyncEvent.NavigateToScanSetup)
                    }
                } else if (_uiState.value.syncFromNowButtonClicked) {
                    onFromNowSyncToggled(true)
                }

                _uiState.update {
                    it.copy(permissionDenied = false)
                }
            } else {
                _uiState.update {
                    it.copy(permissionDenied = true)
                }
            }
        } else {
            if (permissions.getOrDefault(Manifest.permission.READ_EXTERNAL_STORAGE, false)) {
                Log.d("SyncViewModel", "Permission granted")
                if (_uiState.value.startFullScanButtonClicked) {
                    viewModelScope.launch {
                        _events.send(SyncEvent.NavigateToScanSetup)
                    }
                } else if (_uiState.value.syncFromNowButtonClicked) {
                    onFromNowSyncToggled(true)
                }
            } else {
                _uiState.update {
                    it.copy(permissionDenied = true)
                }
            }
        }
    }
}
