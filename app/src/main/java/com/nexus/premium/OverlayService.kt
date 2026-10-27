package com.nexus.premium

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * OverlayService - HUD cửa sổ nổi "kính mờ".
 */
class OverlayService : Service() {

    companion object {
        @Volatile
        var isRunning = false
            private set

        private const val CHANNEL_ID = "nexus_overlay_channel"
        private const val NOTIF_ID = 1001
        private const val DRAG_THRESHOLD = 10f
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

        statusText = view.findViewById(R.id.statusText)
        answerText = view.findViewById(R.id.answerText)
        questionPreview = view.findViewById(R.id.questionPreview)
        autoNextSwitch = view.findViewById(R.id.autoNextSwitch)
        dragHandle = view.findViewById(R.id.dragHandle)
        btnSolve = view.findViewById(R.id.btnSolve)
        fabClose = view.findViewById(R.id.fabClose)
        btnCollapse = view.findViewById(R.id.btnCollapse)

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
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
                }
                return true
            }
        })

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

        windowManager.addView(rootView, params)
        NexusEvents.log("🪟 HUD hiển thị")
    }

    private fun toggleCollapse() {
        val collapsed = answerText.visibility == View.GONE
        val vis = if (collapsed) View.VISIBLE else View.GONE
        answerText.visibility = vis
        questionPreview.visibility = vis
        autoNextSwitch.visibility = vis
        btnSolve.visibility = vis
        btnCollapse.text = if (collapsed) "Thu nhỏ" else "Mở rộng"
    }

    private fun observeEvents() {
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
        scope.launch {
            NexusEvents.status.collect { statusText.text = it }
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
