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
 * 通过 WorkspaceManager 获取正确的 workspace 目录路径。
 */
class DevToolsProvider(
    private val executionEngine: ExecutionEngine,
    private val workspaceManager: WorkspaceManager,
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
        val context = buildContext(workspaceRoot, gitCmd, cwd, timeoutMillis)
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
        if (path != null) { args.add("--"); args.add(path) }
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
        // shellQuote 确保包含空格的消息不被 shell 拆分
        args.addAll(listOf("-m", message))
        // 用 -m 传消息时 git 本身不依赖 shell 分词，但命令拼接
        // 时 args.joinToString(" ") 会把 "initial commit" 变成两个参数。
        // 解决方案：直接构造 shell 安全的命令字符串
        val quotedMessage = "'" + message.replace("'", "'\\''") + "'"
        val gitCmd = "git commit ${if (all) "-a " else ""}-m $quotedMessage"
        return executeGit(workspaceRoot, gitCmd, cwd)
    }

    /** 执行 git 命令并返回结果 */
    private fun executeGit(
        workspaceRoot: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = 60_000L,
    ): GitResult {
        val context = buildContext(workspaceRoot, command, cwd, timeoutMillis)
        val engine = executionEngine.resolveEngine(command, cwd)
        val result = executionEngine.execute(context, engine)
        return GitResult(
            success = result.exitCode == 0,
            output = if (result.stdout.isNotBlank()) result.stdout else result.stderr,
            exitCode = result.exitCode,
        )
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
        val pm = manager ?: "apt"
        val pkgs = packages.joinToString(" ")
        val command = when (pm) {
            "apt" -> "DEBIAN_FRONTEND=noninteractive apt install -y $pkgs"
            "apt-get" -> "DEBIAN_FRONTEND=noninteractive apt install -y $pkgs"
            "pip" -> "pip install $pkgs"
            "pip3" -> "pip3 install $pkgs"
            "npm" -> "npm install $pkgs"
            "gem" -> "gem install $pkgs"
            else -> "$pm install $pkgs"
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
        val buildSystem = if (commandArgs != null) "custom" else detectBuildSystemProot(workspaceRoot, path)
        val command = commandArgs ?: buildCommandFor(buildSystem)
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
        val buildSystem = detectBuildSystemProot(workspaceRoot, path)
        val baseCmd = when (buildSystem) {
            "gradle" -> "./gradlew test 2>&1"
            "maven" -> "mvn test 2>&1"
            "npm" -> "npm test 2>&1"
            "yarn" -> "yarn test 2>&1"
            "make" -> "make test 2>&1"
            "cargo" -> "cargo test 2>&1"
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

    /** 构建正确的 WorkspaceShellContext */
    private fun buildContext(
        workspaceRoot: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
    ): WorkspaceShellContext {
        val filesDir = workspaceManager.filesDir(workspaceRoot)
        val linuxDir = workspaceManager.linuxDir(workspaceRoot)
        val tempDir = workspaceManager.tempDir(workspaceRoot)
        val workingDir = if (cwd.isNotBlank()) {
            File(filesDir, cwd).also { it.mkdirs() }
        } else {
            filesDir
        }
        return WorkspaceShellContext(
            root = workspaceRoot,
            command = command,
            cwd = cwd,
            filesDir = filesDir,
            linuxDir = linuxDir,
            tempDir = tempDir,
            workingDir = workingDir,
            timeoutMillis = timeoutMillis,
        )
    }

    private fun executeCommand(
        workspaceRoot: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
    ): WorkspaceCommandResult {
        val context = buildContext(workspaceRoot, command, cwd, timeoutMillis)
        val engine = executionEngine.resolveEngine(command, cwd)
        return executionEngine.execute(context, engine)
    }

    /**
     * 在 proot 环境中检测构建系统（通过查找特征文件）
     */
    private fun detectBuildSystemProot(workspaceRoot: String, path: String): String {
        val searchPath = if (path.isNotBlank()) path else "/workspace"
        // 通过 PROOT 引擎执行 ls 检查构建文件是否存在
        val checks = listOf(
            "gradle" to listOf("build.gradle.kts", "build.gradle", "gradlew"),
            "maven" to listOf("pom.xml"),
            "npm" to listOf("package.json"),
            "yarn" to listOf("yarn.lock"),
            "make" to listOf("Makefile", "makefile", "GNUmakefile"),
            "cargo" to listOf("Cargo.toml"),
            "cmake" to listOf("CMakeLists.txt"),
        )
        for ((system, markers) in checks) {
            for (marker in markers) {
                val testCmd = "test -f \"$searchPath/$marker\" && echo 'FOUND' || true"
                val context = buildContext(workspaceRoot, testCmd, "", 10_000L)
                val result = executionEngine.execute(context, EngineType.PROOT)
                if (result.stdout.trim() == "FOUND") return system
            }
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
        "custom" -> ""
        else -> "echo 'Unknown build system: $buildSystem'"
    }

    companion object {
        private const val TAG = "DevToolsProvider"
    }
}
