package com.graphenelab.photosync.manager

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.graphenelab.photosync.background.FullScanService
import com.graphenelab.photosync.background.PhotoSyncWorker
import com.graphenelab.photosync.domain.model.TimeInterval
import com.graphenelab.photosync.domain.repositroy.ISyncRepository
import com.graphenelab.photosync.manager.interfaces.IBackgroundSyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages background synchronization tasks, including periodic photo sync and full media scans.
 * It utilizes WorkManager for scheduling periodic tasks and directly starts/stops a foreground service for full scans.
 */
@Singleton
class BackgroundSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncRepository: ISyncRepository
) : IBackgroundSyncManager {

    private val workManager: WorkManager by lazy(LazyThreadSafetyMode.NONE) {
        WorkManager.getInstance(context)
    }
    private val periodicWorkTag = "periodic-photo-sync"

    override suspend fun schedulePeriodicSync() {
        if (syncRepository.syncFromNowPoint.first() == 0L) {
            val syncStartTime = System.currentTimeMillis() / 1000L
            val selectedFolders = syncRepository.selectedFolders.first()
            
            for (bucketId in selectedFolders) {
                val currentIntervals = syncRepository.getSyncedIntervals(bucketId).first()
                val newInterval = TimeInterval(start = syncStartTime, end = syncStartTime)
                syncRepository.saveSyncedIntervals(bucketId, currentIntervals + newInterval)
            }

            syncRepository.saveSyncFromNowPoint(syncStartTime)
        }

        val request = PeriodicWorkRequestBuilder<PhotoSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(periodicWorkTag)
            .build()

        workManager.enqueueUniquePeriodicWork("UniquePhotoSyncWork", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override suspend fun cancelPeriodicSync() {
        syncRepository.deleteSyncFromNowPoint()
        workManager.cancelAllWorkByTag(periodicWorkTag)
    }

    override fun startFullScanService() {
        val intent = Intent(context, FullScanService::class.java).apply {
            action = FullScanService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun stopFullScanService() {
        val intent = Intent(context, FullScanService::class.java).apply {
            action = FullScanService.ACTION_STOP
        }
        context.startService(intent)
    }

    override fun getPeriodicSyncWorkInfoFlow(): Flow<Boolean> {
        return workManager.getWorkInfosByTagFlow(periodicWorkTag)
            .map { it.any { info -> info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING } }
    }
}
