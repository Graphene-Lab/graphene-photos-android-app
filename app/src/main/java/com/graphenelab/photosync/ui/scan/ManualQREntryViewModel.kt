package com.graphenelab.photosync.ui.scan

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.graphenelab.photosync.domain.validator.QRValidationResult
import com.graphenelab.photosync.domain.validator.QRValidator
import com.graphenelab.photosync.R
import javax.inject.Inject

@HiltViewModel
class ManualQREntryViewModel @Inject constructor(
    private val qrValidator: QRValidator
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManualQREntryUiState())
    val uiState: StateFlow<ManualQREntryUiState> = _uiState.asStateFlow()

    fun onQrTextChanged(text: String) {
        _uiState.update { it.copy(qrText = text, errorResId = null) }
    }

    fun onSubmit() {
        val result = qrValidator.validate(_uiState.value.qrText)

        when (result) {
            QRValidationResult.Empty -> {
                _uiState.update { it.copy(errorResId = R.string.manual_qr_error_empty) }
            }
            QRValidationResult.InvalidFormat -> {
                _uiState.update { it.copy(errorResId = R.string.manual_qr_error_invalid) }
            }
            QRValidationResult.Success -> {
                val text = _uiState.value.qrText.trim()
                _uiState.update { it.copy(qrText = text, isSubmitted = true) }
            }
        }
    }

    /**
     * Resets the submission state after it has been handled by the UI.
     * 
     * This is crucial for "Single-Event" side effects like navigation. Without consuming
     * the event, the 'isSubmitted' flag would remain true in the state. If the user later 
     * navigates back to this screen, the UI would see the "true" value again and 
     * immediately trigger a forward navigation loop.
     */
    fun onSubmissionConsumed() {
        _uiState.update { it.copy(isSubmitted = false) }
    }
}
