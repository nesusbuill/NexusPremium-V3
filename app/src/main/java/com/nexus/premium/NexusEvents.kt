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

    /** 🆕 Kênh scan preview (bảng quét đề tầng sâu) */
    private val _scanPreviews = MutableSharedFlow<ScanPreviewEvent>(extraBufferCapacity = 8)
    val scanPreviews: SharedFlow<ScanPreviewEvent> = _scanPreviews

    /** 🆕 Kênh answer detail (khung xem đáp án chi tiết) */
    private val _answerDetails = MutableSharedFlow<AnswerDetailEvent>(extraBufferCapacity = 8)
    val answerDetails: SharedFlow<AnswerDetailEvent> = _answerDetails

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

    /** 🆕 Bắn scan preview ngay sau khi extract thành công, TRƯỚC khi gọi AI */
    fun emitScanPreview(ev: ScanPreviewEvent) {
        Log.d(TAG, "🔍 Scan: ${ev.question.take(60)} (lọc ${ev.filteredCount} rác)")
        _scanPreviews.tryEmit(ev)
    }

    /** 🆕 Bắn answer detail khi AI trả về đáp án */
    fun emitAnswerDetail(ev: AnswerDetailEvent) {
        Log.d(TAG, "📋 Detail: ${ev.answer}")
        _answerDetails.tryEmit(ev)
    }
}

data class AnswerEvent(val question: String, val answer: String)

/** 🆕 Payload của bảng quét đề tầng sâu */
data class ScanPreviewEvent(
    val question: String,
    val options: Map<String, String>,
    val filteredCount: Int
)

/** 🆕 Payload của khung xem đáp án chi tiết */
data class AnswerDetailEvent(
    val question: String,
    val options: Map<String, String>,
    val answer: String
)
