package com.nexus.premium

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * NexusAccessibilityService - Trái tim của Nexus Premium.
 */
class NexusAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: NexusAccessibilityService? = null
            private set

        private const val DEBOUNCE_MS = 600L
        private const val POST_CLICK_DELAY_MS = 900L

        private val ANSWER_REGEX = Regex(
            """^\s*[\(\[]?([A-Da-d])[\)\]]?\s*[.):\-–—]\s*(.+)$"""
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var pendingJob: Job? = null
    private var lastQuestionHash: Int = 0

    var autoNextEnabled: Boolean = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        NexusEvents.log("✅ Accessibility kết nối")
        NexusEvents.emitStatus("Sẵn sàng")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleScan()
        }
    }

    private fun scheduleScan() {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            try { scanAndSolve() }
            catch (e: Exception) { NexusEvents.log("⚠️ scan: ${e.message}") }
        }
    }

    fun triggerScan() = scheduleScan()

    private suspend fun scanAndSolve() {
        val root = rootInActiveWindow ?: return
        val extracted = extractQuiz(root) ?: return

        val hash = extracted.question.hashCode()
        if (hash == lastQuestionHash) return
        lastQuestionHash = hash

        NexusEvents.emitStatus("Đang giải...")
        NexusEvents.log("📖 Câu hỏi: ${extracted.question.take(100)}")

        val answer = ApiClient.solve(extracted.question)
        NexusEvents.emitAnswer(extracted.question, answer)
        NexusEvents.emitStatus("Đáp án: $answer")

        if (answer !in listOf("A", "B", "C", "D")) return

        extracted.options[answer]?.let { node ->
            if (gestureClickNode(node)) NexusEvents.log("👆 Click: $answer")
            delay(POST_CLICK_DELAY_MS)
        }

        if (autoNextEnabled) handler.postDelayed({ tryAutoNext() }, 500)
    }

    private fun extractQuiz(root: AccessibilityNodeInfo): Extracted? {
        val texts = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        collectTexts(root, texts)
        if (texts.size < 5) return null

        val clean = texts
            .map { it.first.trim() to it.second }
            .filter { (t, _) -> t.isNotEmpty() && !isNoise(t) }

        val options = mutableMapOf<String, AccessibilityNodeInfo>()
        var firstOptionIdx = -1

        for ((idx, pair) in clean.withIndex()) {
            val m = ANSWER_REGEX.find(pair.first) ?: continue
            val letter = m.groupValues[1].uppercase()
            if (letter !in options) {
                options[letter] = pair.second
                if (firstOptionIdx == -1) firstOptionIdx = idx
            }
            if (options.size == 4) break
        }

        if (options.size < 4) {
            val tail = clean.takeLast(4)
            if (tail.size == 4) {
                options["A"] = tail[0].second
                options["B"] = tail[1].second
                options["C"] = tail[2].second
                options["D"] = tail[3].second
                firstOptionIdx = clean.size - 4
            }
        }

        if (options.size < 4 || firstOptionIdx < 1) return null

        val questionText = clean.subList(0, firstOptionIdx)
            .joinToString(" ") { it.first }.trim()
        if (questionText.length < 10) return null

        val fullQuestion = buildString {
            appendLine("Câu hỏi: $questionText")
            options.toSortedMap().forEach { (letter, node) ->
                val t = node.text?.toString()?.trim().orEmpty()
                appendLine("$letter. $t")
            }
            append("Chọn đáp án đúng (chỉ trả về 1 chữ A/B/C/D).")
        }

        return Extracted(fullQuestion, options)
    }

    private fun collectTexts(
        node: AccessibilityNodeInfo,
        out: MutableList<Pair<String, AccessibilityNodeInfo>>
    ) {
        val txt = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val display = txt.ifEmpty { desc }
        if (display.isNotEmpty() && display.length < 500) out.add(display to node)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out)
        }
    }

    private fun isNoise(t: String): Boolean {
        if (Regex("""^\d+\s*/\s*\d+$""").matches(t)) return true
        if (Regex("""^\d+$""").matches(t)) return true
        val junk = listOf(
            "đăng nhập", "đăng ký", "trang chủ", "menu", "logout", "login",
            "share", "chia sẻ", "báo cáo", "xem thêm", "tải app", "cài đặt",
            "thoát", "quay lại", "back", "trang trước", "trang sau"
        )
        val low = t.lowercase()
        return junk.any { low == it || low.startsWith("$it ") }
    }

    private fun gestureClickNode(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false

        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {}
            override fun onCancelled(g: GestureDescription?) {
                NexusEvents.log("⚠️ Gesture huỷ")
            }
        }, null)
    }

    private fun tryAutoNext() {
        val root = rootInActiveWindow ?: return
        val patterns = listOf(
            "tiếp theo", "tiếp", "next", "nộp bài", "hoàn thành",
            "kết thúc", ">", "→", "continue", "submit", "câu tiếp"
        )
        val node = findClickableByText(root, patterns) ?: return
        gestureClickNode(node)
        NexusEvents.log("➡️ Auto-Next")
    }

    private fun findClickableByText(
        node: AccessibilityNodeInfo,
        patterns: List<String>
    ): AccessibilityNodeInfo? {
        val t = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
            .trim().lowercase()

        if (t.isNotEmpty() && patterns.any { t == it || t.contains(it) }) {
            var cur: AccessibilityNodeInfo? = node
            while (cur != null) {
                if (cur.isClickable && cur.isEnabled) return cur
                cur = cur.parent
            }
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableByText(child, patterns)?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {
        NexusEvents.log("⚠️ Accessibility bị ngắt")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        NexusEvents.log("🛑 Accessibility dừng")
    }

    data class Extracted(
        val question: String,
        val options: Map<String, AccessibilityNodeInfo>
    )
}
