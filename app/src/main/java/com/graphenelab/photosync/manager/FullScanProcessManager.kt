package com.graphenelab.photosync.manager

import android.content.ContentResolver
import android.util.Log
import com.graphenelab.communication.crypto.IZeroKnowledgeProof
import com.graphenelab.photosync.BuildConfig
import com.graphenelab.photosync.common.PhotoSyncStatusManager
import com.graphenelab.photosync.common.SyncStatusManager
import com.graphenelab.photosync.common.UploadTimeouts
import com.graphenelab.photosync.common.config.SyncConfig
import com.graphenelab.photosync.domain.model.GalleryPhoto
import com.graphenelab.photosync.domain.model.TimeInterval
import com.graphenelab.photosync.domain.repositroy.IGalleryRepository
import com.graphenelab.photosync.domain.repositroy.ISyncRepository
import com.graphenelab.photosync.manager.interfaces.ICloudManager
import com.graphenelab.photosync.manager.interfaces.IFullScanProcessManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Concrete implementation of [com.graphenelab.photosync.manager.interfaces.IFullScanProcessManager].
 * Manages the logic for a comprehensive gallery photo synchronization,
 * including interval management, photo fetching, batch syncing.
 */
class FullScanProcessManager @Inject constructor(
    private val syncIntervalRepository: ISyncRepository,
    private val galleryRepository: IGalleryRepository,
    private val syncConfig: SyncConfig,
    private val contentResolver: ContentResolver,
    private val zeroKnowledgeProof: IZeroKnowledgeProof?,
    private val dataCenterCloudManager: ICloudManager
) : IFullScanProcessManager {

    private val concurrentLimit = Semaphore(2) // Allow max N concurrent operations

    override suspend fun initializeIntervals(bucketId: String): MutableList<TimeInterval> {
        if (BuildConfig.DEBUG) syncIntervalRepository.clearSyncData()// TODO: Note - clears synced photos.
        val allIntervals = syncIntervalRepository.getSyncedIntervals(bucketId).first().toMutableList()
        // Ensure the initial 0-timestamp interval exists for complete coverage.
        if (allIntervals.none { it.start == 0L }) {
            allIntervals.add(0, TimeInterval(0, 0))
        }
        allIntervals.sortBy { it.start }
        return allIntervals
    }

    override suspend fun processNextTwoIntervals(
        bucketId: String, currentIntervals: MutableList<TimeInterval>, currentCoroutineContext: CoroutineContext
    ): MutableList<TimeInterval> {
        val interval1 = currentIntervals[0]
        val interval2 = currentIntervals[1]

        val photosInGap =
            galleryRepository.getPhotosInInterval(interval1.end + 1, interval2.start - 1, bucketId)

        var tempInterval1 = interval1
        if (photosInGap.isNotEmpty()) {
            val onBatchSave: suspend (Long) -> Unit = { newEndTimestamp ->
                tempInterval1 = interval1.copy(end = newEndTimestamp)
                // Save current progress immediately for crash recovery.
                val updatedListForSave = currentIntervals.toMutableList()
                updatedListForSave[0] = tempInterval1
                syncIntervalRepository.saveSyncedIntervals(bucketId, updatedListForSave)
            }
            SyncStatusManager.increaseDiscoveredPhotosCount(photosInGap.size)
            syncAndSaveInBatches(
                currentCoroutineContext,
                photosInGap,
                onBatchSave
            )
        }

        val mergedInterval = mergeTwoIntervals(tempInterval1, interval2)

        // Replace the two processed intervals with the newly merged one.
        val newList = currentIntervals.drop(2).toMutableList()
        newList.add(0, mergedInterval)

        syncIntervalRepository.saveSyncedIntervals(bucketId, newList)
        return newList
    }

    override suspend fun processTailEnd(
        bucketId: String,
        currentIntervals: MutableList<TimeInterval>,
        currentCoroutineContext: CoroutineContext
    ): MutableList<TimeInterval> {
        // No tail end to process if the list is empty (should not happen if initialized correctly).
        if (currentIntervals.isEmpty()) return currentIntervals

        val finalInterval = currentIntervals.first()
        // Fetch photos beyond the last synced timestamp for this bucket.
        val photosInTail = galleryRepository.getPhotos(startTimeSeconds = finalInterval.end + 1, bucketId = bucketId)

        if (photosInTail.isNotEmpty()) {
            val onBatchSave: suspend (Long) -> Unit = { newEndTimestamp ->
                val updatedInterval = finalInterval.copy(end = newEndTimestamp)
                currentIntervals[0] = updatedInterval
                syncIntervalRepository.saveSyncedIntervals(bucketId, currentIntervals)
            }
            SyncStatusManager.increaseDiscoveredPhotosCount(photosInTail.size)

            syncAndSaveInBatches(
                currentCoroutineContext,
                photosInTail,
                onBatchSave
            )
        }
        return currentIntervals
    }

    private fun mergeTwoIntervals(interval1: TimeInterval, interval2: TimeInterval): TimeInterval {
        return TimeInterval(
            interval1.start,
            maxOf(interval1.end, interval2.end)
        )
    }


    private suspend fun syncAndSaveInBatches(
        context: CoroutineContext,
        photos: List<GalleryPhoto>,
        onBatchSave: suspend (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val batchSize = syncConfig.batchSize
        var lastSyncedTimestamp = 0L

        // Process photos in parallel batches
        photos.chunked(batchSize).forEach { batch ->
            val deferredResults = batch.map { photo ->
                async {
                    concurrentLimit.withPermit {
                        context.ensureActive()

                        try {
                            // 1. Read original file content directly into memory
                            val originalBytes =
                                contentResolver.openInputStream(photo.path)?.use { inputStream ->
                                    inputStream.readBytes()
                                } ?: throw IOException("Failed to read photo")

                            val fileContentToUpload: ByteArray
                            val fileNameToUpload: String
                            val clearFullPath = syncConfig.photoFolderPath + photo.displayName

                            if (syncConfig.isEncryptionEnabled && zeroKnowledgeProof != null) {
                                // 2. Encrypt the full transport path, not only the filename.
                                fileNameToUpload = zeroKnowledgeProof.EncryptFullFileName(clearFullPath)

                                // 3. Derive the content key from the clear full path
                                fileContentToUpload = zeroKnowledgeProof.encryptBytes(
                                    originalBytes,
                                    clearFullPath,
                                    photo.lastModifiedSeconds 
                                )
                            } else {
                                // Skip encryption
                                fileNameToUpload = clearFullPath
                                fileContentToUpload = originalBytes
                            }


                            // 4. Upload bytes with the chosen filename (encrypted or original)
                            val timeoutMs = UploadTimeouts.forSizeBytes(fileContentToUpload.size)
                            val uploadCompleted = withTimeoutOrNull(timeoutMs) {
                                suspendCancellableCoroutine { continuation ->
                                    val finished = AtomicBoolean(false)
                                    dataCenterCloudManager.uploadFileBytes(
                                        fileContentToUpload,
                                        fileNameToUpload,
                                        photo.lastModifiedSeconds
                                    ) { progress ->
                                        if (progress.hasError) {
                                            CoroutineScope(Dispatchers.Main).launch {
                                                PhotoSyncStatusManager.updatePhotoProgress(
                                                    filename = photo.displayName,
                                                    currentChunk = progress.currentChunk,
                                                    totalChunks = progress.totalChunks,
                                                    hasError = true,
                                                    errorMessage = progress.errorMessage ?: "Upload failed"
                                                )
                                                SyncStatusManager.incrementFailedSyncPhotosCount()
                                            }
                                            if (finished.compareAndSet(false, true) && continuation.isActive) {
                                                continuation.resume(Unit)
                                            }
                                        } else if (progress.isCompleted) {
                                            CoroutineScope(Dispatchers.Main).launch {
                                                PhotoSyncStatusManager.markPhotoCompleted(photo.displayName)
                                                SyncStatusManager.incrementSuccessfulSyncPhotosCount()
                                            }
                                            if (finished.compareAndSet(false, true) && continuation.isActive) {
                                                continuation.resume(Unit)
                                            }
                                        } else {
                                            CoroutineScope(Dispatchers.Main).launch {
                                                PhotoSyncStatusManager.updatePhotoProgress(
                                                    filename = photo.displayName, // Show original name in UI
                                                    currentChunk = progress.currentChunk,
                                                    totalChunks = progress.totalChunks
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (uploadCompleted == null) {
                                CoroutineScope(Dispatchers.Main).launch {
                                    PhotoSyncStatusManager.updatePhotoProgress(
                                        filename = photo.displayName,
                                        currentChunk = 0,
                                        totalChunks = 0,
                                        hasError = true,
                                        errorMessage = "Upload timed out waiting for completion callback"
                                    )
                                    SyncStatusManager.incrementFailedSyncPhotosCount()
                                }
                            }
                            photo.dateAdded // Return timestamp for batch tracking
                        } catch (e: Exception) {
                            Log.e("FullScanProcessManager", "Failed to sync ${photo.displayName}", e)
                            CoroutineScope(Dispatchers.Main).launch {
                                PhotoSyncStatusManager.updatePhotoProgress(
                                    filename = photo.displayName,
                                    currentChunk = 0,
                                    totalChunks = 0,
                                    hasError = true,
                                    errorMessage = e.message ?: "Upload failed"
                                )
                                SyncStatusManager.incrementFailedSyncPhotosCount()
                            }
                            photo.dateAdded
                        }
                    }
                }
            }

            // Wait for all photos in the batch to complete
            val timestamps = deferredResults.awaitAll()
            lastSyncedTimestamp = timestamps.maxOrNull() ?: lastSyncedTimestamp

            // Save batch progress
            withContext(Dispatchers.Main) {
                onBatchSave(lastSyncedTimestamp)
            }
        }
    }
}
