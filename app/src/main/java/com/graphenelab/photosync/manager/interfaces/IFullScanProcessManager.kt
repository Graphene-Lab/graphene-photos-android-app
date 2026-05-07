package com.graphenelab.photosync.manager.interfaces

import com.graphenelab.photosync.domain.model.TimeInterval
import kotlin.coroutines.CoroutineContext

/**
 * Defines the contract for managing a full gallery scan and synchronization process.
 * This manager orchestrates the identification, synchronization, and tracking of photo
 * synchronization progress across various time intervals.
 */
interface IFullScanProcessManager {

    /**
     * Initializes and retrieves the current list of synchronized time intervals for a folder.
     * @param bucketId: The ID of the folder.
     */
    suspend fun initializeIntervals(bucketId: String): MutableList<TimeInterval>

    /**
     * Processes the gap between the first two intervals for a specific folder.
     * @param bucketId: The ID of the folder.
     * @param currentIntervals: The current list of synced time intervals.
     * @param currentCoroutineContext: The coroutine context for cancellation awareness.
     */
    suspend fun processNextTwoIntervals(
        bucketId: String,
        currentIntervals: MutableList<TimeInterval>,
        currentCoroutineContext: CoroutineContext
    ): MutableList<TimeInterval>

    /**
     * Processes any photos beyond the last synced interval for a specific folder.
     * @param bucketId: The ID of the folder.
     * @param currentIntervals: The current list of synced time intervals.
     * @param currentCoroutineContext: The coroutine context for cancellation awareness.
     */
    suspend fun processTailEnd(
        bucketId: String,
        currentIntervals: MutableList<TimeInterval>,
        currentCoroutineContext: CoroutineContext
    ): MutableList<TimeInterval>

}