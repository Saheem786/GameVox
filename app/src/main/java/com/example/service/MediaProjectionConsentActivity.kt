package com.example.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.data.TranslatorStateRepository

/**
 * Lightweight transparent Activity launched only when the user taps the floating button
 * to start dubbing without an active MediaProjection session, ensuring fresh per-session
 * screen capture consent via Android's MediaProjection dialog.
 */
class MediaProjectionConsentActivity : ComponentActivity() {

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenTranslatorService::class.java).apply {
                action = ScreenTranslatorService.ACTION_START_WITH_PROJECTION
                putExtra(ScreenTranslatorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenTranslatorService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            TranslatorStateRepository.updateCaptureTelemetry(
                hasActiveProjection = false,
                statusMessage = "Screen capture permission denied — tap Start to grant access"
            )
            TranslatorStateRepository.setCapturingOcr(false)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mgr == null) {
            TranslatorStateRepository.updateCaptureTelemetry(
                hasActiveProjection = false,
                statusMessage = "MediaProjection service unavailable on this device"
            )
            finish()
            return
        }
        captureLauncher.launch(mgr.createScreenCaptureIntent())
    }
}
