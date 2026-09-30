package com.palmclaw.phonecontrol.server

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import com.palmclaw.phonecontrol.ApkMcpApp
import com.palmclaw.phonecontrol.capture.ScreenCaptureService
import com.palmclaw.phonecontrol.control.AgentAccessibilityService
import com.palmclaw.phonecontrol.core.Logs
import com.palmclaw.phonecontrol.core.Prefs
import com.palmclaw.phonecontrol.enhance.ShizukuEnhance
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 一次工具调用的结果：一段文字 + 可选的一张图 */
data class ToolResult(val text: String, val imageBase64: String? = null, val isError: Boolean = false) {

    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
            val img = imageBase64
            if (img != null) {
                addJsonObject {
                    put("type", "image")
                    put("data", img)
                    put("mimeType", "image/jpeg")
                }
            }
        }
        put("isError", isError)
    }

    companion object {
        fun text(s: String) = ToolResult(s)
        fun error(s: String) = ToolResult(s, null, true)
        fun image(text: String, base64: String) = ToolResult(text, base64)
    }
}

data class ToolDef(val name: String, val description: String, val schema: JsonObject) {

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", schema)
    }
}

/**
 * AI 能调用的全部能力。
 *
 * 坐标约定：除非显式传 space=real，所有 x/y 都按「截图坐标系」解释
 * （即 AI 最近一次拿到的截图 JPEG 的像素），内部会换算回真实屏幕像素。
 */
object ToolRegistry {

    private fun obj(s: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(s) as JsonObject

    private fun noArgs(): JsonObject = obj("""{"type":"object","properties":{}}""")

    val defs: List<ToolDef> = listOf(
        ToolDef(
            "screenshot",
            "截取当前屏幕。返回一张 JPEG 图片（坐标系就是图片像素），以及屏幕真实尺寸。" +
                "先用它看看现在屏幕上有什么，再决定点什么。" +
                "注意：如果 max_width 把图缩小了，后续 tap/swipe 的坐标也按这张图的像素解释。",
            obj(
                """{"type":"object","properties":{"max_width":{"type":"integer","description":"图片最长边，默认取 App 设置"},"quality":{"type":"integer","description":"JPEG 质量 10-100"}}}"""
            )
        ),
        ToolDef(
            "get_ui_tree",
            "读取当前界面的控件树（纯文本，含文字/描述/可点击/坐标）。" +
                "比截图更省 token、更精确，适合普通 App；自定义绘制的界面可能读不到。",
            obj("""{"type":"object","properties":{"max_nodes":{"type":"integer","description":"最多返回多少个控件，默认 300"}}}""")
        ),
        ToolDef(
            "tap",
            "点击屏幕上的一个点。x/y 默认按截图坐标系给（见 screenshot 返回的尺寸）。",
            obj(
                """{"type":"object","properties":{"x":{"type":"number"},"y":{"type":"number"},"space":{"type":"string","enum":["screenshot","real"],"description":"默认 screenshot"}},"required":["x","y"]}"""
            )
        ),
        ToolDef(
            "long_press",
            "长按屏幕上的一个点（约 650ms）。",
            obj(
                """{"type":"object","properties":{"x":{"type":"number"},"y":{"type":"number"},"space":{"type":"string","enum":["screenshot","real"]}},"required":["x","y"]}"""
            )
        ),
        ToolDef(
            "swipe",
            "从 (x1,y1) 滑到 (x2,y2)，可用来滚动列表、翻页、下拉通知栏。",
            obj(
                """{"type":"object","properties":{"x1":{"type":"number"},"y1":{"type":"number"},"x2":{"type":"number"},"y2":{"type":"number"},"duration_ms":{"type":"integer","description":"默认 300"},"space":{"type":"string","enum":["screenshot","real"]}},"required":["x1","y1","x2","y2"]}"""
            )
        ),
        ToolDef(
            "type_text",
            "往当前聚焦的输入框写入文字。如果没反应，先 tap 一下输入框再调。",
            obj("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""")
        ),
        ToolDef(
            "press_key",
            "按系统按键。",
            obj(
                """{"type":"object","properties":{"key":{"type":"string","enum":["back","home","recents","notifications","quick_settings"]}},"required":["key"]}"""
            )
        ),
        ToolDef(
            "find_and_tap",
            "按文字/描述查找控件并点击它的中心。比手算坐标可靠得多，优先用它。",
            obj(
                """{"type":"object","properties":{"text":{"type":"string","description":"要匹配的文字或 contentDescription"},"exact":{"type":"boolean","description":"是否完全相等，默认 false（包含即可）"}},"required":["text"]}"""
            )
        ),
        ToolDef(
            "scroll",
            "在当前可滚动区域上下滚动。",
            obj(
                """{"type":"object","properties":{"direction":{"type":"string","enum":["up","down"]}},"required":["direction"]}"""
            )
        ),
        ToolDef(
            "launch_app",
            "按包名或应用名打开一个 App。若设备已安装并授权 Shizuku，自动走特权通道，不受后台启动限制。",
            obj("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""")
        ),
        ToolDef(
            "list_apps",
            "列出所有可启动的 App（名称 + 包名）。",
            noArgs()
        ),
        ToolDef(
            "current_app",
            "返回当前前台 App 的包名。",
            noArgs()
        ),
        ToolDef(
            "open_url",
            "用系统浏览器打开一个网址。",
            obj("""{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""")
        ),
        ToolDef(
            "wait",
            "等待一段时间，让界面动画跑完再截图。",
            obj("""{"type":"object","properties":{"ms":{"type":"integer","description":"默认 800，最大 10000"}}}""")
        ),
        ToolDef(
            "screen_size",
            "返回屏幕真实尺寸和截图尺寸，以及两者的缩放比。",
            noArgs()
        ),
        ToolDef(
            "get_status",
            "查看当前各权限/服务是否就绪。",
            noArgs()
        )
    )

    // ── 调用入口 ────────────────────────────────────────────

    /**
     * 一台手机只有一双手：所有工具调用经 lock 全局串行执行，防止并发手势 / 并发读帧互相踩。
     * wait / launch_app 等在锁内阻塞是有意为之 —— 它们本来就该让「下一步」等结果。
     */
    private val lock = Any()

    fun call(name: String, args: JsonObject?): ToolResult {
        val a = args ?: JsonObject(emptyMap())
        return try {
            synchronized(lock) {
                dispatch(name, a)
            }
        } catch (t: Throwable) {
            Logs.add("工具 $name 异常: ${t.message}")
            ToolResult.error("$name 执行失败: ${t.message}")
        }
    }

    private fun dispatch(name: String, a: JsonObject): ToolResult = when (name) {
        "screenshot" -> screenshot(a)
        "get_ui_tree" -> uiTree(a)
        "tap" -> tap(a)
        "long_press" -> longPress(a)
        "swipe" -> swipe(a)
        "type_text" -> typeText(a)
        "press_key" -> pressKey(a)
        "find_and_tap" -> findAndTap(a)
        "scroll" -> scroll(a)
        "launch_app" -> launchApp(a)
        "list_apps" -> listApps()
        "current_app" -> currentApp()
        "open_url" -> openUrl(a)
        "wait" -> waitTool(a)
        "screen_size" -> screenSize()
        "get_status" -> status()
        else -> ToolResult.error("未知工具: $name")
    }

    // ── 各工具实现 ──────────────────────────────────────────

    private fun screenshot(a: JsonObject): ToolResult {
        val mgr = ScreenCaptureService.instance
            ?: return ToolResult.error("屏幕捕获没开。请在 APK MCP App 里点「开启屏幕捕获」并同意录屏。")
        if (!mgr.running) return ToolResult.error("屏幕捕获已停止，请重新开启。")
        if (!mgr.awaitFirstFrame(2000L)) return ToolResult.error("还没拿到画面，稍后再试。")

        val cfg = Prefs.config.value
        val mw = a["max_width"]?.jsonPrimitive?.intOrNull ?: cfg.maxWidth
        val q = a["quality"]?.jsonPrimitive?.intOrNull ?: cfg.jpegQuality
        val cap = mgr.captureJpeg(mw, q) ?: return ToolResult.error("截图失败。")
        val b64 = Base64.encodeToString(cap.bytes, Base64.NO_WRAP)
        val text = buildString {
            append("截图尺寸 ").append(cap.width).append('x').append(cap.height)
            append("，屏幕真实尺寸 ").append(mgr.realWidth).append('x').append(mgr.realHeight)
            append("。接下来 tap/swipe 的坐标请按「截图尺寸」给。")
        }
        return ToolResult.image(text, b64)
    }

    private fun uiTree(a: JsonObject): ToolResult {
        val svc = AgentAccessibilityService.instance
            ?: return ToolResult.error("无障碍服务没开，无法读取控件树。")
        val maxNodes = a["max_nodes"]?.jsonPrimitive?.intOrNull ?: 300
        val tree = svc.uiTree(maxNodes.coerceIn(20, 1200))
        val (rw, rh) = svc.realSize()
        val head = "真实屏幕 ${rw}x$rh，控件坐标即真实像素。\n"
        return ToolResult.text(head + tree)
    }

    private fun tap(a: JsonObject): ToolResult {
        val svc = AgentAccessibilityService.instance
            ?: return ToolResult.error("无障碍服务没开。")
        val p = resolveXY(a, "x", "y") ?: return ToolResult.error("需要 x 和 y。")
        val ok = svc.tap(p.first, p.second)
        return if (ok) ToolResult.text("已点击 (${p.first.toInt()}, ${p.second.toInt()})")
        else ToolResult.error("点击失败：手势被系统取消或超时未确认（可能被用户触摸打断），可稍后重试。")
    }

    private fun longPress(a: JsonObject): ToolResult {
        val svc = AgentAccessibilityService.instance
            ?: return ToolResult.error("无障碍服务没开。")
        val p = resolveXY(a, "x", "y") ?: return ToolResult.error("需要 x 和 y。")
        val ok = svc.longPress(p.first, p.second)
        return if (ok) ToolResult.text("已长按 (${p.first.toInt()}, ${p.second.toInt()})")
        else ToolResult.error("长按失败：手势被系统取消或超时未确认。")
    }

    private fun swipe(a: JsonObject): ToolResult {
        val svc = AgentAccessibilityService.instance
            ?: return ToolResult.error("无障碍服务没开。")
        val x1 = a["x1"]?.jsonPrimitive?.floatOrNull ?: return ToolResult.error("需要 x1")
        val y1 = a["y1"]?.jsonPrimitive?.floatOrNull ?: return ToolResult.error("需要 y1")
        val x2 = a["x2"]?.jsonPrimitive?.floatOrNull ?: return ToolResult.error("需要 x2")
        val y2 = a["y2"]?.jsonPrimitive?.floatOrNull ?: return ToolResult.error("需要 y2")
        val dur = (a["duration_ms"]?.jsonPrimitive?.intOrNull ?: 300).toLong()
        val s = scaleFactor(a)
        val ok = svc.swipe(x1 * s, y1 * s, x2 * s, y2 * s, dur)
        return if (ok) ToolResult.text("已滑动 (${x1.toInt()},${y1.toInt()}) → (${x2.toInt()},${y2.toInt()})")
        else ToolResult.error("滑动失败：手势被系统取消或超时未确认。")
    }

    private fun typeText(a: JsonObject): ToolResult {
        val svc = AgentAccessibilityService.instance
            ?: return ToolResult.error("无障碍服务没开。")
        val text = a["text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("需要 text")
        val ok = svc.typeText(text)
        return if (ok) ToolResult.text("已写入文字（${text.length} 字）")
        else ToolResult.error("写入失败：没有找到可编辑输入框，先 tap 一下输入框再试。")
    }

    private fun pressKey(a: JsonObject): ToolResult {
        val svc = AgentAccessibilityService.instance
            ?: return ToolResult.error("无障碍服务没开。")
        val key = a["key"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("需要 key")
        val action = when (key.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return ToolResult.error("不支持的按键: $key")
        }
        val ok = svc.performGlobalAction(action)
        return if (ok) ToolResult.text("已按下 $key") else ToolResult.error("按键失败")
    }

    private fun findAndTap(a: JsonObject): ToolResult {
        val svc = AgentAccessibilityService.instance
            ?: return ToolResult.error("无障碍服务没开。")
        val needle = a["text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("需要 text")
        val exact = a["exact"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        val node = svc.findNode(needle, exact)
            ?: return ToolResult.error("界面上没找到「$needle」。可以改用截图 + tap 坐标。")
        val ok = svc.tapNode(node)
        return if (ok) ToolResult.text("已点击「$needle」")
        else ToolResult.error("找到「$needle」但点击失败。")
    }

    private fun scroll(a: JsonObject): ToolResult {
        val svc = AgentAccessibilityService.instance
            ?: return ToolResult.error("无障碍服务没开。")
        val dir = a["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
        val root = svc.rootNode() ?: return ToolResult.error("读不到界面。")
        var target = findScrollable(root, 0)
        if (target == null) {
            // 退化为手势滑动
            val (rw, rh) = svc.realSize()
            val cx = rw / 2f
            val ok = if (dir == "down") {
                svc.swipe(cx, rh * 0.75f, cx, rh * 0.25f, 350)
            } else {
                svc.swipe(cx, rh * 0.25f, cx, rh * 0.75f, 350)
            }
            return if (ok) ToolResult.text("已滑动$dir")
            else ToolResult.error("滚动失败")
        }
        val ok = svc.scrollNode(target, dir == "down")
        return if (ok) ToolResult.text("已滚动$dir")
        else ToolResult.error("该区域不支持滚动")
    }

    private fun findScrollable(
        n: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (n.isScrollable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findScrollable(c, depth + 1)?.let { return it }
        }
        return null
    }

    private fun launchApp(a: JsonObject): ToolResult {
        val query = a["query"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("需要 query（包名或应用名）")
        val ctx = ApkMcpApp.appContext
        val pm = ctx.packageManager

        var pkg: String? = query.takeIf { pm.getLaunchIntentForPackage(it) != null }
        if (pkg == null) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(intent, 0)
            val hit = list.firstOrNull {
                val label = it.loadLabel(pm).toString()
                val name = it.activityInfo.packageName
                label.contains(query, true) || name.contains(query, true)
            }
            if (hit != null) pkg = hit.activityInfo.packageName
        }
        if (pkg == null) return ToolResult.error("找不到应用「$query」，可以用 list_apps 看看有哪些。")

        val li = pm.getLaunchIntentForPackage(pkg)
            ?: return ToolResult.error("$pkg 没有可启动的入口。")
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        li.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        val svc = AgentAccessibilityService.instance
        val before = svc?.currentPackage()

        // ① Shizuku 特权通道（已授权且未被关掉时优先）：shell uid 不受后台启动 Activity 限制，
        //    MIUI / HyperOS / EMUI 等 ROM 的私有限制也拦不住（悬浮窗豁免在这些 ROM 上常常无效）。
        if (Prefs.config.value.preferShizuku && ShizukuEnhance.available()) {
            val (ok, out) = ShizukuEnhance.startIntent(li)
            Logs.add("Shizuku 启动 $pkg → ${out.take(80)}")
            if (ok && awaitForeground(pkg, 2500L)) {
                return ToolResult.text("已启动 $pkg（Shizuku 特权通道，当前前台: ${svc?.currentPackage()}）")
            }
            Logs.add("Shizuku 通道未生效，回退常规方式")
        }

        // ② 常规方式（需要悬浮窗豁免；部分 ROM 可能仍拦截）
        try {
            ctx.startActivity(li)
        } catch (t: Throwable) {
            return ToolResult.error("启动 $pkg 失败: ${t.message}")
        }

        val switched = awaitForeground(pkg, 2500L)
        val now = svc?.currentPackage()
        return if (switched) {
            ToolResult.text("已启动 $pkg（当前前台: $now）")
        } else {
            ToolResult.error(
                "调用了 $pkg 的启动 Intent，但 2.5 秒后前台仍是 " + (now ?: "未知") +
                    "（启动前是 " + (before ?: "未知") + "）。\n" +
                    "原因: Android 10+ 默认禁止后台应用启动 Activity，部分 ROM（MIUI/EMUI 等）还有更严的私有限制，悬浮窗豁免也可能无效。\n" +
                    "解决: ① 在 APK MCP 控制台「增强」卡片里安装并授权 Shizuku，用特权通道启动（最稳）；" +
                    "② 开启「悬浮窗」权限后重试；" +
                    "③ 先 press_key home 回到桌面，再调 launch_app。"
            )
        }
    }

    /** 轮询，等前台真的切到 pkg */
    private fun awaitForeground(pkg: String, timeoutMs: Long): Boolean {
        val svc = AgentAccessibilityService.instance ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val cur = svc.currentPackage()
            if (cur != null && cur == pkg) return true
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                return svc.currentPackage() == pkg
            }
        }
        return svc.currentPackage() == pkg
    }

    private fun listApps(): ToolResult {
        val ctx = ApkMcpApp.appContext
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first }
        val sb = StringBuilder("共 ${list.size} 个可启动应用：\n")
        for ((label, pkg) in list) sb.append(label).append("  ").append(pkg).append('\n')
        return ToolResult.text(sb.toString())
    }

    private fun currentApp(): ToolResult {
        val svc = AgentAccessibilityService.instance
            ?: return ToolResult.error("无障碍服务没开。")
        val pkg = svc.currentPackage() ?: return ToolResult.text("读不到前台包名。")
        return ToolResult.text("前台应用: $pkg")
    }

    private fun openUrl(a: JsonObject): ToolResult {
        val url = a["url"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("需要 url")
        val ctx = ApkMcpApp.appContext
        val svc = AgentAccessibilityService.instance
        val before = svc?.currentPackage()

        // ① Shizuku 特权通道：am start 不受后台启动限制
        if (Prefs.config.value.preferShizuku && ShizukuEnhance.available()) {
            val (ok, out) = ShizukuEnhance.openUrl(url)
            Logs.add("Shizuku 打开 $url → ${out.take(80)}")
            if (ok && awaitForegroundChanged(before, 2500L)) {
                return ToolResult.text("已打开 $url（Shizuku 特权通道，当前前台: ${svc?.currentPackage()}）")
            }
            Logs.add("Shizuku 通道未生效，回退常规方式")
        }

        // ② 常规方式
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (t: Throwable) {
            return ToolResult.error("打开 $url 失败: ${t.message}")
        }
        val changed = awaitForegroundChanged(before, 2500L)
        val now = svc?.currentPackage()
        return if (changed) {
            ToolResult.text("已打开 $url（当前前台: $now）")
        } else {
            ToolResult.error(
                "调用了打开 $url 的 Intent，但 2.5 秒后前台仍是 " + (now ?: "未知") +
                    "。多半也是后台启动 Activity 被拦截：安装并授权 Shizuku（特权通道，最稳），或开启「悬浮窗」权限后重试。"
            )
        }
    }

    /** 轮询，等前台从 before 变成别的 App */
    private fun awaitForegroundChanged(before: String?, timeoutMs: Long): Boolean {
        val svc = AgentAccessibilityService.instance ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val cur = svc.currentPackage()
            if (cur != null && cur != before) return true
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                return svc.currentPackage() != before
            }
        }
        return svc.currentPackage() != before
    }

    private fun waitTool(a: JsonObject): ToolResult {
        val ms = (a["ms"]?.jsonPrimitive?.intOrNull ?: 800).coerceIn(0, 10000)
        try {
            Thread.sleep(ms.toLong())
        } catch (_: InterruptedException) {
        }
        return ToolResult.text("已等待 ${ms}ms")
    }

    private fun screenSize(): ToolResult {
        val svc = AgentAccessibilityService.instance
        val (rw, rh) = svc?.realSize() ?: (0 to 0)
        val mgr = ScreenCaptureService.instance
        // 与 scaleFactor 一致：报告最近一次实际返回的截图尺寸，即 AI 应使用的坐标空间
        val iw = when {
            mgr != null && mgr.lastJpegWidth > 0 -> mgr.lastJpegWidth
            mgr != null -> mgr.imageWidth
            else -> 0
        }
        val ih = when {
            mgr != null && mgr.lastJpegHeight > 0 -> mgr.lastJpegHeight
            mgr != null -> mgr.imageHeight
            else -> 0
        }
        val ratio = if (iw > 0 && rw > 0) rw.toFloat() / iw else 1f
        return ToolResult.text(
            "真实屏幕 ${rw}x$rh\n截图尺寸（当前坐标空间）${iw}x$ih\n坐标缩放比 x$ratio（截图坐标 × $ratio = 真实坐标）"
        )
    }

    private fun status(): ToolResult {
        val cfg = Prefs.config.value
        val svc = AgentAccessibilityService.instance
        val (rw, rh) = svc?.realSize() ?: (0 to 0)
        val capture = ScreenCaptureService.instance
        val sb = StringBuilder()
        sb.append("无障碍服务: ").append(if (svc != null) "已开启" else "未开启").append('\n')
        val canOverlay = try {
            Settings.canDrawOverlays(ApkMcpApp.appContext)
        } catch (t: Throwable) {
            false
        }
        sb.append("悬浮窗权限: ").append(
            if (canOverlay) "已开启（launch_app 不会被后台启动限制拦截）"
            else "未开启（launch_app / open_url 可能被系统拦截）"
        ).append('\n')
        sb.append("Shizuku 增强: ").append(ShizukuEnhance.statusText()).append('\n')
        sb.append("屏幕捕获: ")
            .append(if (capture?.running == true) "运行中 ${capture.imageWidth}x${capture.imageHeight}" else "未开启")
            .append('\n')
        sb.append("真实屏幕: ${rw}x$rh\n")
        sb.append("HTTP 端口: ").append(cfg.port).append('\n')
        sb.append("监听: ").append(if (cfg.bindAll) "0.0.0.0（局域网可访问）" else "127.0.0.1（仅本机）").append('\n')
        sb.append("截图最长边: ").append(cfg.maxWidth).append("，质量 ").append(cfg.jpegQuality)
        return ToolResult.text(sb.toString())
    }

    // ── 坐标换算 ────────────────────────────────────────────

    private fun scaleFactor(a: JsonObject): Float {
        val space = a["space"]?.jsonPrimitive?.contentOrNull ?: "screenshot"
        if (space == "real") return 1f
        val mgr = ScreenCaptureService.instance ?: return 1f
        if (mgr.realWidth <= 0) return 1f
        // 基准坐标空间 = 最近一次返回的截图实际尺寸（含 max_width 缩小的情况），
        // 只有从未截过图才退化到虚拟显示尺寸，保证 tap/swipe 坐标与 AI 看到的图永远一致。
        val w = if (mgr.lastJpegWidth > 0) mgr.lastJpegWidth else mgr.imageWidth
        if (w <= 0 || w == mgr.realWidth) return 1f
        return mgr.realWidth.toFloat() / w
    }

    private fun resolveXY(a: JsonObject, kx: String, ky: String): Pair<Float, Float>? {
        val x = a[kx]?.jsonPrimitive?.floatOrNull ?: return null
        val y = a[ky]?.jsonPrimitive?.floatOrNull ?: return null
        val s = scaleFactor(a)
        return (x * s) to (y * s)
    }

    @Suppress("unused")
    private fun jsonArrayOf(vararg s: String): JsonArray = buildJsonArray {
        s.forEach { add(JsonPrimitive(it)) }
    }

    @Suppress("unused")
    private fun nullable(e: JsonElement?): JsonElement = e ?: JsonNull
}
