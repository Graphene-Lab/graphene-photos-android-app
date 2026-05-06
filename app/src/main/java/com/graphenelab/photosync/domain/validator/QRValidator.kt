package com.graphenelab.photosync.domain.validator

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QRValidator @Inject constructor() {
    companion object {
        // QR data requires at least 33 bytes (1 type + 24 key + 8 ID), which is 44 Base64 chars.
        private val BASE64_REGEX = "^[A-Za-z0-9+/]{44,}[=]{0,2}$".toRegex()
    }

    fun validate(text: String): QRValidationResult {
        val trimmed = text.trim()
        return when {
            trimmed.isBlank() -> QRValidationResult.Empty
            !BASE64_REGEX.matches(trimmed) -> QRValidationResult.InvalidFormat
            else -> QRValidationResult.Success
        }
    }
}
