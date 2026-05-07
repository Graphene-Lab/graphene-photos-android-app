package com.graphenelab.photosync.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A singleton object to manage and broadcast the state of the sync process.
 * The Service writes to it, and the ViewModel reads from it.
 * Implementation of Observable pattern.
 */
object SyncStatusManager {
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _currentFolderMetrics = MutableStateFlow(SyncMetrics())
    val currentFolderMetrics: StateFlow<SyncMetrics> = _currentFolderMetrics

    private val _sessionMetrics = MutableStateFlow(SyncMetrics())
    val sessionMetrics: StateFlow<SyncMetrics> = _sessionMetrics

    private val _currentFolderName = MutableStateFlow<String?>(null)
    val currentFolderName: StateFlow<String?> = _currentFolderName

    fun updateSyncStatus(isSyncing: Boolean) {
        _isSyncing.value = isSyncing
    }

    fun updateCurrentFolder(name: String?) {
        _currentFolderName.value = name
    }

    fun updateNoPhotosFoundToSync(noPhotosFound: Boolean) {
        _sessionMetrics.update { it.copy(noPhotosFoundToSync = noPhotosFound) }
    }

    fun resetSyncSession() {
        _isSyncing.value = false
        _currentFolderName.value = null
        _sessionMetrics.value = SyncMetrics()
        resetFolderCounters()
    }

    fun resetFolderCounters() {
        _currentFolderMetrics.value = SyncMetrics()
    }

    fun incrementSuccessfulSyncPhotosCount() {
        _currentFolderMetrics.update { it.copy(successful = it.successful + 1) }
        _sessionMetrics.update { it.copy(successful = it.successful + 1) }
    }

    fun incrementFailedSyncPhotosCount() {
        _currentFolderMetrics.update { it.copy(failed = it.failed + 1) }
        _sessionMetrics.update { it.copy(failed = it.failed + 1) }
    }

    fun increaseDiscoveredPhotosCount(countToIncrease: Int) {
        _currentFolderMetrics.update { it.copy(discovered = it.discovered + countToIncrease) }
        _sessionMetrics.update { it.copy(discovered = it.discovered + countToIncrease) }
    }
}
