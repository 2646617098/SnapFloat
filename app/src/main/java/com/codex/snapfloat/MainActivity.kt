package com.codex.snapfloat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.codex.snapfloat.databinding.ActivityMainBinding
import com.codex.snapfloat.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                CapturePermissionStore.update(result.resultCode, result.data!!)
                toast(getString(R.string.capture_permission_granted))
                renderStatus()
            } else {
                toast(getString(R.string.capture_permission_denied))
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast(getString(R.string.notification_permission_optional))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.buttonOverlayPermission.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        binding.buttonCapturePermission.setOnClickListener {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        binding.buttonStartOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast(getString(R.string.overlay_permission_missing))
                return@setOnClickListener
            }
            requestNotificationPermissionIfNeeded()
            val serviceIntent = OverlayService.createIntent(this).apply {
                CapturePermissionStore.resultCode?.let { putExtra(OverlayService.EXTRA_RESULT_CODE, it) }
                CapturePermissionStore.dataIntent?.let { putExtra(OverlayService.EXTRA_DATA_INTENT, Intent(it)) }
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            toast(getString(R.string.overlay_started))
        }

        binding.buttonStopOverlay.setOnClickListener {
            stopService(OverlayService.createIntent(this))
            toast(getString(R.string.overlay_stopped))
        }

        binding.buttonResetPermission.setOnClickListener {
            CapturePermissionStore.clear()
            renderStatus()
            toast(getString(R.string.capture_permission_reset))
        }

        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun renderStatus() {
        val overlayStatus = if (Settings.canDrawOverlays(this)) {
            getString(R.string.status_ready)
        } else {
            getString(R.string.status_missing)
        }

        val captureStatus = if (CapturePermissionStore.hasPermission()) {
            getString(R.string.status_ready)
        } else {
            getString(R.string.status_missing)
        }

        binding.textOverlayStatus.text = getString(R.string.overlay_status_value, overlayStatus)
        binding.textCaptureStatus.text = getString(R.string.capture_status_value, captureStatus)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
