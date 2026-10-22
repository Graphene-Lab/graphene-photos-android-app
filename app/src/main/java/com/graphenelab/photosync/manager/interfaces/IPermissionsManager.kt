package com.graphenelab.photosync.manager.interfaces

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import com.graphenelab.photosync.manager.PermissionSet
import com.graphenelab.photosync.manager.PhotoAccessLevel

interface IPermissionsManager {
    fun setLauncher(launcher: ActivityResultLauncher<Array<String>>)
    fun requestPermissions(permissionSet: PermissionSet)

    /**
     * Requests the media permissions needed to read the device photo library, without
     * asking for unrelated permissions such as notifications.
     */
    fun requestPhotoAccess()

    fun hasPermissions(context: Context, permissionSet: PermissionSet): Boolean

    /**
     * Reports how much of the device photo library the app is currently allowed to read.
     * Screens should check this before querying MediaStore: an unpermitted query does not
     * fail, it silently returns an empty cursor.
     */
    fun photoAccessLevel(context: Context): PhotoAccessLevel
}
