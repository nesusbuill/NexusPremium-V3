package com.nexus.premium

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ApiClient - Gọi Groq API giải trắc nghiệm.
 *
 * V4 CHANGES:
 *  - Đọc API key từ ApiKeyStore (SharedPreferences) → user đổi key không cần build lại.
 *  - Fallback về FALLBACK_KEYS nếu user chưa nhập.
 *  - Vẫn giữ nguyên: model chain, multi-key rotation, parse 3 tầng.
 */
object ApiClient {

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    /**
     * CHAIN MODEL — Thứ tự ưu tiên.
     * Nếu Groq khai tử 1 model → tự nhảy sang model kế tiếp.
     */
    private val MODEL_CHAIN = listOf(
        "openai/gpt-oss-120b",
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant"
    )

    /**
     * FALLBACK KEYS — CHỈ DÙNG KHI user chưa nhập key trong app.
     * Nếu user đã nhập key qua dialog → ApiKeyStore.getUserKeys() sẽ được ưu tiên.
     */
    private val FALLBACK_KEYS = listOf(
        "gsk_KEY1_THAY_VAO_DAY",
        "gsk_KEY2_THAY_VAO_DAY",
        "gsk_KEY3_THAY_VAO_DAY"
    )

    private val SYSTEM_PROMPT = """
Bạn là NEXUS PRIME - Hội đồng 5 chuyên gia. Nhiệm vụ: Giải CHÍNH XÁC TUYỆT ĐỐI câu hỏi trắc nghiệm sau.

BẮT BUỘC ÁP DỤNG 10 KỸ NĂNG NGHIÊN CỨU & ĐÀO SÂU (TỰ SUY LUẬN NGẦM):
1. /extract & /digest: Lọc bỏ từ rác của web (Menu, 1/14...). Chỉ lấy câu hỏi và đáp án.
2. /investigate: Điều tra bẫy chữ "KHÔNG", "NGOẠI TRỪ", "CHỈ".
3. /deepdive: Đào sâu ngữ cảnh câu hỏi.
4. COMPARE: Đặt 4 đáp án cạnh nhau, so sánh từng cặp.
5. FACTCHECK: Kiểm chứng lại kiến thức, sự kiện.
6. /sources: Đảm bảo kiến thức có nguồn gốc vững chắc.
7. TIMELINE: Nếu là câu hỏi lịch sử, sắp xếp dòng thời gian.
8. /deepdive & INVERT: Tìm lý do tại sao 3 đáp án kia SAI.
9. XRAY: Nhìn xuyên qua đáp án gây nhiễu.
10. DOSSIER & OODA: Chốt đáp án đúng nhất.

YÊU CẦU ĐẦU RA: Chỉ trả về 1 đối tượng JSON duy nhất. Định dạng: {"answer": "A"}. KHÔNG markdown, KHÔNG giải thích, KHÔNG trả về "?".
    """.trimIndent()

    private val keyIndex = AtomicInteger(0)
    private val keyLock = Mutex()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * ✅ Lấy danh sách key đang hoạt động.
     * Ưu tiên: user keys (SharedPreferences) → fallback keys (hard-code).
     */
    private fun getActiveKeys(): List<String> {
        val userKeys = ApiKeyStore.getUserKeys()
        return if (userKeys.isNotEmpty()) userKeys else FALLBACK_KEYS
    }

    // ==================== API ====================

    suspend fun solve(question: String): String = withContext(Dispatchers.IO) {
        // Check có key chưa
        if (getActiveKeys().isEmpty()) {
            NexusEvents.log("❌ Chưa có API key — mở app nhập key!")
            return@withContext "?"
        }

        for ((index, model) in MODEL_CHAIN.withIndex()) {
            NexusEvents.log("🤖 Thử model #${index + 1}: $model")
            val result = trySolveWithModel(model, question)
            if (result != "?") return@withContext result
        }
        NexusEvents.log("❌ Tất cả model đều thất bại")
        "?"
    }

    private suspend fun trySolveWithModel(model: String, question: String): String {
        val keys = getActiveKeys()
        if (keys.isEmpty()) {
            NexusEvents.log("❌ Không có key nào khả dụng")
            return "?"
        }

        var lastError = ""
        repeat(keys.size * 2) { attempt ->
            val currentKey = getNextKey()
            if (currentKey.isEmpty()) {
                NexusEvents.log("❌ getNextKey() trả về rỗng")
                return "?"
            }
            try {
                val result = callOnce(model, currentKey, question)
                if (result != null) return result
            } catch (e: DeprecatedModelException) {
                NexusEvents.log("⚠️ Model $model đã bị khai tử")
                return "?"
            } catch (e: RateLimitException) {
                lastError = "429 (key ...${currentKey.takeLast(6)})"
                NexusEvents.log("⚠️ $model: rate limit - xoay key")
            } catch (e: AuthException) {
                lastError = "401 (key ...${currentKey.takeLast(6)})"
                NexusEvents.log("⚠️ $model: unauthorized - xoay key")
            } catch (e: Exception) {
                lastError = "network: ${e.message}"
                val backoff = (500L * (attempt + 1)).coerceAtMost(3000)
                NexusEvents.log("⚠️ $model: $lastError - backoff ${backoff}ms")
                delay(backoff)
            }
        }
        NexusEvents.log("❌ $model: ${keys.size * 2} lần đều thất bại ($lastError)")
        return "?"
    }

    private fun callOnce(model: String, key: String, question: String): String? {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            put(JSONObject().put("role", "user").put("content", question))
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.1)
            put("max_tokens", 32)
            put("response_format", JSONObject().put("type", "json_object"))
            put("stream", false)
        }.toString()

        val req = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonMedia))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            return when (resp.code) {
                200 -> parseAnswer(resp.body?.string() ?: return null)
                401 -> throw AuthException()
                404 -> throw DeprecatedModelException()
                429 -> throw RateLimitException()
                else -> {
                    NexusEvents.log("⚠️ HTTP ${resp.code}")
                    null
                }
            }
        }
    }

    private fun parseAnswer(raw: String): String = try {
        val content = JSONObject(raw)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message")
            .getString("content").trim()

        tryParseJsonAnswer(content)
            ?: tryParseJsonAnswer(content.replace("```json", "").replace("```", "").trim())
            ?: Regex(""""answer"\s*:\s*"([A-Da-d])"""").find(content)
                ?.groupValues?.get(1)?.uppercase()
            ?: run {
                NexusEvents.log("⚠️ Không parse được: ${content.take(80)}")
                "?"
            }
    } catch (e: Exception) {
        NexusEvents.log("⚠️ Parse lỗi: ${e.message}")
        "?"
    }

    private fun tryParseJsonAnswer(text: String): String? = try {
        val ans = JSONObject(text).optString("answer", "").uppercase().trim()
        if (ans.length == 1 && ans[0] in "ABCD") ans else null
    } catch (e: Exception) { null }

    /** ✅ getNextKey đọc từ danh sách key đang hoạt động (user keys hoặc fallback). */
    private suspend fun getNextKey(): String = keyLock.withLock {
        val keys = getActiveKeys()
        if (keys.isEmpty()) return@withLock ""
        val idx = keyIndex.getAndUpdate { (it + 1) % keys.size }
        keys[idx]
    }

    private class RateLimitException : Exception("429")
    private class AuthException : Exception("401")
    private class DeprecatedModelException : Exception("404")
}
