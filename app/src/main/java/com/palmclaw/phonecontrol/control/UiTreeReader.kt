package com.palmclaw.phonecontrol.control

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 把无障碍控件树压成一段紧凑文本，纯文本模型也能「看」界面。
 */
object UiTreeReader {

    fun dump(root: AccessibilityNodeInfo?, maxNodes: Int = 300): String {
        if (root == null) return "(当前没有可读窗口)"
        val sb = StringBuilder()
        sb.append("package=").append(root.packageName ?: "?").append('\n')
        val rect = Rect()
        var count = 0

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxNodes) return
            val cls = n.className?.toString()?.substringAfterLast('.') ?: "View"
            val txt = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val hint = n.hintText?.toString()?.trim().orEmpty()
            val interesting = txt.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() ||
                n.isClickable || n.isScrollable || n.isEditable || n.isCheckable || n.isFocusable

            if (interesting && n.isVisibleToUser) {
                count++
                n.getBoundsInScreen(rect)
                sb.append('#').append(count).append(' ').append(cls)
                val id = n.viewIdResourceName?.substringAfterLast('/')
                if (!id.isNullOrEmpty()) sb.append(" id=").append(id)
                if (txt.isNotEmpty()) sb.append(" text=\"").append(txt.take(140)).append('"')
                if (desc.isNotEmpty()) sb.append(" desc=\"").append(desc.take(140)).append('"')
                if (hint.isNotEmpty()) sb.append(" hint=\"").append(hint.take(60)).append('"')
                if (n.isClickable) sb.append(" clickable")
                if (n.isLongClickable) sb.append(" longClickable")
                if (n.isScrollable) sb.append(" scrollable")
                if (n.isEditable) sb.append(" editable")
                if (n.isCheckable) sb.append(if (n.isChecked) " checked" else " unchecked")
                if (n.isFocused) sb.append(" focused")
                if (!n.isEnabled) sb.append(" disabled")
                sb.append(" bounds=[")
                    .append(rect.left).append(',').append(rect.top).append(',')
                    .append(rect.right).append(',').append(rect.bottom).append(']')
                sb.append('\n')
            }

            if (depth >= 40) return
            val cc = n.childCount
            for (i in 0 until cc) {
                if (count >= maxNodes) return
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }

        walk(root, 0)
        if (count == 0) sb.append("(没有可交互控件)\n")
        return sb.toString()
    }
}
