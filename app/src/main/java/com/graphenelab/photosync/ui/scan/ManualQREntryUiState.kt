package com.graphenelab.photosync.ui.scan

data class ManualQREntryUiState(
    val qrText: String = "",
    val errorResId: Int? = null,
    val isSubmitted: Boolean = false
)
