package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Engine Compatibility Tests
 *
 * 验证 HostShellRunner 和 ProotShellRunner 在 workspace_shell 场景下
 * 的行为一致性。测试套件覆盖：
 *   - 基础命令执行
 *   - 工作目录隔离
 *   - 超时处理
 *   - 大输出截断
 *   - stdin 管道
 *   - 错误码传播
 *
 * 注入方式：
 *   在 Workflow 中通过 local.properties 设置 RUNNER_UNDER_TEST
 *   → CI 动态决定用哪个 runner 实例化 WorkspaceManager
 */
class EngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ==================== 基础命令 ====================

    @Test
    fun `basic echo command returns correct output`() {
        val result = createManager().executeCommand(
            createWorkspace(),
            "echo 'hello engine'",
        )
        assertEquals(0, result.exitCode)
        assertEquals("hello engine\n", result.stdout)
    }

    @Test
    fun `command exit code propagates correctly`() {
        val result = createManager().executeCommand(
            createWorkspace(),
            "exit 42",
        )
        assertEquals(42, result.exitCode)
    }

    @Test
    fun `pwd reflects working directory`() {
        val root = "pwd-test"
        val manager = createManager()
        val filesDir = manager.filesDir(root)
        val subDir = File(filesDir, "sub/dir").apply { mkdirs() }

        val result = manager.executeCommand(
            root = root,
            command = "pwd",
            cwd = "sub/dir",
        )
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.trimEnd().endsWith("/sub/dir"))
    }

    @Test
    fun `stderr is captured separately`() {
        val result = createManager().executeCommand(
            createWorkspace(),
            "echo stdout && echo stderr >&2",
        )
        assertEquals(0, result.exitCode)
        assertEquals("stdout\n", result.stdout)
        assertEquals("stderr\n", result.stderr)
    }

    // ==================== 超时处理 ====================

    @Test
    fun `command timeout returns exitCode -1`() {
        val result = createManager().executeCommand(
            createWorkspace(),
            "sleep 10",
            timeoutMillis = 500L,
        )
        assertEquals(-1, result.exitCode)
        assertTrue(result.timedOut)
    }

    /**********************************
     * 超时后进程组已被 kill
     * 验证：再执行一条快速命令，不应受残留进程影响
     */
    @Test
    fun `after timeout subsequent commands work`() {
        val manager = createManager()
        val root = createWorkspace()

        manager.executeCommand(root, "sleep 10", timeoutMillis = 500L)

        val result = manager.executeCommand(root, "echo 'still alive'")
        assertEquals(0, result.exitCode)
        assertEquals("still alive\n", result.stdout)
    }

    // ==================== 输出截断 ====================

    @Test
    fun `large output is truncated at limit`() {
        val result = createManager().executeCommand(
            createWorkspace(),
            // 产生 300K 字符
            "awk 'BEGIN { for(i=0; i<300000; i++) printf \"a\" }'",
        )

        assertEquals(0, result.exitCode)
        assertTrue("Output should be truncated", result.truncated)
        assertTrue(
            "Truncated output should not exceed $MAX_OUTPUT_CHARS chars",
            result.stdout.length <= MAX_OUTPUT_CHARS,
        )
        // 截断后应为完整的 limit
        assertEquals(MAX_OUTPUT_CHARS, result.stdout.length)
    }

    // ==================== stdin 管道 ====================

    @Test
    fun `stdin is piped to command`() {
        val manager = createManager()
        val root = createWorkspace()

        val result = manager.executeCommand(
            root = root,
            command = "cat",
            stdin = "hello from stdin".toByteArray(),
        )
        assertEquals(0, result.exitCode)
        assertEquals("hello from stdin", result.stdout)
    }

    // ==================== 文件隔离 ====================

    @Test
    fun `files created in one workspace not visible in another`() {
        val manager = createManager()

        manager.executeCommand("ws-a", "echo 'secret-a' > /workspace/data.txt")

        val result = manager.executeCommand("ws-b", "cat /workspace/data.txt 2>/dev/null || echo 'NOT_FOUND'")
        assertEquals("NOT_FOUND\n", result.stdout)
    }

    // ==================== 环境隔离 ====================

    @Test
    fun `modified PATH does not affect host`() {
        val manager = createManager()
        val root = createWorkspace()

        // 在 workspace 内修改 PATH
        manager.executeCommand(root, "export PATH=/tmp")

        // 宿主机 PATH 应不受影响
        val hostPath = System.getenv("PATH")
        assertNotNull(hostPath)
        assertTrue(hostPath!!.contains("/usr/bin") || hostPath.contains("/bin"))
    }

    // ==================== 组合链式命令 ====================

    @Test
    fun `piped commands work`() {
        val result = createManager().executeCommand(
            createWorkspace(),
            "echo 'line1\nline2\nline3' | wc -l",
        )
        assertEquals(0, result.exitCode)
        assertEquals("3\n", result.stdout)
    }

    // ==================== 帮助方法 ====================

    private fun createManager(): WorkspaceManager {
        val runnerType = System.getProperty("RUNNER_UNDER_TEST", "HostShellRunner")
        val shellRunner: WorkspaceShellRunner = when (runnerType) {
            "ProotShellRunner" -> ProotShellRunner(File(tmp.newFolder(), "native"))
            else -> HostShellRunner()
        }
        return WorkspaceManager(
            baseDir = tmp.newFolder(),
            shellRunner = shellRunner,
        )
    }

    private fun createWorkspace(): String {
        val root = "test-${java.util.UUID.randomUUID().toString().take(8)}"
        createManager().ensureWorkspace(root)
        return root
    }
}
