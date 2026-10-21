package com.graphenelab.photosync.data.network.user

import com.google.gson.annotations.SerializedName

data class CloudSpaceInitializationResponse(
    val status: String? = null,
    val attempts: Int? = null,
    val nextRetryAt: String? = null,
    val errorCode: String? = null,
    val retryable: Boolean? = null
)

internal data class CloudSpaceInitializationErrorResponse(
    @SerializedName("setup")
    val state: CloudSpaceInitializationResponse? = null,
    val errorCode: String? = null
)
