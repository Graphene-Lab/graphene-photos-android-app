package com.graphenelab.photosync.ui.common

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
fun SyncedPhotosDeleteHandler(
    photoUrisToDelete: List<Uri>?,
    onDeletePermissionResult: (granted: Boolean, photosCount: Int) -> Unit,
    onPhotoUrisToDeleteConsumed: () -> Unit,
    logTag: String
) {
    val context = LocalContext.current
    var pendingDeleteUris by remember { mutableStateOf<List<Uri>?>(null) }

    val deletePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        val deletedCount = pendingDeleteUris?.size ?: 0

        Log.d(logTag, "Delete permission result: resultCode=${result.resultCode}, granted=$granted")
        onDeletePermissionResult(granted, if (granted) deletedCount else 0)
        pendingDeleteUris = null
    }

    LaunchedEffect(photoUrisToDelete) {
        val photoUris = photoUrisToDelete ?: return@LaunchedEffect
        if (photoUris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@LaunchedEffect

        pendingDeleteUris = photoUris
        try {
            Log.d(logTag, "Creating delete request for ${photoUris.size} photos")
            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, ArrayList(photoUris))
            deletePermissionLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
            onPhotoUrisToDeleteConsumed()
        } catch (e: Exception) {
            Log.e(logTag, "Failed to create delete request", e)
            pendingDeleteUris = null
            onDeletePermissionResult(false, 0)
        }
    }
}
