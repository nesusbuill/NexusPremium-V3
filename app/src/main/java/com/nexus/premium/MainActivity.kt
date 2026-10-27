package com.nexus.premium

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MainActivity - Bảng điều khiển trung tâm.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusOverlay: TextView
    private lateinit var statusAccess: TextView
    private lateinit var statusNotif: TextView
    private lateinit var statusBattery: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateStatuses() }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusOverlay = findViewById(R.id.statusOverlay)
        statusAccess = findViewById(R.id.statusAccess)
        statusNotif = findViewById(R.id.statusNotif)
        statusBattery = findViewById(R.id.statusBattery)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnGrantAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnGrantBattery).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
        findViewById<Button>(R.id.btnGrantNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        btnStart.setOnClickListener { startHud() }
        btnStop.setOnClickListener { stopHud() }

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        updateStatuses()
        scope.launch {
            repeat(20) { delay(1000); updateStatuses() }
        }
    }

    private fun updateStatuses() {
        statusOverlay.text = if (Settings.canDrawOverlays(this)) "✅" else "❌"
        statusAccess.text = if (isAccessibilityEnabled()) "✅" else "❌"

        statusNotif.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) "✅" else "❌"
        } else "✅"

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        statusBattery.text = if (pm.isIgnoringBatteryOptimizations(packageName)) "✅" else "⚠️"

        btnStart.isEnabled = Settings.canDrawOverlays(this) && isAccessibilityEnabled()
        btnStop.isEnabled = OverlayService.isRunning
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startHud() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        btnStart.postDelayed({ updateStatuses() }, 500)
    }

    private fun stopHud() {
        stopService(Intent(this, OverlayService::class.java))
        btnStart.postDelayed({ updateStatuses() }, 300)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
