package com.graphenelab.photosync.ui.folders

import android.content.Context
import com.graphenelab.photosync.data.local.mediastore.PhotoLocalDataSource
import com.graphenelab.photosync.domain.model.ImageFolder
import com.graphenelab.photosync.domain.repositroy.ISyncRepository
import com.graphenelab.photosync.manager.PhotoAccessLevel
import com.graphenelab.photosync.manager.interfaces.IBackgroundSyncManager
import com.graphenelab.photosync.manager.interfaces.IPermissionsManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Covers the folders screen behaviour when photo access is missing, partially granted,
 * granted later, or granted from the app settings.
 *
 * The key invariant is that MediaStore is never queried without access: an unpermitted query
 * silently returns an empty cursor, which used to be shown as "No photo folders found" and
 * hid the fact that permission was simply missing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("FoldersViewModel - photo access and folder loading")
class FoldersViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val context = mockk<Context>(relaxed = true)
    private val photoLocalDataSource = mockk<PhotoLocalDataSource>(relaxed = true)
    private val syncRepository = mockk<ISyncRepository>(relaxed = true)
    private val backgroundSyncManager = mockk<IBackgroundSyncManager>(relaxed = true)
    private val permissionsManager = mockk<IPermissionsManager>(relaxed = true)

    private var accessLevel = PhotoAccessLevel.None
    private var folders: List<ImageFolder> = emptyList()

    private val camera = ImageFolder(bucketId = "11", displayName = "Camera", photoCount = 12, coverUri = null)
    private val screenshots = ImageFolder(bucketId = "22", displayName = "Screenshots", photoCount = 3, coverUri = null)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        accessLevel = PhotoAccessLevel.None
        folders = emptyList()

        every { permissionsManager.photoAccessLevel(any()) } answers { accessLevel }
        every { permissionsManager.requestPhotoAccess() } just Runs
        every { permissionsManager.setLauncher(any()) } just Runs
        every { photoLocalDataSource.getAvailableFolders() } answers { folders }
        every { syncRepository.selectedFolders } returns flowOf(emptySet())
        every { syncRepository.hasInitializedFolders } returns flowOf(true)
        coEvery { syncRepository.saveSelectedFolders(any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): FoldersViewModel = FoldersViewModel(
        context = context,
        photoLocalDataSource = photoLocalDataSource,
        syncRepository = syncRepository,
        backgroundSyncManager = backgroundSyncManager,
        permissionsManager = permissionsManager,
        ioDispatcher = dispatcher
    )

    @Test
    fun `no access - shows permission state and requests access without querying MediaStore`() = runTest(dispatcher) {
        accessLevel = PhotoAccessLevel.None
        val viewModel = viewModel()

        viewModel.onScreenShown()
        advanceUntilIdle()

        assertEquals(FoldersPermissionState.Required, viewModel.uiState.value.permissionState)
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.availableFolders.isEmpty())
        verify(exactly = 0) { photoLocalDataSource.getAvailableFolders() }
        verify(exactly = 1) { permissionsManager.requestPhotoAccess() }
    }

    @Test
    fun `no access - auto request happens only once even if the screen is shown again`() = runTest(dispatcher) {
        accessLevel = PhotoAccessLevel.None
        val viewModel = viewModel()

        viewModel.onScreenShown()
        advanceUntilIdle()
        viewModel.onScreenShown()
        advanceUntilIdle()

        assertEquals(FoldersPermissionState.Required, viewModel.uiState.value.permissionState)
        verify(exactly = 1) { permissionsManager.requestPhotoAccess() }
        verify(exactly = 0) { photoLocalDataSource.getAvailableFolders() }
    }

    @Test
    fun `denied access - stays in permission state and does not query MediaStore`() = runTest(dispatcher) {
        accessLevel = PhotoAccessLevel.None
        val viewModel = viewModel()

        viewModel.onScreenShown()   // auto request
        advanceUntilIdle()
        viewModel.onPermissionResult() // user answered "Don't allow"
        advanceUntilIdle()

        assertEquals(FoldersPermissionState.Required, viewModel.uiState.value.permissionState)
        assertTrue(viewModel.uiState.value.availableFolders.isEmpty())
        verify(exactly = 0) { photoLocalDataSource.getAvailableFolders() }
        verify(exactly = 1) { permissionsManager.requestPhotoAccess() }
    }

    @Test
    fun `grant tap after a denial still issues a new request`() = runTest(dispatcher) {
        accessLevel = PhotoAccessLevel.None
        val viewModel = viewModel()

        viewModel.onScreenShown()
        advanceUntilIdle()
        viewModel.onPermissionResult()
        advanceUntilIdle()

        viewModel.requestPhotoAccess()
        advanceUntilIdle()

        assertEquals(FoldersPermissionState.Required, viewModel.uiState.value.permissionState)
        verify(exactly = 2) { permissionsManager.requestPhotoAccess() }
        verify(exactly = 0) { photoLocalDataSource.getAvailableFolders() }
    }

    @Test
    fun `granted access - loads the folders without asking for permission`() = runTest(dispatcher) {
        accessLevel = PhotoAccessLevel.Full
        folders = listOf(camera, screenshots)
        val viewModel = viewModel()

        viewModel.onScreenShown()
        advanceUntilIdle()

        assertEquals(FoldersPermissionState.Granted, viewModel.uiState.value.permissionState)
        assertEquals(2, viewModel.uiState.value.availableFolders.size)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
        verify(exactly = 0) { permissionsManager.requestPhotoAccess() }
        verify(exactly = 1) { photoLocalDataSource.getAvailableFolders() }
    }

    @Test
    fun `access granted after the prompt - folders load immediately`() = runTest(dispatcher) {
        accessLevel = PhotoAccessLevel.None
        val viewModel = viewModel()
        viewModel.onScreenShown()
        advanceUntilIdle()
        assertEquals(FoldersPermissionState.Required, viewModel.uiState.value.permissionState)

        accessLevel = PhotoAccessLevel.Full
        folders = listOf(camera, screenshots)
        viewModel.onPermissionResult()
        advanceUntilIdle()

        assertEquals(FoldersPermissionState.Granted, viewModel.uiState.value.permissionState)
        assertEquals(2, viewModel.uiState.value.availableFolders.size)
        verify(exactly = 1) { photoLocalDataSource.getAvailableFolders() }
    }

    @Test
    fun `refresh after app settings - picks up a grant made there and loads the folders`() = runTest(dispatcher) {
        accessLevel = PhotoAccessLevel.None
        val viewModel = viewModel()
        viewModel.onScreenShown()
        advanceUntilIdle()
        verify(exactly = 0) { photoLocalDataSource.getAvailableFolders() }

        accessLevel = PhotoAccessLevel.Full
        folders = listOf(camera)
        viewModel.refreshFolders()
        advanceUntilIdle()

        assertEquals(FoldersPermissionState.Granted, viewModel.uiState.value.permissionState)
        assertEquals(1, viewModel.uiState.value.availableFolders.size)
        verify(exactly = 1) { photoLocalDataSource.getAvailableFolders() }
    }

    @Test
    fun `limited access - reports Limited and lists only the visible folders`() = runTest(dispatcher) {
        accessLevel = PhotoAccessLevel.Limited
        folders = listOf(screenshots)
        val viewModel = viewModel()

        viewModel.onScreenShown()
        advanceUntilIdle()

        assertEquals(FoldersPermissionState.Limited, viewModel.uiState.value.permissionState)
        assertEquals(1, viewModel.uiState.value.availableFolders.size)
        verify(exactly = 1) { photoLocalDataSource.getAvailableFolders() }
        verify(exactly = 0) { permissionsManager.requestPhotoAccess() }
    }

    @Test
    fun `empty result with full access - stays Granted instead of becoming a permission prompt`() = runTest(dispatcher) {
        accessLevel = PhotoAccessLevel.Full
        folders = emptyList()
        val viewModel = viewModel()

        viewModel.onScreenShown()
        advanceUntilIdle()

        assertEquals(FoldersPermissionState.Granted, viewModel.uiState.value.permissionState)
        assertTrue(viewModel.uiState.value.availableFolders.isEmpty())
        assertFalse(viewModel.uiState.value.isLoading)
        verify(exactly = 1) { photoLocalDataSource.getAvailableFolders() }
        verify(exactly = 0) { permissionsManager.requestPhotoAccess() }
    }

    @Test
    fun `existing selections are preserved while the permission state changes`() = runTest(dispatcher) {
        every { syncRepository.selectedFolders } returns flowOf(setOf(camera.bucketId))
        accessLevel = PhotoAccessLevel.None
        val viewModel = viewModel()

        viewModel.onScreenShown()
        advanceUntilIdle()
        assertEquals(setOf(camera.bucketId), viewModel.uiState.value.selectedFolderIds)

        accessLevel = PhotoAccessLevel.Full
        folders = listOf(camera, screenshots)
        viewModel.onPermissionResult()
        advanceUntilIdle()

        assertEquals(FoldersPermissionState.Granted, viewModel.uiState.value.permissionState)
        assertEquals(setOf(camera.bucketId), viewModel.uiState.value.selectedFolderIds)
        verify(exactly = 0) { syncRepository.saveSelectedFolders(any()) }
    }

    @Test
    fun `first initialization - defaults to the Camera folder`() = runTest(dispatcher) {
        every { syncRepository.hasInitializedFolders } returns flowOf(false)
        accessLevel = PhotoAccessLevel.Full
        folders = listOf(camera, screenshots)
        val viewModel = viewModel()

        viewModel.onScreenShown()
        advanceUntilIdle()

        verify(exactly = 1) { syncRepository.saveSelectedFolders(setOf(camera.bucketId)) }
    }

    @Test
    fun `querying the store after a grant does not request permissions again`() = runTest(dispatcher) {
        // The full scan button is a separate concern: the folders screen itself must not
        // escalate to the sync permission set (which would include notifications).
        accessLevel = PhotoAccessLevel.Full
        folders = listOf(camera)
        val viewModel = viewModel()

        viewModel.onScreenShown()
        advanceUntilIdle()
        viewModel.refreshFolders()
        advanceUntilIdle()

        assertEquals(FoldersPermissionState.Granted, viewModel.uiState.value.permissionState)
        verify(exactly = 2) { photoLocalDataSource.getAvailableFolders() }
        verify(exactly = 0) { permissionsManager.requestPhotoAccess() }
        verify(exactly = 0) { permissionsManager.requestPermissions(any()) }
    }

    @Test
    fun `repeated requests while access is missing never reach the media store`() = runTest(dispatcher) {
        // The screen must keep asking through the manager and must never query the store,
        // not even before a launcher has been registered on the manager.
        accessLevel = PhotoAccessLevel.None
        val viewModel = viewModel()

        viewModel.onScreenShown()
        advanceUntilIdle()
        viewModel.requestPhotoAccess()
        viewModel.requestPhotoAccess()
        advanceUntilIdle()

        assertEquals(FoldersPermissionState.Required, viewModel.uiState.value.permissionState)
        assertTrue(viewModel.uiState.value.availableFolders.isEmpty())
        verify(exactly = 3) { permissionsManager.requestPhotoAccess() }
        verify(exactly = 0) { permissionsManager.setLauncher(any()) }
        verify(exactly = 0) { photoLocalDataSource.getAvailableFolders() }
    }
}
