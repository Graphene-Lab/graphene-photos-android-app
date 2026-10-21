package com.graphenelab.photosync.data.network.user

import com.graphenelab.photosync.data.network.payment.SubscriptionPlan
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST

interface CloudSpaceService {
    @GET("me/cloud-space/status")
    suspend fun getCloudSpaceInitializationState(): Response<CloudSpaceInitializationResponse>

    @POST("me/cloud-space/init")
    suspend fun initializeCloudSpace(): Response<CloudSpaceInitializationResponse>

    @GET("me/cloud-space/credentials")
    suspend fun getCloudSpaceCredentials(): CloudSpaceCredentialsResponse
    
    @GET("me/subscription-plan")
    suspend fun getCurrentSubscriptionPlan(): SubscriptionPlan

    @DELETE("user/me")
    suspend fun deleteCurrentUserAccount(): Response<Unit>
}
