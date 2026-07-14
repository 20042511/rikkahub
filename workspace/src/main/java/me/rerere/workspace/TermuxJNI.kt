package com.termux.terminal

import android.util.Log

/**
 * Termux PTY JNI 桥接
 *
 * 对应 C++ 文件: termux_pty.cpp
 * 原生库: libtermux.so
 *
 * 提供通过 PTY (伪终端) 创建和管理子进程的能力，
 * 支持交互式命令行程序（vim, htop, python shell 等）。
 */
object JNI {

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
    fun isAvailable(): Boolean = nativeLoaded

    // ── 原生方法声明 ──────────────────────────────────

    /**
     * 创建 PTY 子进程
     *
     * @param cmd 可执行文件路径
     * @param cwd 工作目录（可为 null）
     * @param args 命令行参数数组（不包含 cmd 本身）
     * @param envVars 环境变量数组（KEY=VALUE 格式）
     * @param processId 长度为 1 的 int 数组，用于接收子进程 PID
     * @param rows PTY 行数
     * @param columns PTY 列数
     * @return PTY master 文件描述符，失败返回 -1
     */
    external fun createSubprocess(
        cmd: String,
        cwd: String?,
        args: Array<String>,
        envVars: Array<String>,
        processId: IntArray,
        rows: Int,
        columns: Int,
    ): Int

    /**
     * 设置 PTY 窗口大小
     */
    external fun setPtyWindowSize(
        fd: Int,
        rows: Int,
        columns: Int,
    )

    /**
     * 等待子进程结束
     *
     * @param pid 子进程 PID
     * @return 退出码，失败返回 -1
     */
    external fun waitFor(pid: Int): Int

    /**
     * 关闭文件描述符
     */
    external fun close(fd: Int)

    /**
     * 向 PTY 文件描述符写入数据
     *
     * @param fd PTY master fd
     * @param data 要写入的字节数组
     * @return 实际写入的字节数，失败返回 -1
     */
    external fun writeFd(fd: Int, data: ByteArray): Int

    /**
     * 从 PTY 文件描述符读取数据
     *
     * @param fd PTY master fd
     * @param buffer 接收缓冲区
     * @return 实际读取的字节数，0 表示无数据，-1 表示错误
     */
    external fun readFd(fd: Int, buffer: ByteArray): Int

    /**
     * 向 PTY 的前台进程组发送信号
     *
     * 支持 SIGINT (Ctrl+C), SIGQUIT (Ctrl+\), SIGTSTP (Ctrl+Z)
     * SIGTERM 和 SIGKILL 请使用 Process.destroy()
     *
     * @param fd PTY master fd
     * @param signal 信号编号
     */
    external fun sendSignal(fd: Int, signal: Int)
}
