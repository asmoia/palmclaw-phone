// IShell.aidl —— 跑在 Shizuku（shell/root）进程里的最小命令入口
package com.palmclaw.phonecontrol.enhance;

interface IShell {
    /**
     * 执行一条命令（argv 直传）。
     * 返回格式："退出码\n合并输出"
     */
    String exec(in String[] cmd);
}
