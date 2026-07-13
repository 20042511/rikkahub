package me.rerere.workspace

import java.io.File

/**
 * Termux 原生 Shell Runner
 *
 * 直接执行 Termux 的 ARM64 原生二进制（由 Android NDK 编译，链接 bionic libc），
 * 替代 ProotShellRunner 的 ptrace 方案，消除 2~10x 性能损耗。
 *
 * 已验证（2026-07-13 真机）:
 *   ✅ bash -c "echo hello" → SELinux 不拦截 exec
 *   ✅ apt --version       → apt 2.8.3 原生 ARM64 运行
 *   ✅ apt update          → 网络正常
 *   ✅ apt install -y      → 完整包管理流程
 *
 * @param prefixDir Termux $PREFIX 目录（bootstrap 解压位置）
 */
class TermuxShellRunner(
    private val prefixDir: File,
) : WorkspaceShellRunner {

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!isInstalled()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Termux bootstrap not installed at ${prefixDir.absolutePath}",
                timedOut = false,
                truncated = false,
            )
        }

        val process = ProcessBuilder(
            "${prefixDir}/bin/bash",
            "-l",
            "-c",
            context.command,
        )
            .apply {
                environment().clear()
                buildEnvironment().forEach { (k, v) -> put(k, v) }
                directory(context.workingDir)
                redirectErrorStream(false)
            }
            .start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }

    /** 构建 Termux 兼容环境变量 */
    internal fun buildEnvironment(): Map<String, String> {
        val prefix = prefixDir.absolutePath
        return mapOf(
            "PREFIX" to prefix,
            "HOME" to "${prefixDir.parentFile}/home",
            "LD_LIBRARY_PATH" to "$prefix/lib",
            "PATH" to "$prefix/bin:/system/bin",
            "TMPDIR" to "$prefix/tmp",
            "SHELL" to "$prefix/bin/bash",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "ANDROID_ROOT" to "/system",
            "ANDROID_DATA" to "/data",
        )
    }

    /** 检测 Termux 环境是否已安装 */
    fun isInstalled(): Boolean =
        File(prefixDir, "bin/bash").exists() &&
        File(prefixDir, "bin/bash").canExecute()
}
