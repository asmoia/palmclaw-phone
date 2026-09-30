package com.palmclaw.phonecontrol.enhance

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.palmclaw.phonecontrol.ApkMcpApp
import com.palmclaw.phonecontrol.core.Logs
import rikka.shizuku.Shizuku

/**
 * Shizuku 增强模式（可选）。
 *
 * Shizuku 以 shell（adb）或 root 权限运行一个服务，本应用经用户授权后可以借用其权限。
 * 这里只用它做一件事：用 `am start` 启动 Activity —— shell uid 不受「后台启动 Activity」
 * 限制（Android 10+ 的 BAL 限制只约束普通应用），MIUI / HyperOS / EMUI 的私有限制也拦不住。
 *
 * 实现说明：Shizuku 13.x 已把 newProcess 转为私有 API，这里按官方推荐走 UserService ——
 * 把一个极小的 IShell 服务（IShell.aidl / ShellService.kt）加载进 Shizuku 的特权进程，
 * 只暴露 exec 一个命令入口，argv 直传、不经 shell 拼接，没有注入面。
 *
 * 所有方法都静默降级 —— Shizuku 未安装 / 未运行 / 未授权时一律返回不可用，
 * 调用方（ToolRegistry）自动回退常规方式。
 */
object ShizukuEnhance {

    /** Shizuku App 的包名 */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private const val PERMISSION_REQUEST_CODE = 10086

    /** 增强模式状态，供 UI / get_status 展示 */
    enum class State { NOT_INSTALLED, NOT_RUNNING, NEED_PERMISSION, READY }

    /** 已连接的特权 shell 服务（跑在 Shizuku 进程里） */
    @Volatile private var shell: IShell? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shell = IShell.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
        }
    }

    private fun serviceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(ApkMcpApp.appContext.packageName, ShellService::class.java.name)
        )
            .daemon(false)
            .version(1)
            .processNameSuffix("apkmcp-shell")

    /** Shizuku App 是否已安装 */
    fun installed(): Boolean = try {
        ApkMcpApp.appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) != null
    } catch (_: Throwable) {
        false
    }

    /** binder 是否存活（服务在跑）。未运行时调用 Shizuku 方法会抛 IllegalStateException，务必先判 */
    fun alive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** 用户是否已授权本应用（服务没跑时视为未授权） */
    fun granted(): Boolean = try {
        alive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** 增强能力是否可用：装了 + 跑着 + 授权了 */
    fun available(): Boolean = granted()

    fun state(): State = when {
        !installed() -> State.NOT_INSTALLED
        !alive() -> State.NOT_RUNNING
        !granted() -> State.NEED_PERMISSION
        else -> State.READY
    }

    /** 给人/AI 看的一行状态描述 */
    fun statusText(): String = when (state()) {
        State.NOT_INSTALLED -> "未安装（装好并授权后 launch_app/open_url 不再被后台启动限制拦截）"
        State.NOT_RUNNING -> "已安装，服务未运行（去 Shizuku App 启动服务）"
        State.NEED_PERMISSION -> "服务运行中，但未授权本应用"
        State.READY -> "已就绪，launch_app/open_url 走特权通道"
    }

    /** 弹出 Shizuku 的授权对话框（服务须在运行） */
    fun requestPermission() {
        try {
            if (alive() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (t: Throwable) {
            Logs.add("请求 Shizuku 授权失败: ${t.message}")
        }
    }

    /** 拿到特权 shell 服务。首次绑定是异步的，最多等 5 秒回调。 */
    private fun obtainShell(): IShell? {
        shell?.let { return it }
        return try {
            Shizuku.bindUserService(serviceArgs(), connection)
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                shell?.let { return it }
                try {
                    Thread.sleep(60)
                } catch (_: InterruptedException) {
                    return shell
                }
            }
            shell
        } catch (t: Throwable) {
            Logs.add("绑定 Shizuku UserService 失败: ${t.message}")
            null
        }
    }

    /**
     * 以 shell/root 权限执行一条命令。
     * 返回 (退出码是否为 0, 合并输出)。
     */
    private fun exec(cmd: Array<String>): Pair<Boolean, String> {
        return try {
            if (!available()) return Pair(false, "Shizuku 不可用")
            val s = obtainShell() ?: return Pair(false, "Shizuku UserService 未连接")
            val res = s.exec(cmd)
            val idx = res.indexOf('\n')
            val code = if (idx >= 0) res.substring(0, idx).trim().toIntOrNull() ?: -1 else -1
            val out = if (idx >= 0) res.substring(idx + 1) else ""
            Pair(code == 0, out.trim())
        } catch (t: Throwable) {
            Logs.add("Shizuku 执行失败: ${t.message}")
            Pair(false, t.message ?: "unknown")
        }
    }

    /**
     * 用 am start 启动一个已解析出组件的 Intent（如 getLaunchIntentForPackage 的结果）。
     * shell uid 发起的启动不受「后台启动 Activity」限制，MIUI/EMUI 的私有限制同样无效。
     * timeout 8 兜底：万一 am 卡死，工具调用不会永远挂起。
     */
    fun startIntent(intent: Intent): Pair<Boolean, String> {
        val comp = intent.component
            ?: return Pair(false, "Intent 没有 component，无法用 am start")
        return exec(
            arrayOf("timeout", "8", "am", "start", "-n", "${comp.packageName}/${comp.className}")
        )
    }

    /** 用 am start 打开网址 */
    fun openUrl(url: String): Pair<Boolean, String> =
        exec(arrayOf("timeout", "8", "am", "start", "-a", "android.intent.action.VIEW", "-d", url))
}
