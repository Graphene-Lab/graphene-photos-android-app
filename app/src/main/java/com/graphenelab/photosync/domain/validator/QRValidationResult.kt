package com.graphenelab.photosync.domain.validator

sealed class QRValidationResult {
    data object Success : QRValidationResult()
    data object Empty : QRValidationResult()
    data object InvalidFormat : QRValidationResult()
}
