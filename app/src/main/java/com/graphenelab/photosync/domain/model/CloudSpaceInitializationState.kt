package com.graphenelab.photosync.domain.model

import java.time.Instant

enum class CloudSpaceInitializationStatus {
    PENDING,
    IN_PROGRESS,
    RETRY_SCHEDULED,
    FAILED,
    READY
}

data class CloudSpaceInitializationState(
    val status: CloudSpaceInitializationStatus,
    val attempts: Int,
    val nextRetryAt: Instant?,
    val errorCode: String?,
    val retryable: Boolean
) {
    // The backend's retryable flag describes automatic recovery, not manual retry permission.
    val manualRetryAllowed: Boolean
        get() = status == CloudSpaceInitializationStatus.FAILED &&
            errorCode == "CLOUD_SPACE_RETRY_LIMIT_REACHED"
}
