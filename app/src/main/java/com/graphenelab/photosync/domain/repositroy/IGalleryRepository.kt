package com.graphenelab.photosync.domain.repositroy

import android.net.Uri
import com.graphenelab.photosync.domain.model.GalleryPhoto
import com.graphenelab.photosync.domain.model.TimeInterval

/**
 * Interface for accessing photos from the device's gallery.
 */
interface IGalleryRepository {
    /**
     * Get photos from the gallery starting from a specific timestamp (seconds) for a folder.
     * @param startTimeSeconds: Time from which to fetch photos.
     * @param bucketId: The ID of the folder.
     * @return List<GalleryPhoto>: A list of photos matching the filters.
     */
    fun getPhotos(startTimeSeconds: Long, bucketId: String): List<GalleryPhoto>

    /**
     * Get photos from the gallery within a specific time interval for a folder.
     * @param start: Start timestamp in seconds.
     * @param end: End timestamp in seconds.
     * @param bucketId: The ID of the folder.
     * @return List<GalleryPhoto>: A list of photos within the given interval.
     */
    fun getPhotosInInterval(start: Long, end: Long, bucketId: String): List<GalleryPhoto>

    /**
     * Gets URIs of synced photos for deletion within a folder.
     * @param intervals: List of time intervals representing synced photos.
     * @param bucketId: The ID of the folder.
     * @return List<Uri>: URIs of photos to delete.
     */
    fun getSyncedPhotosUris(intervals: List<TimeInterval>, bucketId: String): List<Uri>

    /**
     * Retrieves all folders on the device that contain photos.
     */
    fun getAvailableFolders(): List<com.graphenelab.photosync.domain.model.ImageFolder>
}
