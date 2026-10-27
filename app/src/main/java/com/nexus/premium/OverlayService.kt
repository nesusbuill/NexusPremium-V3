package com.nexus.premium

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * OverlayService V4 - HUD kính mờ + Clamp + 2 panel mới.
 *
 * 🆕 V4:
 *  - Clamp vị trí HUD trong màn hình (kể cả khi xoay)
 *  - Scan preview panel (🔍 quét tầng sâu)
 *  - Answer detail panel (📋 xem đáp án chi tiết)
 */
class OverlayService : Service() {

    companion object {
        @Volatile
        var isRunning = false
            private set

        private const val CHANNEL_ID = "nexus_overlay_channel"
        private const val NOTIF_ID = 1001
        private const val DRAG_THRESHOLD = 10f
        private const val MIN_VISIBLE_PX = 60  // Luôn giữ ít nhất 60px HUD trong màn hình
    }

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private lateinit var statusText: TextView
    private lateinit var answerText: TextView
    private lateinit var questionPreview: TextView
    private lateinit var autoNextSwitch: SwitchCompat
    private lateinit var dragHandle: View
    private lateinit var btnSolve: View
    private lateinit var fabClose: ImageButton
    private lateinit var btnCollapse: TextView

    // 🆕 Refs 2 panel mới
    private lateinit var scanPreviewPanel: View
    private lateinit var scanQuestionText: TextView
    private lateinit var scanOptionsText: TextView
    private lateinit var scanFilteredText: TextView

    private lateinit var answerDetailPanel: View
    private lateinit var answerDetailText: TextView

    private lateinit var btnToggleScan: TextView
    private lateinit var btnToggleDetail: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlay()
        observeEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, "Nexus Overlay", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Duy trì HUD Nexus Premium"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }

        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nexus)
            .setContentTitle("Nexus Premium đang chạy")
            .setContentText("HUD đang hiển thị")
            .setOngoing(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun setupOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        rootView = view

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 220
        }

        // Bind views
        statusText = view.findViewById(R.id.statusText)
        answerText = view.findViewById(R.id.answerText)
        questionPreview = view.findViewById(R.id.questionPreview)
        autoNextSwitch = view.findViewById(R.id.autoNextSwitch)
        dragHandle = view.findViewById(R.id.dragHandle)
        btnSolve = view.findViewById(R.id.btnSolve)
        fabClose = view.findViewById(R.id.fabClose)
        btnCollapse = view.findViewById(R.id.btnCollapse)

        // 🆕 Bind 2 panel mới
        scanPreviewPanel = view.findViewById(R.id.scanPreviewPanel)
        scanQuestionText = view.findViewById(R.id.scanQuestionText)
        scanOptionsText = view.findViewById(R.id.scanOptionsText)
        scanFilteredText = view.findViewById(R.id.scanFilteredText)
        answerDetailPanel = view.findViewById(R.id.answerDetailPanel)
        answerDetailText = view.findViewById(R.id.answerDetailText)
        btnToggleScan = view.findViewById(R.id.btnToggleScan)
        btnToggleDetail = view.findViewById(R.id.btnToggleDetail)

        // Drag
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var dragging = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x; initY = params.y
                        touchX = e.rawX; touchY = e.rawY
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - touchX
                        val dy = e.rawY - touchY
                        if (!dragging && (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD)) {
                            dragging = true
                        }
                        if (dragging) {
                            params.x = (initX + dx).toInt()
                            params.y = (initY + dy).toInt()
                            try { windowManager.updateViewLayout(rootView, params) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        clampHudToScreen()  // 🆕 Kéo xong → clamp
                    }
                }
                return true
            }
        })

        // Toggle panels
        btnToggleScan.setOnClickListener { togglePanel(scanPreviewPanel, btnToggleScan) }
        btnToggleDetail.setOnClickListener { togglePanel(answerDetailPanel, btnToggleDetail) }

        autoNextSwitch.isChecked = true
        autoNextSwitch.setOnCheckedChangeListener { _, checked ->
            NexusAccessibilityService.instance?.autoNextEnabled = checked
            NexusEvents.log(if (checked) "🔁 Auto-Next: BẬT" else "⏸ Auto-Next: TẮT")
        }

        btnSolve.setOnClickListener {
            NexusEvents.log("🖐️ Giải thủ công")
            NexusAccessibilityService.instance?.triggerScan()
                ?: NexusEvents.log("⚠️ Chưa bật Trợ năng")
        }

        fabClose.setOnClickListener {
            NexusEvents.log("❌ Đóng HUD")
            stopSelf()
        }

        btnCollapse.setOnClickListener { toggleCollapse() }

        // 🆕 Clamp trước khi addView để chắc chắn hiện đúng trong màn hình
        clampHudToScreenInitial()

        windowManager.addView(rootView, params)

        // 🆕 Clamp lại sau khi measure (view.width/height đã có giá trị)
        rootView?.post { clampHudToScreen() }

        NexusEvents.log("🪟 HUD hiển thị")
    }

    private fun togglePanel(panel: View, btn: TextView) {
        val visible = panel.visibility == View.VISIBLE
        panel.visibility = if (visible) View.GONE else View.VISIBLE
        btn.alpha = if (visible) 0.5f else 1f
        rootView?.post { clampHudToScreen() }
    }

    /**
     * 🆕 Clamp vị trí HUD vào trong màn hình.
     * Đảm bảo ít nhất MIN_VISIBLE_PX của HUD luôn hiển thị trên mỗi cạnh.
     */
    private fun clampHudToScreen() {
        val view = rootView ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val hudW = view.width.takeIf { it > 0 } ?: 300
        val hudH = view.height.takeIf { it > 0 } ?: 400

        // Giới hạn X: không cho HUD chui hoàn toàn ra trái/phải
        params.x = params.x.coerceIn(-hudW + MIN_VISIBLE_PX, screenW - MIN_VISIBLE_PX)
        // Giới hạn Y: không cho HUD chui lên trên hoặc xuống dưới màn hình
        params.y = params.y.coerceIn(0, screenH - MIN_VISIBLE_PX)

        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    /** Clamp sớm trước khi view được đo (dùng giá trị mặc định). */
    private fun clampHudToScreenInitial() {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        params.x = params.x.coerceIn(0, screenW - MIN_VISIBLE_PX)
        params.y = params.y.coerceIn(0, screenH - MIN_VISIBLE_PX)
    }

    /** 🆕 Khi xoay màn hình → clamp lại để HUD không bị đẩy ra ngoài. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rootView?.post { clampHudToScreen() }
    }

    private fun toggleCollapse() {
        val collapsed = answerText.visibility == View.GONE
        val vis = if (collapsed) View.VISIBLE else View.GONE
        answerText.visibility = vis
        questionPreview.visibility = vis
        autoNextSwitch.visibility = vis
        btnSolve.visibility = vis
        if (!collapsed) {
            scanPreviewPanel.visibility = View.GONE
            btnToggleScan.alpha = 0.5f
            answerDetailPanel.visibility = View.GONE
            btnToggleDetail.alpha = 0.5f
        }
        btnCollapse.text = if (collapsed) "Thu nhỏ" else "Mở rộng"
        rootView?.post { clampHudToScreen() }
    }

    private fun observeEvents() {
        // Đáp án lớn
        scope.launch {
            NexusEvents.answers.collect { ev ->
                answerText.text = ev.answer
                questionPreview.text = ev.question.take(120)
                answerText.setTextColor(
                    when (ev.answer) {
                        "A", "B", "C", "D" -> 0xFF7CFFB2.toInt()
                        else -> 0xFFFF6B6B.toInt()
                    }
                )
            }
        }
        // Status
        scope.launch {
            NexusEvents.status.collect { statusText.text = it }
        }
        // 🆕 Scan preview
        scope.launch {
            NexusEvents.scanPreviews.collect { ev ->
                scanQuestionText.text = ev.question.take(200)
                scanOptionsText.text = ev.options.entries.joinToString("\n") { (k, v) ->
                    "$k. ${v.take(80)}"
                }
                scanFilteredText.text = "Đã lọc: ${ev.filteredCount} rác"
            }
        }
        // 🆕 Answer detail
        scope.launch {
            NexusEvents.answerDetails.collect { ev ->
                val detail = buildString {
                    appendLine("❓ ${ev.question.take(250)}")
                    appendLine()
                    ev.options.forEach { (k, v) ->
                        val marker = if (k == ev.answer) " ✅" else ""
                        appendLine("$k. ${v.take(80)}$marker")
                    }
                }
                answerDetailText.text = detail
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        try { rootView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        rootView = null
        NexusEvents.log("🛑 HUD đóng")
    }
}
