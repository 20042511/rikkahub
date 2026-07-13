package me.rerere.workspace

import java.io.File

/**
 * 智能派发 Shell Runner
 *
 * 优先级：Termux Native > Proot > Host
 * 自动检测可用运行环境，无需手动切换。
 *
 * @param termuxRunner Termux 原生 runner
 * @param prootRunner  Proot runner（回退）
 * @param hostRunner   Host runner（兜底）
 * @param workspacesBase WorkspaceManager 的 baseDir（用于检测 proot rootfs）
 */
class DelegatingShellRunner(
    private val termuxRunner: TermuxShellRunner,
    private val prootRunner: WorkspaceShellRunner,
    private val hostRunner: WorkspaceShellRunner,
    private val workspacesBase: File,
) : WorkspaceShellRunner {

    @Volatile
    private var cachedMode: RuntimeMode? = null

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        return activeRunner().execute(context)
    }

    private fun activeRunner(): WorkspaceShellRunner = when (mode()) {
        RuntimeMode.TERMUX_NATIVE -> termuxRunner
        RuntimeMode.LEGACY_PROOT -> prootRunner
        RuntimeMode.HOST_FALLBACK -> hostRunner
    }

    private fun mode(): RuntimeMode {
        if (cachedMode != null) return cachedMode!!
        return detectMode().also { cachedMode = it }
    }

    private fun detectMode(): RuntimeMode = when {
        termuxRunner.isInstalled() -> RuntimeMode.TERMUX_NATIVE
        hasProotRootfs() -> RuntimeMode.LEGACY_PROOT
        else -> RuntimeMode.HOST_FALLBACK
    }

    /** 遍历 workspace 目录，检测是否有 proot rootfs */
    private fun hasProotRootfs(): Boolean {
        if (!workspacesBase.exists()) return false
        return workspacesBase.listFiles()?.any { dir ->
            dir.isDirectory &&
            dir.name.matches(Regex("[A-Za-z0-9._-]+")) &&
            File(dir, "linux/bin/sh").exists()
        } ?: false
    }

    /** 强制重新检测（安装 Termux bootstrap 后调用） */
    fun refresh() { cachedMode = null }

    enum class RuntimeMode {
        TERMUX_NATIVE,
        LEGACY_PROOT,
        HOST_FALLBACK,
    }
}
