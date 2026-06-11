package com.graphenelab.photosync.domain.usecase

import android.net.Uri
import com.graphenelab.photosync.domain.repositroy.IGalleryRepository
import com.graphenelab.photosync.domain.repositroy.ISyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

class GetSyncedPhotoUrisForDeletionUseCase @Inject constructor(
    private val syncRepository: ISyncRepository,
    private val galleryRepository: IGalleryRepository
) {
    suspend operator fun invoke(): List<Uri> = withContext(Dispatchers.IO) {
        val selectedFolders = syncRepository.selectedFolders.first()

        selectedFolders.flatMap { bucketId ->
            val intervals = syncRepository.getSyncedIntervals(bucketId).first()
            if (intervals.isEmpty()) {
                emptyList()
            } else {
                galleryRepository.getSyncedPhotosUris(intervals, bucketId)
            }
        }
    }
}
