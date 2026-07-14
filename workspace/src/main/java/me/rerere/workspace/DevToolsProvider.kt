package me.rerere.workspace

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 开发工具提供者
 *
 * 提供 AI Agent 可直接调用的开发工具函数：
 * - Git 操作 (clone, status, diff, commit, push, pull, branch, log)
 * - 包管理 (install, search, update)
 * - 项目构建 (build, test, lint)
 *
 * 所有操作通过 ExecutionEngine 自动选择最佳引擎执行。
 */
class DevToolsProvider(
    private val executionEngine: ExecutionEngine,
) {
    // ── Git 操作 ─────────────────────────────────────────

    data class GitResult(
        val success: Boolean,
        val output: String,
        val exitCode: Int,
    )

    /**
     * 执行 Git 命令
     */
    fun git(
        workspaceRoot: String,
        args: List<String>,
        cwd: String = "",
        timeoutMillis: Long = 60_000L,
    ): GitResult {
        val gitCmd = "git ${args.joinToString(" ")}"
        val context = WorkspaceShellContext(
            root = workspaceRoot,
            command = gitCmd,
            cwd = cwd,
            // These are filled by the WorkspaceManager, but for direct use:
            filesDir = File("/dev/null"),
            linuxDir = File("/dev/null"),
            tempDir = File("/dev/null"),
            workingDir = File("/dev/null"),
            timeoutMillis = timeoutMillis,
        )
        val engine = executionEngine.resolveEngine(gitCmd, cwd)
        val result = executionEngine.execute(context, engine)
        return GitResult(
            success = result.exitCode == 0,
            output = if (result.stdout.isNotBlank()) result.stdout else result.stderr,
            exitCode = result.exitCode,
        )
    }

    /**
     * Git clone 操作
     */
    fun gitClone(
        workspaceRoot: String,
        url: String,
        directory: String? = null,
        branch: String? = null,
        depth: Int? = null,
        cwd: String = "",
    ): GitResult {
        val args = mutableListOf("clone")
        branch?.let { args.addAll(listOf("--branch", it)) }
        depth?.let { args.addAll(listOf("--depth", it.toString())) }
        args.add(url)
        directory?.let { args.add(it) }
        return git(workspaceRoot, args, cwd, timeoutMillis = 300_000L)
    }

    /**
     * Git status
     */
    fun gitStatus(workspaceRoot: String, cwd: String = ""): GitResult =
        git(workspaceRoot, listOf("status"), cwd)

    /**
     * Git diff
     */
    fun gitDiff(
        workspaceRoot: String,
        staged: Boolean = false,
        path: String? = null,
        cwd: String = "",
    ): GitResult {
        val args = mutableListOf("diff")
        if (staged) args.add("--cached")
        path?.let { args.add("--", it) }
        return git(workspaceRoot, args, cwd)
    }

    /**
     * Git commit
     */
    fun gitCommit(
        workspaceRoot: String,
        message: String,
        all: Boolean = false,
        cwd: String = "",
    ): GitResult {
        val args = mutableListOf("commit")
        if (all) args.add("-a")
        args.addAll(listOf("-m", message))
        return git(workspaceRoot, args, cwd)
    }

    /**
     * Git push
     */
    fun gitPush(
        workspaceRoot: String,
        remote: String = "origin",
        branch: String? = null,
        cwd: String = "",
    ): GitResult {
        val args = mutableListOf("push", remote)
        branch?.let { args.add(it) }
        return git(workspaceRoot, args, cwd, timeoutMillis = 120_000L)
    }

    /**
     * Git log
     */
    fun gitLog(
        workspaceRoot: String,
        maxCount: Int = 10,
        cwd: String = "",
    ): GitResult {
        return git(
            workspaceRoot,
            listOf("log", "--oneline", "--max-count=$maxCount"),
            cwd,
        )
    }

    // ── 包管理 ─────────────────────────────────────────

    data class PkgResult(
        val success: Boolean,
        val output: String,
        val exitCode: Int,
    )

    /**
     * 安装包（自动选择包管理器）
     */
    fun installPackages(
        workspaceRoot: String,
        packages: List<String>,
        manager: String? = null,
        cwd: String = "",
    ): PkgResult {
        val pm = manager ?: detectPackageManager()
        val command = when (pm) {
            "apt" -> "DEBIAN_FRONTEND=noninteractive apt-get install -y ${packages.joinToString(" ")}"
            "apt-get" -> "DEBIAN_FRONTEND=noninteractive apt-get install -y ${packages.joinToString(" ")}"
            "pip" -> "pip install ${packages.joinToString(" ")}"
            "pip3" -> "pip3 install ${packages.joinToString(" ")}"
            "npm" -> "npm install ${packages.joinToString(" ")}"
            "gem" -> "gem install ${packages.joinToString(" ")}"
            else -> "${pm} install ${packages.joinToString(" ")}"
        }
        val result = executeCommand(workspaceRoot, command, cwd, timeoutMillis = 300_000L)
        return PkgResult(
            success = result.exitCode == 0,
            output = if (result.stdout.isNotBlank()) result.stdout else result.stderr,
            exitCode = result.exitCode,
        )
    }

    /**
     * 搜索包
     */
    fun searchPackage(
        workspaceRoot: String,
        query: String,
        manager: String? = null,
        cwd: String = "",
    ): PkgResult {
        val pm = manager ?: detectPackageManager()
        val command = when (pm) {
            "apt" -> "apt-cache search $query"
            "pip", "pip3" -> "${pm} search $query"
            "npm" -> "npm search $query 2>/dev/null | head -20"
            else -> "$pm search $query"
        }
        val result = executeCommand(workspaceRoot, command, cwd)
        return PkgResult(
            success = result.exitCode == 0,
            output = if (result.stdout.isNotBlank()) result.stdout else result.stderr,
            exitCode = result.exitCode,
        )
    }

    // ── 项目构建 ─────────────────────────────────────────

    data class BuildResult(
        val success: Boolean,
        val output: String,
        val buildSystem: String,
        val exitCode: Int,
    )

    /**
     * 检测构建系统并构建项目
     */
    fun buildProject(
        workspaceRoot: String,
        path: String = "",
        commandArgs: String? = null,
        cwd: String = "",
    ): BuildResult {
        val buildSystem = detectBuildSystem(workspaceRoot, path)
        val command = if (commandArgs != null) {
            commandArgs
        } else {
            buildCommandFor(buildSystem)
        }
        val result = executeCommand(
            workspaceRoot, command,
            if (path.isNotBlank()) path else cwd,
            timeoutMillis = 600_000L,
        )
        return BuildResult(
            success = result.exitCode == 0,
            output = if (result.stdout.isNotBlank()) result.stdout else result.stderr,
            buildSystem = buildSystem,
            exitCode = result.exitCode,
        )
    }

    /**
     * 运行测试
     */
    fun runTests(
        workspaceRoot: String,
        path: String = "",
        testFilter: String? = null,
        cwd: String = "",
    ): BuildResult {
        val buildSystem = detectBuildSystem(workspaceRoot, path)
        val baseCmd = when (buildSystem) {
            "gradle" -> "./gradlew test"
            "maven" -> "mvn test"
            "npm" -> "npm test"
            "yarn" -> "yarn test"
            "make" -> "make test"
            "cargo" -> "cargo test"
            else -> throw IllegalArgumentException("Unknown build system: $buildSystem")
        }
        val cmd = if (testFilter != null) "$baseCmd --tests $testFilter" else baseCmd
        val result = executeCommand(workspaceRoot, cmd, cwd, timeoutMillis = 600_000L)
        return BuildResult(
            success = result.exitCode == 0,
            output = if (result.stdout.isNotBlank()) result.stdout else result.stderr,
            buildSystem = buildSystem,
            exitCode = result.exitCode,
        )
    }

    // ── 内部方法 ─────────────────────────────────────────

    private fun executeCommand(
        workspaceRoot: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
    ): WorkspaceCommandResult {
        val engine = executionEngine.resolveEngine(command, cwd)
        val context = WorkspaceShellContext(
            root = workspaceRoot,
            command = command,
            cwd = cwd,
            filesDir = File("/dev/null"),
            linuxDir = File("/dev/null"),
            tempDir = File("/dev/null"),
            workingDir = File("/dev/null"),
            timeoutMillis = timeoutMillis,
        )
        return executionEngine.execute(context, engine)
    }

    private fun detectPackageManager(): String {
        // 按优先级检测可用包管理器
        val candidates = listOf(
            "apt" to "/usr/bin/apt",
            "pip3" to "/usr/bin/pip3",
            "pip" to "/usr/bin/pip",
            "npm" to "/usr/bin/npm",
        )
        for ((name, path) in candidates) {
            if (File(path).isFile) return name
        }
        return "apt"
    }

    private fun detectBuildSystem(workspaceRoot: String, path: String): String {
        val searchDir = if (path.isNotBlank()) File(path) else File(workspaceRoot)
        val markers = listOf(
            "gradle" to listOf("build.gradle.kts", "build.gradle", "gradlew"),
            "maven" to listOf("pom.xml"),
            "npm" to listOf("package.json"),
            "yarn" to listOf("yarn.lock"),
            "make" to listOf("Makefile", "makefile"),
            "cargo" to listOf("Cargo.toml"),
            "cmake" to listOf("CMakeLists.txt"),
        )
        for ((system, files) in markers) {
            if (files.any { File(searchDir, it).isFile }) return system
        }
        return "unknown"
    }

    private fun buildCommandFor(buildSystem: String): String = when (buildSystem) {
        "gradle" -> "./gradlew build 2>&1 || gradle build 2>&1"
        "maven" -> "mvn compile 2>&1"
        "npm" -> "npm run build 2>&1"
        "yarn" -> "yarn build 2>&1"
        "make" -> "make 2>&1"
        "cargo" -> "cargo build 2>&1"
        "cmake" -> "cmake . && make 2>&1"
        else -> "echo 'Unknown build system: $buildSystem'"
    }

    companion object {
        private const val TAG = "DevToolsProvider"
    }
}
