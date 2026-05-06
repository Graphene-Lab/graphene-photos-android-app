package com.graphenelab.photosync.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.graphenelab.photosync.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualQREntryScreen(
    onNavigateBack: () -> Unit,
    onQrEntered: (String) -> Unit,
    viewModel: ManualQREntryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Navigate when submission is confirmed by the ViewModel, then consume the event
    LaunchedEffect(uiState.isSubmitted) {
        if (uiState.isSubmitted) {
            onQrEntered(uiState.qrText)
            viewModel.onSubmissionConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manual_qr_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.manual_qr_back_cd)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            ManualQREntryContent(
                uiState = uiState,
                onQrTextChanged = { viewModel.onQrTextChanged(it) },
                onSubmit = { viewModel.onSubmit() }
            )
        }
    }
}

@Composable
private fun ManualQREntryContent(
    uiState: ManualQREntryUiState,
    onQrTextChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.manual_qr_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = uiState.qrText,
            onValueChange = onQrTextChanged,
            label = { Text(stringResource(R.string.manual_qr_label)) },
            placeholder = { Text(stringResource(R.string.manual_qr_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 5,
            isError = uiState.errorResId != null,
            supportingText = if (uiState.errorResId != null) {
                { Text(text = stringResource(uiState.errorResId), color = MaterialTheme.colorScheme.error) }
            } else {
                null
            }
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onSubmit,
            enabled = uiState.qrText.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(text = stringResource(R.string.manual_qr_button), fontSize = 16.sp)
        }
    }
}
