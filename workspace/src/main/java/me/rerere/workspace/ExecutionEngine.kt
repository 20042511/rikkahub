package me.rerere.workspace

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 执行引擎类型
 */
enum class EngineType {
    /** 在 Android 宿主上直接执行 (bionic libc, 原生速度) */
    HOST,
    /** 通过 proot 在 rootfs 中执行 (glibc, 兼容性优先) */
    PROOT,
}

/**
 * 执行策略：自动选择最佳引擎
 *
 * HOST 引擎：直接通过 ProcessBuilder 执行，不走 proot，性能最佳
 * PROOT 引擎：通过 proot 进入 rootfs 执行，兼容 glibc 工具
 *
 * 对于大多数开发工具（git, python, node, gcc），走 HOST 引擎更快；
 * 只有需要 glibc 的工具（如某些闭源二进制、apt）才走 PROOT 引擎。
 */
class ExecutionEngine(
    private val prootRunner: ProotShellRunner,
    private val hostRunner: HostShellRunner,
) {
    /** 需要 PROOT 引擎的工具前缀 */
    private val prootOnlyCommands = setOf(
        "apt", "apt-get", "dpkg", "apt-cache",
    )

    /** 需要 PROOT 引擎的工具（如果存在宿主版本则优先用 HOST） */
    private val prootPreferredCommands = setOf(
        // 编译/构建工具（通常在 proot rootfs 中，宿主没有）
        "git", "gcc", "g++", "make", "cmake", "cc", "c++",
        "java", "javac", "jar", "mvn", "mvnw", "gradle", "gradlew",
        "python", "python3", "pip", "pip3",
        "node", "npm", "npx", "yarn", "pnpm",
        "rustc", "cargo", "go",
        "gem", "bundle", "rake",
        "php", "composer",
        "perl",
        "swift",
        "dart", "flutter",
    )

    /**
     * 判断命令应该使用哪个引擎
     */
    fun resolveEngine(command: String, cwd: String = ""): EngineType {
        val cmdName = command.trim().split("\\s+".toRegex()).first()
            .removePrefix("/").removePrefix("./")

        // 明确需要 proot 的命令
        if (cmdName in prootOnlyCommands) return EngineType.PROOT

        // proot 优先的命令，检查宿主是否存在
        if (cmdName in prootPreferredCommands) {
            if (!isHostToolAvailable(cmdName)) return EngineType.PROOT
        }

        return EngineType.HOST
    }

    /**
     * 根据引擎类型执行命令
     */
    fun execute(context: WorkspaceShellContext, engineType: EngineType): WorkspaceCommandResult {
        return when (engineType) {
            EngineType.HOST -> hostRunner.execute(context)
            EngineType.PROOT -> prootRunner.execute(context)
        }
    }

    /**
     * 自动选择并执行（对外主要入口）
     */
    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val engine = resolveEngine(context.command, context.cwd)
        Log.d(TAG, "execute: engine=$engine, command=${context.command.take(100)}")
        return execute(context, engine)
    }

    companion object {
        private const val TAG = "ExecutionEngine"

        private fun isHostToolAvailable(name: String): Boolean {
            return try {
                val process = ProcessBuilder(
                    "/system/bin/sh", "-c", "command -v $name"
                ).start()
                process.waitFor(3, TimeUnit.SECONDS)
                process.exitValue() == 0
            } catch (e: Exception) {
                false
            }
        }
    }
}
