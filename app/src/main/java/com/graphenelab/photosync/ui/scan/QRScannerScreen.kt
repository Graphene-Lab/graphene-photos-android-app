package com.graphenelab.photosync.ui.scan

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.graphenelab.photosync.R

// ScanScreen.kt
@Composable
fun ScanScreen(
    modifier: Modifier = Modifier,
    onNavigateToResult: (String?) -> Unit,
    onNavigateToManualEntry: () -> Unit,
    viewModel: ScanViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Launchers setup
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        viewModel.handleScanResult(result.contents)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.handlePermissionResult(permissions)
    }

    // One-time launcher setup
    LaunchedEffect(Unit) {
        viewModel.setScanLauncher(scanLauncher)
        viewModel.setPermissionLauncher(permissionLauncher)
    }

    // Handle navigation when scanned
    LaunchedEffect(uiState) {
        if (uiState is ScanUiState.Scanned) {
            onNavigateToResult((uiState as ScanUiState.Scanned).content)
        }
    }

    ScanContent(
        modifier = modifier,
        uiState = uiState,
        onScanClicked = { viewModel.onScanButtonClicked(context) },
        onManualEntryClicked = onNavigateToManualEntry
    )
}

@Composable
private fun ScanContent(
    modifier: Modifier,
    uiState: ScanUiState,
    onScanClicked: () -> Unit,
    onManualEntryClicked: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.scan_connect_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.scan_step1),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.scan_step2),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.scan_step3),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Spacer(modifier = Modifier.height(20.dp))
            val sampleShape = RoundedCornerShape(10.dp)
            Image(
                painter = painterResource(id = R.drawable.windows_client_qr_sample),
                contentDescription = stringResource(R.string.scan_qr_sample_cd),
                contentScale = ContentScale.FillBounds, // This forces the image to hit the corners
                modifier = Modifier
                    .size(280.dp)
                    .clip(sampleShape) // Now it has something to clip!
            )
            Spacer(modifier = Modifier.height(20.dp))
            ScanButton(onClick = onScanClicked)
            Spacer(modifier = Modifier.height(16.dp))
            ManualEntryButton(onClick = onManualEntryClicked)

            when (val state = uiState) {
                is ScanUiState.PermissionDenied -> PermissionDeniedMessage()
                is ScanUiState.Scanned -> ScannedContent(content = state.content)
                ScanUiState.Idle, ScanUiState.PermissionGranted -> {} // No UI for these states
            }
        }
    }
}

@Composable
private fun ScanButton(onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(stringResource(R.string.scan_button))
    }
}

@Composable
private fun ManualEntryButton(onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(stringResource(R.string.scan_enter_manually))
    }
}

@Composable
private fun PermissionDeniedMessage() {
    Text(
        text = stringResource(R.string.scan_permission_denied),
        modifier = Modifier.padding(top = 16.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ScannedContent(content: String?) {
    Text(
        text = stringResource(R.string.scan_result_prefix, content ?: stringResource(R.string.scan_result_nothing)),
        modifier = Modifier.padding(top = 16.dp),
        textAlign = TextAlign.Center
    )
}
