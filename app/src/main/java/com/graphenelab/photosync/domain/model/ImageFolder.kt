package com.graphenelab.photosync.domain.model

import android.net.Uri

data class ImageFolder(
    val bucketId: String,
    val displayName: String,
    val photoCount: Int,
    val coverUri: Uri?
)
