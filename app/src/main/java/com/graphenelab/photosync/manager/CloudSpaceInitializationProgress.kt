package com.graphenelab.photosync.manager

import com.graphenelab.photosync.domain.model.CloudSpaceCredentials
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationState

sealed interface CloudSpaceInitializationProgress {
    data object FetchingCredentials : CloudSpaceInitializationProgress
    data class Preparing(val state: CloudSpaceInitializationState) : CloudSpaceInitializationProgress
    data class RetryScheduled(val state: CloudSpaceInitializationState) : CloudSpaceInitializationProgress
    data class RetryLimitReached(val state: CloudSpaceInitializationState) : CloudSpaceInitializationProgress
    data class SupportRequired(val state: CloudSpaceInitializationState) : CloudSpaceInitializationProgress
    data class Ready(val credentials: CloudSpaceCredentials) : CloudSpaceInitializationProgress
    data object SessionExpired : CloudSpaceInitializationProgress
    data class RequestFailed(val exception: Exception) : CloudSpaceInitializationProgress
}
