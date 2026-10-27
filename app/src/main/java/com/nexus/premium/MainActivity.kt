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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
 * MainActivity V4 - Bảng điều khiển trung tâm.
 *
 * 🆕 V4:
 *  - Nút "CẤU HÌNH API KEY" → dialog nhập 3 key Groq (lưu vào SharedPreferences)
 *  - Không cần build lại APK khi đổi key
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

        // 🔑 Init ApiKeyStore trước khi dùng ApiClient
        ApiKeyStore.init(this)

        statusOverlay = findViewById(R.id.statusOverlay)
        statusAccess = findViewById(R.id.statusAccess)
        statusNotif = findViewById(R.id.statusNotif)
        statusBattery = findViewById(R.id.statusBattery)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        // 🔑 Nút cấu hình API
        findViewById<Button>(R.id.btnApiKey).setOnClickListener {
            showApiKeyDialog()
        }

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

        // Cảnh báo lần đầu nếu chưa nhập key
        if (!ApiKeyStore.hasAnyKey()) {
            btnStart.postDelayed({
                Toast.makeText(
                    this,
                    "⚠️ Chưa có API key! Bấm 'CẤU HÌNH API KEY' trước.",
                    Toast.LENGTH_LONG
                ).show()
            }, 800)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatuses()
        scope.launch {
            repeat(20) { delay(1000); updateStatuses() }
        }
    }

    /** 🔑 Dialog nhập 3 API key Groq. */
    private fun showApiKeyDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_api_keys, null)
        val et1 = view.findViewById<EditText>(R.id.etKey1)
        val et2 = view.findViewById<EditText>(R.id.etKey2)
        val et3 = view.findViewById<EditText>(R.id.etKey3)

        // Hiển thị key hiện tại (nếu đã lưu)
        val (k1, k2, k3) = ApiKeyStore.getRawKeys()
        et1.setText(k1)
        et2.setText(k2)
        et3.setText(k3)

        AlertDialog.Builder(this)
            .setTitle("🔑 CẤU HÌNH API KEY")
            .setView(view)
            .setPositiveButton("LƯU") { _, _ ->
                val input1 = et1.text.toString().trim()
                val input2 = et2.text.toString().trim()
                val input3 = et3.text.toString().trim()

                // Validate sơ bộ: nếu có nhập thì phải bắt đầu bằng gsk_
                val invalid = listOf(input1, input2, input3).any {
                    it.isNotEmpty() && !it.startsWith("gsk_")
                }
                if (invalid) {
                    Toast.makeText(
                        this,
                        "❌ Key phải bắt đầu bằng 'gsk_'",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }

                ApiKeyStore.saveKeys(input1, input2, input3)
                val count = ApiKeyStore.getUserKeys().size
                Toast.makeText(
                    this,
                    if (count > 0) "✅ Đã lưu $count key!" else "⚠️ Chưa có key nào!",
                    Toast.LENGTH_SHORT
                ).show()
                NexusEvents.log("🔑 Đã lưu $count key từ dialog")
            }
            .setNeutralButton("LẤY KEY") { _, _ ->
                startActivity(Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://console.groq.com/keys")
                ))
            }
            .setNegativeButton("HỦY", null)
            .show()
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
        // Chặn nếu chưa có key
        if (!ApiKeyStore.hasAnyKey() && ApiClient.hasNoFallbackKey()) {
            Toast.makeText(
                this,
                "⚠️ Chưa có API key! Bấm 'CẤU HÌNH API KEY' trước.",
                Toast.LENGTH_LONG
            ).show()
            showApiKeyDialog()
            return
        }

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
