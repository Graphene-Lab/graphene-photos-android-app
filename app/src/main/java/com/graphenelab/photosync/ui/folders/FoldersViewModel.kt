package com.graphenelab.photosync.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.graphenelab.photosync.data.local.mediastore.PhotoLocalDataSource
import com.graphenelab.photosync.domain.model.ImageFolder
import com.graphenelab.photosync.domain.repositroy.ISyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.graphenelab.photosync.manager.interfaces.IBackgroundSyncManager

data class FoldersUiState(
    val isLoading: Boolean = true,
    val availableFolders: List<ImageFolder> = emptyList(),
    val selectedFolderIds: Set<String> = emptySet(),
    val error: String? = null
)

@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val photoLocalDataSource: PhotoLocalDataSource,
    private val syncRepository: ISyncRepository,
    private val backgroundSyncManager: IBackgroundSyncManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(FoldersUiState())
    val uiState: StateFlow<FoldersUiState> = _uiState.asStateFlow()

    init {
        loadFolders()
        observeSelectedFolders()
    }

    private fun loadFolders() {
        viewModelScope.launch {
            try {
                val folders = withContext(Dispatchers.IO) {
                    photoLocalDataSource.getAvailableFolders()
                }

                val isInitialized = syncRepository.hasInitializedFolders.first()
                if (!isInitialized) {
                    val cameraFolder = folders.find { it.displayName.equals("Camera", ignoreCase = true) }
                    if (cameraFolder != null) {
                        syncRepository.saveSelectedFolders(setOf(cameraFolder.bucketId))
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    availableFolders = folders,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error occurred"
                )
            }
        }
    }

    fun refreshFolders() {
        loadFolders()
    }

    private fun observeSelectedFolders() {
        viewModelScope.launch {
            syncRepository.selectedFolders.collect { selected ->
                _uiState.value = _uiState.value.copy(selectedFolderIds = selected)
            }
        }
    }

    fun toggleFolder(bucketId: String, isSelected: Boolean) {
        viewModelScope.launch {
            val currentSelected = _uiState.value.selectedFolderIds.toMutableSet()
            if (isSelected) {
                currentSelected.add(bucketId)
            } else {
                currentSelected.remove(bucketId)
            }
            syncRepository.saveSelectedFolders(currentSelected)
        }
    }

    fun startFullScan() {
        backgroundSyncManager.startFullScanService()
    }
}
