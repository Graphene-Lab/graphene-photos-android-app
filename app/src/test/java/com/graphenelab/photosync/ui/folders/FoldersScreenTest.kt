package com.graphenelab.photosync.ui.folders

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.graphenelab.photosync.R
import com.graphenelab.photosync.data.local.mediastore.PhotoLocalDataSource
import com.graphenelab.photosync.domain.model.ImageFolder
import com.graphenelab.photosync.domain.repositroy.ISyncRepository
import com.graphenelab.photosync.manager.PermissionSet
import com.graphenelab.photosync.manager.PhotoAccessLevel
import com.graphenelab.photosync.manager.interfaces.IBackgroundSyncManager
import com.graphenelab.photosync.manager.interfaces.IPermissionsManager
import com.graphenelab.photosync.ui.theme.AppTheme
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Screen-level coverage of the folders picker when photo access is missing, granted,
 * partially granted, or granted from the app settings.
 *
 * The regression this pins down: opening "Choose Folders" before pressing Start used to query
 * MediaStore with no permission, so the user only saw "No photo folders found" while the
 * manage-permissions action was hidden inside the non-empty branch.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
@LooperMode(LooperMode.Mode.PAUSED)
class FoldersScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val syncRepository = mockk<ISyncRepository>(relaxed = true)
    private val backgroundSyncManager = mockk<IBackgroundSyncManager>(relaxed = true)
    private val photoLocalDataSource = mockk<PhotoLocalDataSource>(relaxed = true)
    private val permissions = FakePermissionsManager()

    private val selections = MutableStateFlow<Set<String>>(emptySet())
    private var currentFolders: List<ImageFolder> = emptyList()

    private val camera = ImageFolder(bucketId = "11", displayName = "Camera", photoCount = 12, coverUri = null)
    private val screenshots = ImageFolder(bucketId = "22", displayName = "Screenshots", photoCount = 3, coverUri = null)

    @Before
    fun setUp() {
        selections.value = emptySet()
        currentFolders = emptyList()
        permissions.level = PhotoAccessLevel.None
        permissions.requests = 0
        permissions.launcherSet = false

        every { syncRepository.selectedFolders } returns selections
        every { syncRepository.hasInitializedFolders } returns MutableStateFlow(true)
        every { photoLocalDataSource.getAvailableFolders() } answers { currentFolders }
        coEvery { syncRepository.saveSelectedFolders(any()) } just Runs
    }

    private fun show(
        level: PhotoAccessLevel,
        folders: List<ImageFolder> = emptyList(),
        isFromScanSetup: Boolean = false,
        onBack: () -> Unit = {}
    ): FoldersViewModel {
        permissions.level = level
        currentFolders = folders
        val viewModel = FoldersViewModel(
            compose.activity.applicationContext,
            photoLocalDataSource,
            syncRepository,
            backgroundSyncManager,
            permissions,
            UnconfinedTestDispatcher()
        )
        compose.setContent {
            AppTheme(darkTheme = false, dynamicColor = false) {
                FoldersScreen(
                    isFromScanSetup = isFromScanSetup,
                    onNavigateBack = onBack,
                    viewModel = viewModel
                )
            }
        }
        return viewModel
    }

    @Test
    fun `missing access shows the permission prompt instead of an empty folder list`() {
        show(level = PhotoAccessLevel.None)
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.folders_permission_required_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.folders_permission_grant)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.folders_permission_open_settings)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.folders_no_folders)).assertDoesNotExist()

        assertTrue(permissions.launcherSet, "the launcher must be registered before the first evaluation")
        assertEquals(1, permissions.requests)
        verify(exactly = 0) { photoLocalDataSource.getAvailableFolders() }
    }

    @Test
    fun `granting from the prompt lists the folders`() {
        val viewModel = show(level = PhotoAccessLevel.None)
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.folders_permission_grant)).assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(2, permissions.requests)
        compose.onNodeWithText(camera.displayName).assertDoesNotExist()

        // Same callback the real permission launcher fires when the dialog is accepted.
        compose.runOnIdle {
            permissions.level = PhotoAccessLevel.Full
            currentFolders = listOf(camera, screenshots)
            viewModel.onPermissionResult()
        }
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.folders_permission_required_title)).assertDoesNotExist()
        compose.onNodeWithText(camera.displayName).assertIsDisplayed()
        compose.onNodeWithText(screenshots.displayName).assertIsDisplayed()
        verify { photoLocalDataSource.getAvailableFolders() }
    }

    @Test
    fun `full access with no folders shows the empty message and keeps the manage action`() {
        show(level = PhotoAccessLevel.Full, folders = emptyList())
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.folders_no_folders)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.folders_manage_permissions)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.folders_permission_required_title)).assertDoesNotExist()
        assertEquals(0, permissions.requests)
        verify(exactly = 1) { photoLocalDataSource.getAvailableFolders() }
    }

    @Test
    fun `limited access shows the limited banner and only the visible folders`() {
        show(level = PhotoAccessLevel.Limited, folders = listOf(screenshots))
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.folders_limited_access)).assertIsDisplayed()
        compose.onNodeWithText(screenshots.displayName).assertIsDisplayed()
        compose.onNodeWithText(camera.displayName).assertDoesNotExist()
        assertEquals(0, permissions.requests)
        verify(exactly = 1) { photoLocalDataSource.getAvailableFolders() }
    }

    @Test
    fun `limited access with nothing visible keeps the manage action reachable`() {
        show(level = PhotoAccessLevel.Limited, folders = emptyList())
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.folders_limited_access)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.folders_manage_permissions)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.folders_no_folders)).assertDoesNotExist()
        assertEquals(0, permissions.requests)
        verify(exactly = 1) { photoLocalDataSource.getAvailableFolders() }
    }

    @Test
    fun `tapping a folder saves the new selection`() {
        val viewModel = show(level = PhotoAccessLevel.Full, folders = listOf(camera, screenshots))
        compose.waitForIdle()
        coVerify(exactly = 0) { syncRepository.saveSelectedFolders(any()) }

        compose.runOnIdle { viewModel.toggleFolder(camera.bucketId, true) }
        compose.waitForIdle()

        coVerify(exactly = 1) { syncRepository.saveSelectedFolders(setOf(camera.bucketId)) }
    }

    @Test
    fun `tapping a folder keeps the selections that were already made`() {
        selections.value = setOf(screenshots.bucketId)
        val viewModel = show(level = PhotoAccessLevel.Full, folders = listOf(camera, screenshots))
        compose.waitForIdle()

        compose.runOnIdle { viewModel.toggleFolder(camera.bucketId, true) }
        compose.waitForIdle()

        coVerify(exactly = 1) {
            syncRepository.saveSelectedFolders(setOf(screenshots.bucketId, camera.bucketId))
        }
    }

    @Test
    fun `back button is handed to the caller`() {
        var back = 0
        show(level = PhotoAccessLevel.Full, folders = listOf(camera), onBack = { back++ })
        compose.waitForIdle()

        compose.onNodeWithContentDescription(text(R.string.folders_back_cd)).performClick()
        compose.waitForIdle()

        assertEquals(1, back)
    }

    @Test
    fun `scan setup hides the start button until folders are visible`() {
        val viewModel = show(level = PhotoAccessLevel.None, isFromScanSetup = true)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.sync_start_full_scan)).assertDoesNotExist()

        compose.runOnIdle {
            permissions.level = PhotoAccessLevel.Full
            currentFolders = listOf(camera)
            viewModel.onPermissionResult()
        }
        compose.waitForIdle()

        compose.onNodeWithText(camera.displayName).assertIsDisplayed()
        // The FAB's label is nested under cleared semantics; click the actual button.
        val startButton = compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText(text(R.string.sync_start_full_scan))),
            useUnmergedTree = true
        )
        startButton.assertIsDisplayed().performClick()
        compose.waitForIdle()

        verify(exactly = 1) { backgroundSyncManager.startFullScanService() }
    }

    private fun text(id: Int) = compose.activity.getString(id)

    /**
     * Drives the screen without touching the real permission platform: the test decides what the
     * access level is, exactly like the platform callback does in production.
     */
    private inner class FakePermissionsManager : IPermissionsManager {
        var level = PhotoAccessLevel.None
        var requests = 0
        var launcherSet = false

        override fun setLauncher(launcher: ActivityResultLauncher<Array<String>>) {
            launcherSet = true
        }

        override fun requestPermissions(permissionSet: PermissionSet) {
            requests++
        }

        override fun requestPhotoAccess() {
            requests++
        }

        override fun hasPermissions(context: Context, permissionSet: PermissionSet): Boolean =
            level == PhotoAccessLevel.Full

        override fun photoAccessLevel(context: Context): PhotoAccessLevel = level
    }
}
