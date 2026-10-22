package com.graphenelab.photosync.ui.folders

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.graphenelab.photosync.data.local.mediastore.PhotoLocalDataSource
import com.graphenelab.photosync.di.IoDispatcher
import com.graphenelab.photosync.domain.model.ImageFolder
import com.graphenelab.photosync.domain.repositroy.ISyncRepository
import com.graphenelab.photosync.manager.PhotoAccessLevel
import com.graphenelab.photosync.manager.interfaces.IBackgroundSyncManager
import com.graphenelab.photosync.manager.interfaces.IPermissionsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Photo access state of the folders screen.
 *
 * [Required] means the app cannot read the photo library yet, so no MediaStore query is issued:
 * an unpermitted query does not fail, it returns an empty cursor that would look to the user
 * like "no photo folders found" instead of a request for access.
 * [Limited] means only the photos the user explicitly selected are visible.
 */
enum class FoldersPermissionState {
    Checking,
    Required,
    Limited,
    Granted
}

data class FoldersUiState(
    val permissionState: FoldersPermissionState = FoldersPermissionState.Checking,
    val isLoading: Boolean = false,
    val availableFolders: List<ImageFolder> = emptyList(),
    val selectedFolderIds: Set<String> = emptySet(),
    val error: String? = null
)

@HiltViewModel
class FoldersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoLocalDataSource: PhotoLocalDataSource,
    private val syncRepository: ISyncRepository,
    private val backgroundSyncManager: IBackgroundSyncManager,
    private val permissionsManager: IPermissionsManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(FoldersUiState())
    val uiState: StateFlow<FoldersUiState> = _uiState.asStateFlow()

    private var autoRequestIssued = false

    init {
        observeSelectedFolders()
    }

    fun setPermissionLauncher(launcher: ActivityResultLauncher<Array<String>>) {
        permissionsManager.setLauncher(launcher)
    }

    /**
     * Called when the screen is shown. Requests photo access the first time it is missing,
     * so opening folder selection never depends on pressing Start.
     */
    fun onScreenShown() {
        applyAccess(requestIfMissing = true)
    }

    /** Asks for the media permissions only, never for unrelated permissions such as notifications. */
    fun requestPhotoAccess() {
        permissionsManager.requestPhotoAccess()
    }

    /** Called when the runtime permission dialog is dismissed, whatever the user answered. */
    fun onPermissionResult() {
        applyAccess(requestIfMissing = false)
    }

    /** Called when coming back from the app permission settings, where the state may have changed. */
    fun refreshFolders() {
        applyAccess(requestIfMissing = false)
    }

    private fun applyAccess(requestIfMissing: Boolean) {
        when (permissionsManager.photoAccessLevel(context)) {
            PhotoAccessLevel.Full -> {
                _uiState.value = _uiState.value.copy(permissionState = FoldersPermissionState.Granted)
                loadFolders()
            }

            PhotoAccessLevel.Limited -> {
                _uiState.value = _uiState.value.copy(permissionState = FoldersPermissionState.Limited)
                loadFolders()
            }

            PhotoAccessLevel.None -> {
                // Querying MediaStore here would return an empty cursor and the screen would
                // claim there are no folders, hiding the fact that access is simply missing.
                _uiState.value = _uiState.value.copy(
                    permissionState = FoldersPermissionState.Required,
                    isLoading = false
                )
                if (requestIfMissing && !autoRequestIssued) {
                    autoRequestIssued = true
                    requestPhotoAccess()
                }
            }
        }
    }

    private fun loadFolders() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val folders = withContext(ioDispatcher) {
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
