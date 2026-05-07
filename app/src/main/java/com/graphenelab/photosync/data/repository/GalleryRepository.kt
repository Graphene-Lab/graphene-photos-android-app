package com.graphenelab.photosync.data.repository

import android.net.Uri
import com.graphenelab.photosync.data.local.mediastore.PhotoLocalDataSource
import com.graphenelab.photosync.domain.model.GalleryPhoto
import com.graphenelab.photosync.domain.model.ImageFolder
import com.graphenelab.photosync.domain.model.TimeInterval
import com.graphenelab.photosync.domain.repositroy.IGalleryRepository
import javax.inject.Inject
class GalleryRepositoryImpl @Inject constructor(
    private val localDataSource: PhotoLocalDataSource
) : IGalleryRepository {

    override fun getPhotos(startTimeSeconds: Long, bucketId: String): List<GalleryPhoto> {
        return localDataSource.getPhotos(startTimeSeconds, bucketId)
    }

    override fun getPhotosInInterval(start: Long, end: Long, bucketId: String): List<GalleryPhoto> {
        return localDataSource.getPhotosInInterval(start, end, bucketId)
    }

    override fun getSyncedPhotosUris(intervals: List<TimeInterval>, bucketId: String): List<Uri> {
        return localDataSource.getSyncedPhotosUris(intervals, bucketId)
    }

    override fun getAvailableFolders(): List<ImageFolder> {
        return localDataSource.getAvailableFolders()
    }
}

