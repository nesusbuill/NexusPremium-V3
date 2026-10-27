package com.nexus.premium

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * NexusEvents - Event bus nội bộ.
 * Kênh giao tiếp: ApiClient ↔ AccessibilityService ↔ OverlayService.
 */
object NexusEvents {

    const val TAG = "NexusPremium"

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logs: SharedFlow<String> = _logs

    private val _answers = MutableSharedFlow<AnswerEvent>(extraBufferCapacity = 8)
    val answers: SharedFlow<AnswerEvent> = _answers

    private val _status = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val status: SharedFlow<String> = _status

    fun log(msg: String) {
        Log.d(TAG, msg)
        _logs.tryEmit(msg)
    }

    fun emitAnswer(question: String, answer: String) {
        Log.d(TAG, "➡️ Answer: $answer")
        _answers.tryEmit(AnswerEvent(question, answer))
    }

    fun emitStatus(msg: String) {
        Log.d(TAG, "📢 $msg")
        _status.tryEmit(msg)
    }
}

data class AnswerEvent(val question: String, val answer: String)
