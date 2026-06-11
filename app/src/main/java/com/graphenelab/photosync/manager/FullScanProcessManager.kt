package com.graphenelab.photosync.manager

import android.content.ContentResolver
import android.util.Log
import com.graphenelab.communication.crypto.IZeroKnowledgeProof
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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

    private data class UploadOutcome(
        val success: Boolean,
        val errorMessage: String? = null
    )

    private data class PhotoSyncResult(
        val timestamp: Long,
        val success: Boolean
    )

    override suspend fun initializeIntervals(bucketId: String): MutableList<TimeInterval> {
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
        val progressIntervals = currentIntervals.toMutableList()
        val progressSaveMutex = Mutex()
        if (photosInGap.isNotEmpty()) {
            val onBatchSave: suspend (Long) -> Unit = { newEndTimestamp ->
                progressSaveMutex.withLock {
                    tempInterval1 = interval1.copy(end = newEndTimestamp)
                    // Save current progress immediately for crash recovery.
                    val updatedListForSave = currentIntervals.toMutableList()
                    updatedListForSave[0] = tempInterval1
                    progressIntervals.replaceWith(updatedListForSave)
                    syncIntervalRepository.saveSyncedIntervals(bucketId, updatedListForSave)
                }
            }
            val onPhotoSynced: suspend (Long) -> Unit = { syncedTimestamp ->
                saveSyncedPhotoTimestamp(
                    bucketId = bucketId,
                    progressIntervals = progressIntervals,
                    progressSaveMutex = progressSaveMutex,
                    timestamp = syncedTimestamp
                )
            }
            SyncStatusManager.increaseDiscoveredPhotosCount(photosInGap.size)
            syncAndSaveInBatches(
                currentCoroutineContext,
                photosInGap,
                onBatchSave,
                onPhotoSynced
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
            val progressSaveMutex = Mutex()
            val onBatchSave: suspend (Long) -> Unit = { newEndTimestamp ->
                progressSaveMutex.withLock {
                    val updatedInterval = finalInterval.copy(end = newEndTimestamp)
                    val updatedIntervals = currentIntervals.toMutableList()
                    updatedIntervals[0] = updatedInterval
                    currentIntervals.replaceWith(mergeIntervals(updatedIntervals))
                    syncIntervalRepository.saveSyncedIntervals(bucketId, currentIntervals)
                }
            }
            val onPhotoSynced: suspend (Long) -> Unit = { syncedTimestamp ->
                saveSyncedPhotoTimestamp(
                    bucketId = bucketId,
                    progressIntervals = currentIntervals,
                    progressSaveMutex = progressSaveMutex,
                    timestamp = syncedTimestamp
                )
            }
            SyncStatusManager.increaseDiscoveredPhotosCount(photosInTail.size)

            syncAndSaveInBatches(
                currentCoroutineContext,
                photosInTail,
                onBatchSave,
                onPhotoSynced
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
        onPhotoSynced: suspend (Long) -> Unit,
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
                                            if (finished.compareAndSet(false, true) && continuation.isActive) {
                                                continuation.resume(
                                                    UploadOutcome(
                                                        success = false,
                                                        errorMessage = progress.errorMessage ?: "Upload failed"
                                                    )
                                                )
                                            }
                                        } else if (progress.isCompleted) {
                                            if (finished.compareAndSet(false, true) && continuation.isActive) {
                                                continuation.resume(UploadOutcome(success = true))
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

                            if (uploadCompleted?.success == true) {
                                withContext(NonCancellable) {
                                    onPhotoSynced(photo.dateAdded)
                                    withContext(Dispatchers.Main) {
                                        PhotoSyncStatusManager.markPhotoCompleted(photo.displayName)
                                        SyncStatusManager.incrementSuccessfulSyncPhotosCount()
                                    }
                                }
                                PhotoSyncResult(photo.dateAdded, success = true)
                            } else {
                                withContext(Dispatchers.Main) {
                                    PhotoSyncStatusManager.updatePhotoProgress(
                                        filename = photo.displayName,
                                        currentChunk = 0,
                                        totalChunks = 0,
                                        hasError = true,
                                        errorMessage = uploadCompleted?.errorMessage
                                            ?: "Upload timed out waiting for completion callback"
                                    )
                                    SyncStatusManager.incrementFailedSyncPhotosCount()
                                }
                                PhotoSyncResult(photo.dateAdded, success = false)
                            }
                        } catch (e: CancellationException) {
                            throw e
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
                            PhotoSyncResult(photo.dateAdded, success = false)
                        }
                    }
                }
            }

            // Wait for all photos in the batch to complete
            val syncedTimestamps = deferredResults.awaitAll()
                .filter { it.success }
                .map { it.timestamp }

            // Save batch progress
            if (syncedTimestamps.isNotEmpty()) {
                lastSyncedTimestamp = syncedTimestamps.maxOrNull() ?: lastSyncedTimestamp
                onBatchSave(lastSyncedTimestamp)
            }
        }
    }

    private suspend fun saveSyncedPhotoTimestamp(
        bucketId: String,
        progressIntervals: MutableList<TimeInterval>,
        progressSaveMutex: Mutex,
        timestamp: Long
    ) {
        progressSaveMutex.withLock {
            val updatedIntervals = mergeIntervals(progressIntervals + TimeInterval(timestamp, timestamp))
            progressIntervals.replaceWith(updatedIntervals)
            syncIntervalRepository.saveSyncedIntervals(bucketId, progressIntervals)
        }
    }

    private fun mergeIntervals(intervals: List<TimeInterval>): MutableList<TimeInterval> {
        if (intervals.isEmpty()) return mutableListOf()

        val merged = mutableListOf<TimeInterval>()
        intervals.sortedBy { it.start }.forEach { interval ->
            val previous = merged.lastOrNull()
            if (previous == null || interval.start > previous.end + 1) {
                merged.add(interval)
            } else {
                merged[merged.lastIndex] = previous.copy(end = maxOf(previous.end, interval.end))
            }
        }
        return merged
    }

    private fun MutableList<TimeInterval>.replaceWith(intervals: List<TimeInterval>) {
        clear()
        addAll(intervals)
    }
}
