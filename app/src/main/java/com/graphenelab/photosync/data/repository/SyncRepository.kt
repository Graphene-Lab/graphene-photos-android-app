package com.graphenelab.photosync.data.repository

import com.graphenelab.photosync.data.local.datastore.SyncPreferencesDataSource
import com.graphenelab.photosync.domain.model.TimeInterval
import com.graphenelab.photosync.domain.repositroy.ISyncRepository
import javax.inject.Inject

class SyncRepositoryImpl @Inject constructor(
    private val prefs: SyncPreferencesDataSource
) : ISyncRepository {

    override fun getSyncedIntervals(bucketId: String) = prefs.getSyncedIntervals(bucketId)

    // TODO: intervals also need to be stored in cloud(e.g. in file named deviceId_intervals in json format) in case of app reinstallation. If user syncs from another device then we need to detect that,
    //  since now we synchronize from 2 devices... each of them has its own sync intervals we need to consider that, and store 2 intervals in cloud (with device ID).
    //  Maybe we store like this in cloud: deviceId1/photos, deviceId2/photos... and in explorer we show both of them. Status: critical.
    override suspend fun saveSyncedIntervals(bucketId: String, intervals: List<TimeInterval>) {
        prefs.saveSyncedIntervals(bucketId, intervals)
    }

    override suspend fun clearSyncedIntervals(bucketIds: Set<String>) {
        prefs.clearSyncedIntervals(bucketIds)
    }

    override val syncFromNowPoint = prefs.syncFromNowPoint

    override suspend fun saveSyncFromNowPoint(timestamp: Long) {
        prefs.saveSyncFromNowPoint(timestamp)
    }

    override suspend fun deleteSyncFromNowPoint() {
        prefs.deleteSyncFromNowPoint()
    }

    override val selectedFolders = prefs.selectedFolders
    override val hasInitializedFolders = prefs.hasInitializedFolders

    override suspend fun saveSelectedFolders(folders: Set<String>) {
        prefs.saveSelectedFolders(folders)
    }

    override suspend fun clearAllData() {
        prefs.clearAllData()
    }

    override suspend fun clearSyncData() {
        prefs.clearSyncData()
    }
}
