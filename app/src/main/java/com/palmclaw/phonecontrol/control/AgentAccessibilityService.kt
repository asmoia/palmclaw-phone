package com.palmclaw.phonecontrol.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.palmclaw.phonecontrol.core.Logs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 唯一的「手」：点击 / 长按 / 滑动 / 输入 / 返回。
 * 全部走无障碍 API，不需要 root，不需要 adb。
 *
 * 手势返回值语义：true = 系统确认手势已执行完成；false = 被取消 / 被拒 / 超时未确认。
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logs.add("无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Logs.add("无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ── 基本信息 ────────────────────────────────────────────

    fun rootNode(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (t: Throwable) {
        null
    }

    fun currentPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (t: Throwable) {
        null
    }

    fun realSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    fun uiTree(maxNodes: Int = 300): String = UiTreeReader.dump(rootNode(), maxNodes)

    // ── 手势 ────────────────────────────────────────────────

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 0L, 60L)
    }

    fun longPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 0L, 650L)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(path, 0L, durationMs.coerceIn(50L, 5000L))
    }

    /**
     * 派发一条手势，并等它真正执行完才返回。
     *
     * dispatchGesture 的返回值只代表「已受理」，不代表「已落地」；真正的成败由
     * GestureResultCallback 异步给出（被用户触摸打断、被安全策略拦截都会走 onCancelled）。
     * 这里用闭锁等到回调再返回（超时 = 手势时长 + 2 秒），调用方拿到的就是真实结果。
     *
     * 注：dispatchGesture 默认会取消仍在进行中的前一条手势 —— 由于 ToolRegistry
     * 已把所有工具调用串行化（见其 lock），本服务自己的手势不会互相打断。
     *
     * 防御：若在主线程调用则不能等（回调也走主线程，等了就是死锁），
     * 退化为只返回「已受理」。当前所有调用方都在 HTTP 工作线程，不走这条分支。
     */
    private fun dispatch(path: Path, start: Long, duration: Long): Boolean {
        var completed = false
        val done = CountDownLatch(1)
        val onMain = Looper.getMainLooper() === Looper.myLooper()
        return try {
            val stroke = GestureDescription.StrokeDescription(path, start, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val accepted = dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed = true
                        done.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Logs.add("手势被取消（可能被用户触摸或安全策略打断）")
                        done.countDown()
                    }
                },
                null
            )

            if (!accepted) {
                Logs.add("手势派发被系统拒绝")
                return false
            }
            if (onMain) {
                Logs.add("警告：主线程调用手势，无法等待真实结果，仅报已受理")
                return true
            }
            if (!done.await(duration + 2000L, TimeUnit.MILLISECONDS)) {
                Logs.add("手势结果超时未确认（时长 ${duration}ms）")
                return false
            }
            completed
        } catch (t: Throwable) {
            Logs.add("手势失败: ${t.message}")
            false
        }
    }

    // ── 控件查找 ────────────────────────────────────────────

    fun findNode(needle: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val root = rootNode() ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 4000) {
            val n = queue.removeFirst()
            visited++
            if (matches(n.text?.toString(), needle, exact) ||
                matches(n.contentDescription?.toString(), needle, exact) ||
                matches(n.hintText?.toString(), needle, exact)
            ) {
                return n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun matches(value: String?, needle: String, exact: Boolean): Boolean {
        if (value.isNullOrBlank()) return false
        return if (exact) value.equals(needle, ignoreCase = true)
        else value.contains(needle, ignoreCase = true)
    }

    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        return tap(cx, cy)
    }

    fun scrollNode(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        return try {
            node.performAction(
                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
        } catch (t: Throwable) {
            false
        }
    }

    // ── 输入 ────────────────────────────────────────────────

    fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootNode() ?: return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        return firstEditable(root, 0)
    }

    private fun firstEditable(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            firstEditable(c, depth + 1)?.let { return it }
        }
        return null
    }

    fun typeText(text: String, replace: Boolean = true): Boolean {
        val target = focusedEditable() ?: return false
        return try {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            Logs.add("输入失败: ${t.message}")
            false
        }
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun ready(): Boolean = instance != null
    }
}
