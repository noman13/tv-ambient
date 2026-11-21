package io.github.noman13.tvambient.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import io.github.noman13.tvambient.service.AmbientService

/**
 * Asks Android for screen-recording consent (the system dialog) and hands the answer to the service, which starts
 * capturing as a media-projection foreground service. Invisible apart from the system dialog. Android asks every time
 * the lights start with screen recording, so this runs from Home when the service says it needs it.
 */
class ProjectionRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) { finish(); return }
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST)
    }

    @Deprecated("startActivityForResult on API 26-32")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST) AmbientService.projectionResult(this, resultCode, data)
        finish()
    }

    private companion object {
        const val REQUEST = 1
    }
}
