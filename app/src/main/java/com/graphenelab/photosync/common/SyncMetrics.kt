package com.graphenelab.photosync.common

/**
 * Represents synchronization metrics for a specific scope (e.g., a single folder or an entire session).
 */
data class SyncMetrics(
    val successful: Int = 0,
    val failed: Int = 0,
    val discovered: Int = 0,
    val noPhotosFoundToSync: Boolean = false
) {
    /**
     * Total number of photos that have been attempted (either succeeded or failed).
     */
    val processed: Int get() = successful + failed
}
