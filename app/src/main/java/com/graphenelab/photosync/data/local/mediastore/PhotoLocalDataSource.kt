package com.graphenelab.photosync.data.local.mediastore

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.graphenelab.photosync.domain.model.GalleryPhoto
import com.graphenelab.photosync.domain.model.ImageFolder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class PhotoLocalDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun getPhotos(startTimeSeconds: Long, bucketId: String): List<GalleryPhoto> {
        return queryPhotos(startTimeSeconds = startTimeSeconds, bucketId = bucketId)
    }

    fun getPhotosInInterval(start: Long, end: Long, bucketId: String): List<GalleryPhoto> {
        if (start > end) return emptyList()
        return queryPhotos(startTimeSeconds = start, endTimeSeconds = end, bucketId = bucketId)
    }

    private fun queryPhotos(
        startTimeSeconds: Long? = null,
        endTimeSeconds: Long? = null,
        bucketId: String
    ): List<GalleryPhoto> {
        val photos = mutableListOf<GalleryPhoto>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATA
        )

        val selectionParts = mutableListOf<String>()
        val selectionArgsList = mutableListOf<String>()

        startTimeSeconds?.let {
            selectionParts.add("${MediaStore.Images.Media.DATE_ADDED} >= ?")
            selectionArgsList.add(it.toString())
        }

        endTimeSeconds?.let {
            selectionParts.add("${MediaStore.Images.Media.DATE_ADDED} <= ?")
            selectionArgsList.add(it.toString())
        }

        selectionParts.add("${MediaStore.Images.Media.BUCKET_ID} = ?")
        selectionArgsList.add(bucketId)

        val selection = selectionParts.joinToString(" AND ")
        val selectionArgs = selectionArgsList.toTypedArray()

        //TODO: problem can occur with photos with same date added since DATE_ADDED is in seconds. Consider adding _ID to sort order for tie-breaking.
        // for example user took 2 photos in same second, and when full syncing we sync first photo and app crashes before syncing second photo,
        // so when sync starts again we omit second, since we already saved last sync time of the interval when syncing first one, and now we start from last sync time + 1...
        // (on the other hand to solve this issue if we start from last sync (without +1) time then we will have reuploads...)
        // Ps crash while syncing exactly at the moment syncing happens with same second can happen in rare cases so not critical for now.
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"
        //note: if no photos found check if permission granted.
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val dateAdded =
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                val lastModifiedSeconds =
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED))
                val displayName =
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                val contentUri: Uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                photos.add(
                    GalleryPhoto(
                        id = id,
                        dateAdded = dateAdded,
                        lastModifiedSeconds = lastModifiedSeconds,
                        displayName = displayName,
                        path = contentUri
                    )
                )
            }
        }

        return photos
    }

    /**
     * Gets URIs of synced photos for deletion.
     * Returns list of photo URIs to delete.
     */
    fun getSyncedPhotosUris(intervals: List<com.graphenelab.photosync.domain.model.TimeInterval>, bucketId: String): List<Uri> {
        if (intervals.isEmpty()) return emptyList()

        val photoUris = mutableListOf<Uri>()

        // Collect photo URIs for each interval
        intervals.forEach { interval ->
            val photos = getPhotosInInterval(interval.start, interval.end, bucketId)
            photoUris.addAll(photos.map { it.path })
        }

        return photoUris
    }

    fun getAvailableFolders(): List<ImageFolder> {
        val folders = mutableListOf<ImageFolder>()
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media._ID
        )

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

            val folderMap = mutableMapOf<String, ImageFolder>()

            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdCol) ?: continue
                val bucketName = cursor.getString(bucketNameCol) ?: "Unknown"
                
                if (folderMap.containsKey(bucketId)) {
                    val existing = folderMap[bucketId]!!
                    folderMap[bucketId] = existing.copy(photoCount = existing.photoCount + 1)
                } else {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    folderMap[bucketId] = ImageFolder(
                        bucketId = bucketId,
                        displayName = bucketName,
                        photoCount = 1,
                        coverUri = contentUri
                    )
                }
            }
            folders.addAll(folderMap.values)
        }
        return folders
    }

    companion object {
    }
}
