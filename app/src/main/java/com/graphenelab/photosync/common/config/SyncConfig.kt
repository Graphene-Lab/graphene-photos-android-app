package com.graphenelab.photosync.common.config

data class SyncConfig(
    val batchSize: Int = 5,
    val isEncryptionEnabled: Boolean = true,
    val photoFolderPath: String = "Photos/"
)