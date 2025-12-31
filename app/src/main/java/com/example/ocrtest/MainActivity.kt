package com.example.ocrtest

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ocrtest.ui.theme.OCRTestTheme

class MainActivity : ComponentActivity() {
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private var pendingProjectionRequest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startOverlayService(result.resultCode, data)
            } else {
                Log.w(TAG, "Screen capture permission denied.")
            }
        }
        setContent {
            OCRTestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainContent(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                        onStartClick = { ensurePermissionsAndStart() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the overlay permission screen triggers the capture flow if approved.
        if (pendingProjectionRequest && Settings.canDrawOverlays(this)) {
            pendingProjectionRequest = false
            requestScreenCapture()
        }
    }

    private fun ensurePermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            pendingProjectionRequest = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = manager.createScreenCaptureIntent()
        projectionLauncher.launch(intent)
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        // Pass the MediaProjection permission token to the foreground service.
        val intent = Intent(this, OverlayOcrService::class.java).apply {
            putExtra(OverlayOcrService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayOcrService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(intent)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
fun MainContent(modifier: Modifier = Modifier, onStartClick: () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Enable the floating OCR button",
            style = MaterialTheme.typography.titleMedium
        )
        Button(
            onClick = onStartClick,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("Start overlay OCR")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainContentPreview() {
    OCRTestTheme {
        MainContent(onStartClick = {})
    }
}
