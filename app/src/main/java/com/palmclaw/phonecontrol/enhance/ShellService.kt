package com.palmclaw.phonecontrol.enhance

/**
 * 跑在 Shizuku（shell/root）特权进程里的 UserService。
 * Shizuku 通过 bindUserService 把这个类加载进特权进程执行 ——
 * 只暴露 exec 一个命令入口，特权面刻意收敛（不开放通用 shell 通道）。
 */
class ShellService : IShell.Stub() {

    override fun exec(cmd: Array<String>): String {
        return try {
            val p = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            "$code\n$out"
        } catch (t: Throwable) {
            "-1\n${t.message ?: "error"}"
        }
    }
}
