package com.graphenelab.photosync.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.graphenelab.photosync.manager.interfaces.IPermissionsManager
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject

/**
 * Concrete implementation of [IPermissionsManager].
 * Manages permission requests and checks for granted permissions.
 */
@ViewModelScoped
class PermissionsManager @Inject constructor() : IPermissionsManager {
    private var launcher: ActivityResultLauncher<Array<String>>? = null

    override fun setLauncher(launcher: ActivityResultLauncher<Array<String>>) {
        this.launcher = launcher
    }

    override fun requestPermissions(permissionSet: PermissionSet) {
        launcher?.launch(permissionSet.permissions.toTypedArray())
    }

    override fun requestPhotoAccess() {
        launcher?.launch(PermissionSet.STORAGE.toTypedArray())
    }

    override fun hasPermissions(context: Context, permissionSet: PermissionSet): Boolean {
        return hasPermissions(context, permissionSet.permissions)
    }

    private fun hasPermissions(context: Context, permissions: Set<String>): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun photoAccessLevel(context: Context): PhotoAccessLevel {
        // Since Android 13 the photo library is readable only through the media permissions;
        // before that, READ_EXTERNAL_STORAGE covers the whole shared storage.
        val libraryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (isGranted(context, libraryPermission)) return PhotoAccessLevel.Full

        // Android 14+ "Select photos": only the photos the user picked are visible to the app.
        val userSelectedOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

        return if (userSelectedOnly) PhotoAccessLevel.Limited else PhotoAccessLevel.None
    }

    private fun isGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * How much of the device photo library the app can currently read.
 *
 * [Full] means the whole media library can be queried, [Limited] means only the photos the
 * user explicitly selected are visible, [None] means the app has no photo access at all.
 */
enum class PhotoAccessLevel {
    None,
    Limited,
    Full
}

sealed class PermissionSet(val permissions: Set<String>) {
    companion object {
        // Common permission sets
        val CAMERA = setOf(Manifest.permission.CAMERA)

        val STORAGE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            setOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // Notification permission for Android 13+ (API 33+)
        val NOTIFICATION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptySet() // No runtime permission needed for older versions
        }

        // Combined essential permissions for sync functionality
        val SYNC_ESSENTIALS = buildSet {
            addAll(STORAGE)
            addAll(NOTIFICATION)
        }
    }

    // Specific permission sets
    object Camera : PermissionSet(CAMERA)
    object Storage : PermissionSet(STORAGE)
    object Notification : PermissionSet(NOTIFICATION)
    object SyncEssentials : PermissionSet(SYNC_ESSENTIALS)

    // Custom permission set constructor
    class Custom(permissions: Set<String>) : PermissionSet(permissions)
}
