package com.graphenelab.photosync.domain.repositroy

import com.graphenelab.photosync.data.network.payment.SubscriptionPlan
import com.graphenelab.photosync.domain.model.CloudSpaceCredentials
import com.graphenelab.photosync.domain.model.CloudSpaceInitializationState

interface ICloudSpaceRepository {
    suspend fun getCloudSpaceInitializationState(): Result<CloudSpaceInitializationState>
    suspend fun initializeCloudSpace(): Result<CloudSpaceInitializationState>
    suspend fun getCloudSpaceCredentials(): Result<CloudSpaceCredentials>
    suspend fun getCurrentSubscriptionPlan(): Result<SubscriptionPlan>
    suspend fun deleteCurrentUserAccount(): Result<DeleteAccountResult>
}

enum class DeleteAccountResult {
    Deleted,
    UserNotFound
}
