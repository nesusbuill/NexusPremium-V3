package com.nexus.premium

import android.content.Context
import android.content.SharedPreferences

/**
 * ApiKeyStore - Lưu/đọc API key Groq từ SharedPreferences.
 *
 * Ưu tiên:
 *  1. Key user nhập trong app (SharedPreferences)
 *  2. Fallback về key hard-code trong ApiClient.kt (nếu có)
 *
 * → User có thể đổi key tức thì KHÔNG cần build lại APK.
 */
object ApiKeyStore {

    private const val PREF_NAME = "nexus_api_keys"
    private const val KEY_1 = "key_1"
    private const val KEY_2 = "key_2"
    private const val KEY_3 = "key_3"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Gọi 1 lần ở MainActivity.onCreate() để init. */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    /** Trả về danh sách key user đã nhập (đã filter blank). */
    fun getUserKeys(): List<String> {
        val p = prefs ?: return emptyList()
        return listOf(
            p.getString(KEY_1, "").orEmpty(),
            p.getString(KEY_2, "").orEmpty(),
            p.getString(KEY_3, "").orEmpty()
        ).filter { it.isNotBlank() && it.startsWith("gsk_") }
    }

    /** Lưu 3 key. Truyền "" để xóa. */
    fun saveKeys(k1: String, k2: String, k3: String) {
        prefs?.edit()
            ?.putString(KEY_1, k1.trim())
            ?.putString(KEY_2, k2.trim())
            ?.putString(KEY_3, k3.trim())
            ?.apply()
    }

    /** Trả về 3 key riêng lẻ để hiển thị lại trong dialog. */
    fun getRawKeys(): Triple<String, String, String> {
        val p = prefs ?: return Triple("", "", "")
        return Triple(
            p.getString(KEY_1, "").orEmpty(),
            p.getString(KEY_2, "").orEmpty(),
            p.getString(KEY_3, "").orEmpty()
        )
    }

    fun hasAnyKey(): Boolean = getUserKeys().isNotEmpty()
}
