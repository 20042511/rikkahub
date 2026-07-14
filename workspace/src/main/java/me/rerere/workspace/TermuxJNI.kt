package com.termux.terminal

import android.util.Log

/**
 * Termux PTY JNI 桥接
 *
 * 对应 C++ 文件: termux_pty.cpp
 * 原生库: libtermux.so
 *
 * ⚠️ 所有 external 方法必须声明为 static（companion object + @JvmStatic），
 * 因为 Termux 终端库 TerminalSession.java 中以静态方式调用，
 * 且 C++ JNI 实现也是 static 模式（第二个参数为 jclass，非 jobject）。
 *
 * 提供通过 PTY (伪终端) 创建和管理子进程的能力，
 * 支持交互式命令行程序（vim, htop, python shell 等）。
 */
class JNI private constructor() {

    companion object {
        private const val TAG = "TermuxJNI"

        private var nativeLoaded = false

        init {
            try {
                System.loadLibrary("termux")
                nativeLoaded = true
                Log.d(TAG, "libtermux.so loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                nativeLoaded = false
                Log.w(TAG, "libtermux.so not available, PTY features disabled", e)
            }
        }

        /** 原生库是否可用 */
        @JvmStatic
        fun isAvailable(): Boolean = nativeLoaded

        // ── 原生方法（必须 static） ────────────────────

        /** 创建 PTY 子进程，返回 master fd，失败返回 -1 */
        @JvmStatic
        external fun createSubprocess(
            cmd: String,
            cwd: String?,
            args: Array<String>,
            envVars: Array<String>,
            processId: IntArray,
            rows: Int,
            columns: Int,
        ): Int

        /** 设置 PTY 窗口大小 */
        @JvmStatic
        external fun setPtyWindowSize(fd: Int, rows: Int, columns: Int)

        /** 等待子进程结束，返回退出码 */
        @JvmStatic
        external fun waitFor(pid: Int): Int

        /** 关闭文件描述符 */
        @JvmStatic
        external fun close(fd: Int)

        /** 向 PTY fd 写入数据，返回写入字节数 */
        @JvmStatic
        external fun writeFd(fd: Int, data: ByteArray): Int

        /** 从 PTY fd 读取数据，返回读取字节数 */
        @JvmStatic
        external fun readFd(fd: Int, buffer: ByteArray): Int

        /** 向 PTY 发送信号（Ctrl+C/Q/Z） */
        @JvmStatic
        external fun sendSignal(fd: Int, signal: Int)
    }
}
