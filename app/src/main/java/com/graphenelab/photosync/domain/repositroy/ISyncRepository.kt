package com.graphenelab.photosync.domain.repositroy

import com.graphenelab.photosync.domain.model.TimeInterval
import kotlinx.coroutines.flow.Flow

/**
 * Interface defining the contract for managing synchronization data.
 * This includes storing and retrieving information about synced time intervals
 * and the starting point for future "sync from now" operations.
 */
interface ISyncRepository {
    fun getSyncedIntervals(bucketId: String): Flow<List<TimeInterval>>

    /**
     * Saves a list of time intervals that have been successfully synced for a specific folder.
     * @param bucketId: The ID of the folder.
     * @param intervals: The list of time intervals to be saved.
     */
    suspend fun saveSyncedIntervals(bucketId: String, intervals: List<TimeInterval>)

    /**
     * Retrieves the starting point for future "sync from now" operations.
     */
    val syncFromNowPoint: Flow<Long>

    /**
     * Saves the timestamp indicating the point from which to start syncing.
     */
    suspend fun saveSyncFromNowPoint(timestamp: Long)

    /**
     * Deletes the saved "sync from now" timestamp.
     */
    suspend fun deleteSyncFromNowPoint()
    
    val selectedFolders: Flow<Set<String>>
    val hasInitializedFolders: Flow<Boolean>
    suspend fun saveSelectedFolders(folders: Set<String>)

    suspend fun clearAllData()

    suspend fun clearSyncData()
}