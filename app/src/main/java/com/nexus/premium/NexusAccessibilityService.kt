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
 *
 * PIPELINE V4:
 *   1. Deep scan leaf nodes (tầng sâu nhất) → tránh container chứa quảng cáo
 *   2. Lọc rác: menu, banner, ad patterns, số trang...
 *   3. Emit SCAN PREVIEW (hiện trên HUD để user kiểm tra)
 *   4. Gọi AI → Emit ANSWER + ANSWER DETAIL
 *   5. Gesture click đáp án + Auto-Next
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

        /**
         * Pattern quảng cáo nhiều từ — dùng "contains" (an toàn, không nhầm câu hỏi).
         * VD: "chính sách bảo mật" sẽ bị lọc, nhưng "bảo mật" đơn lẻ thì không.
         */
        private val AD_MULTI_PATTERNS = listOf(
            "quảng cáo", "sponsored", "advertisement", "banner quảng cáo",
            "click here", "mua ngay", "đặt hàng", "hotline:", "liên hệ:",
            "khuyến mãi", "giảm giá", "giới thiệu về", "about us",
            "chính sách bảo mật", "privacy policy", "điều khoản sử dụng",
            "terms of service", "cookie policy", "google ads", "ads by",
            "xem thêm bài viết", "đọc tiếp", "read more",
            "tải ứng dụng", "download app", "cài đặt ứng dụng"
        )

        /**
         * Menu/nav chính xác — chỉ lọc khi text == pattern (không lọc khi là substring).
         * An toàn cho câu hỏi dài có chứa từ này.
         */
        private val NAV_EXACT = setOf(
            "đăng nhập", "đăng ký", "trang chủ", "menu", "logout", "login",
            "sign in", "sign up", "register",
            "share", "chia sẻ", "báo cáo", "xem thêm", "tải app", "cài đặt",
            "thoát", "quay lại", "back", "trang trước", "trang sau",
            "search", "tìm kiếm", "close", "đóng", "next", "prev",
            "trước", "sau", "chấp nhận", "từ chối", "accept", "reject",
            "cancel", "ok", "hủy", "đồng ý"
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
        val extracted = extractQuiz(root) ?: run {
            NexusEvents.emitStatus("Không tìm thấy đề")
            return
        }

        val hash = extracted.question.hashCode()
        if (hash == lastQuestionHash) return
        lastQuestionHash = hash

        // 🆕 Bắn scan preview NGAY để HUD hiện nội dung đã quét
        NexusEvents.emitScanPreview(
            ScanPreviewEvent(
                question = extracted.questionText,
                options = extracted.optionsText,
                filteredCount = extracted.filteredCount
            )
        )

        NexusEvents.emitStatus("Đang giải...")
        NexusEvents.log("📖 Q: ${extracted.question.take(80)}")

        val answer = ApiClient.solve(extracted.question)
        NexusEvents.emitAnswer(extracted.question, answer)
        NexusEvents.emitStatus("Đáp án: $answer")

        // 🆕 Bắn answer detail khi có đáp án
        NexusEvents.emitAnswerDetail(
            AnswerDetailEvent(
                question = extracted.questionText,
                options = extracted.optionsText,
                answer = answer
            )
        )

        if (answer !in listOf("A", "B", "C", "D")) return

        extracted.options[answer]?.let { node ->
            if (gestureClickNode(node)) NexusEvents.log("👆 Click: $answer")
            delay(POST_CLICK_DELAY_MS)
        }

        if (autoNextEnabled) handler.postDelayed({ tryAutoNext() }, 500)
    }

    /**
     * Extract đề với DEEP SCAN.
     * Chỉ lấy LEAF NODES (childCount == 0) — tầng sâu nhất của cây accessibility.
     * Lý do: node cha thường gộp text quảng cáo + text nội dung → dễ nhiễu.
     */
    private fun extractQuiz(root: AccessibilityNodeInfo): Extracted? {
        val rawTexts = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        collectLeafTexts(root, rawTexts)
        if (rawTexts.size < 5) return null

        // Lọc rác + đếm số đã lọc để hiển thị "Đã lọc N rác"
        var filteredCount = 0
        val clean = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        for ((text, node) in rawTexts) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) continue
            if (isAdOrNoise(trimmed)) {
                filteredCount++
                continue
            }
            clean.add(trimmed to node)
        }
        if (clean.size < 5) return null

        // Tìm 4 đáp án có prefix A/B/C/D
        val options = mutableMapOf<String, AccessibilityNodeInfo>()
        val optionsText = mutableMapOf<String, String>()
        var firstOptionIdx = -1

        for ((idx, pair) in clean.withIndex()) {
            val m = ANSWER_REGEX.find(pair.first) ?: continue
            val letter = m.groupValues[1].uppercase()
            if (letter !in options) {
                options[letter] = pair.second
                optionsText[letter] = pair.first
                if (firstOptionIdx == -1) firstOptionIdx = idx
            }
            if (options.size == 4) break
        }

        // Fallback: 4 text cuối nếu không có prefix
        if (options.size < 4) {
            val tail = clean.takeLast(4)
            if (tail.size == 4) {
                val letters = listOf("A", "B", "C", "D")
                for (i in 0..3) {
                    options[letters[i]] = tail[i].second
                    optionsText[letters[i]] = tail[i].first
                }
                firstOptionIdx = clean.size - 4
            }
        }

        if (options.size < 4 || firstOptionIdx < 1) return null

        val questionText = clean.subList(0, firstOptionIdx)
            .joinToString(" ") { it.first }.trim()
        if (questionText.length < 10) return null

        val fullQuestion = buildString {
            appendLine("Câu hỏi: $questionText")
            optionsText.toSortedMap().forEach { (letter, text) ->
                appendLine("$letter. $text")
            }
            append("Chọn đáp án đúng (chỉ trả về 1 chữ A/B/C/D).")
        }

        return Extracted(
            question = fullQuestion,
            options = options,
            optionsText = optionsText.toSortedMap(),
            questionText = questionText,
            filteredCount = filteredCount
        )
    }

    /**
     * 🆕 DEEP SCAN — chỉ lấy leaf nodes (tầng sâu nhất).
     * Tránh node cha chứa text quảng cáo + text nội dung trộn lẫn.
     */
    private fun collectLeafTexts(
        node: AccessibilityNodeInfo,
        out: MutableList<Pair<String, AccessibilityNodeInfo>>
    ) {
        val txt = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val display = txt.ifEmpty { desc }

        // Chỉ nhận nếu là LEAF (không có con) hoặc không con nào có text
        val isLeaf = node.childCount == 0 || !hasChildWithText(node)

        if (display.isNotEmpty() && display.length < 500 && isLeaf) {
            out.add(display to node)
            return  // Đã nhận node cha (leaf thực sự) → không đệ quy tiếp
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectLeafTexts(child, out)
        }
    }

    private fun hasChildWithText(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (!c.text.isNullOrEmpty() || !c.contentDescription.isNullOrEmpty()) return true
        }
        return false
    }

    /** 🆕 Lọc quảng cáo + menu rác (an toàn cho câu hỏi dài). */
    private fun isAdOrNoise(t: String): Boolean {
        // Số trang "1/14", số trần
        if (Regex("""^\d+\s*/\s*\d+$""").matches(t)) return true
        if (Regex("""^\d{1,4}$""").matches(t)) return true

        val low = t.lowercase().trim()

        // Multi-word ad patterns (contains - an toàn)
        if (AD_MULTI_PATTERNS.any { low.contains(it) }) return true

        // Menu/nav chính xác (exact match - không lọc substring)
        if (NAV_EXACT.contains(low)) return true

        return false
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
        val options: Map<String, AccessibilityNodeInfo>,
        val optionsText: Map<String, String>,
        val questionText: String,
        val filteredCount: Int
    )
}
