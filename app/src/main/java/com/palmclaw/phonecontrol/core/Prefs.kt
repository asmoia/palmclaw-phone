package com.palmclaw.phonecontrol.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 运行配置。全部持久化在 SharedPreferences 里，进程重启后仍在。
 */
data class McpConfig(
    val port: Int = 8765,
    val token: String = "",
    /** false = 只监听 127.0.0.1（同机 Termux 可访问）；true = 监听 0.0.0.0（局域网可访问） */
    val bindAll: Boolean = false,
    /** 截图最长边，越小越省 token */
    val maxWidth: Int = 720,
    /** JPEG 质量 10..100 */
    val jpegQuality: Int = 60,
    /** 0 跟随系统 / 1 浅色 / 2 深色 */
    val themeMode: Int = 0,
    /** Material You 动态取色 */
    val dynamicColor: Boolean = true,
    /** 服务停止后是否自动重启 */
    val sticky: Boolean = true,
    /** Shizuku 已授权时，launch_app / open_url 优先走特权通道（不受后台启动限制） */
    val preferShizuku: Boolean = true
)

object Prefs {

    private const val NAME = "apkmcp"
    private lateinit var sp: SharedPreferences

    private val _config = MutableStateFlow(McpConfig())
    val config: StateFlow<McpConfig> = _config

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        _config.value = read()
    }

    private fun read(): McpConfig {
        val d = McpConfig()
        return McpConfig(
            port = sp.getInt("port", d.port),
            token = sp.getString("token", d.token) ?: "",
            bindAll = sp.getBoolean("bindAll", d.bindAll),
            maxWidth = sp.getInt("maxWidth", d.maxWidth),
            jpegQuality = sp.getInt("jpegQuality", d.jpegQuality),
            themeMode = sp.getInt("themeMode", d.themeMode),
            dynamicColor = sp.getBoolean("dynamicColor", d.dynamicColor),
            sticky = sp.getBoolean("sticky", d.sticky),
            preferShizuku = sp.getBoolean("preferShizuku", d.preferShizuku)
        )
    }

    fun save(c: McpConfig) {
        if (!::sp.isInitialized) {
            _config.value = c
            return
        }
        sp.edit()
            .putInt("port", c.port)
            .putString("token", c.token)
            .putBoolean("bindAll", c.bindAll)
            .putInt("maxWidth", c.maxWidth)
            .putInt("jpegQuality", c.jpegQuality)
            .putInt("themeMode", c.themeMode)
            .putBoolean("dynamicColor", c.dynamicColor)
            .putBoolean("sticky", c.sticky)
            .putBoolean("preferShizuku", c.preferShizuku)
            .apply()
        _config.value = c
    }
}
