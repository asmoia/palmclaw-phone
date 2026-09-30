package com.palmclaw.phonecontrol.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存里的滚动日志，UI 上直接展示，方便看 AI 到底调了什么。
 */
object Logs {

    private const val MAX = 300
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        _lines.value = (_lines.value + line).takeLast(MAX)
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
