package me.rerere.workspace

import android.util.Log
import com.termux.terminal.JNI as TermuxJNI
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 持久化终端会话管理器
 *
 * 支持两种模式：
 * 1. PTY 模式（默认）— 通过 libtermux.so JNI 创建伪终端，
 *    支持交互式程序（vim, python shell, htop 等），信号发送
 * 2. ProcessBuilder 模式（回退）— 当原生库不可用时，
 *    使用标准 ProcessBuilder 创建管道式进程
 *
 * 自动检测并选择可用模式，对 API 使用者透明。
 *
 * ⚠️ 线程安全：每个会话有独立的读写锁，防止多个工具调用交叉读写同一会话。
 */
class TerminalSessionManager {

    /** PTY 模式是否可用 */
    private val ptyAvailable: Boolean = TermuxJNI.isAvailable()

    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    /** 每个会话的读写锁，防止并行工具调用交叉读写同一会话 */
    private val sessionLocks = ConcurrentHashMap<String, ReentrantLock>()

    init {
        Log.d(TAG, "TerminalSessionManager initialized, PTY available: $ptyAvailable")
    }

    // ── 会话生命周期 ─────────────────────────────────

    /**
     * 创建持久终端会话
     */
    fun createSession(
        sessionId: String = "session_${System.nanoTime()}",
        cwd: String? = null,
        env: Map<String, String> = emptyMap(),
        rows: Int = 40,
        columns: Int = 120,
    ): TerminalSession {
        closeSession(sessionId)

        return if (ptyAvailable) {
            createPtySession(sessionId, cwd, env, rows, columns)
        } else {
            createProcessSession(sessionId, cwd, env)
        }
    }

    /**
     * 向会话写入命令（线程安全）
     */
    fun writeToSession(sessionId: String, input: String): Boolean {
        val session = sessions[sessionId] ?: return false
        val lock = sessionLocks.getOrPut(sessionId) { ReentrantLock() }
        lock.lock()
        try {
            session.write(input.toByteArray(Charsets.UTF_8))
            session.lastActiveAt = System.currentTimeMillis()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "writeToSession failed: $sessionId", e)
            closeSession(sessionId)
            return false
        } finally {
            lock.unlock()
        }
    }

    /**
     * 从会话读取输出（非阻塞，线程安全）
     */
    fun readFromSession(sessionId: String, maxBytes: Int = 4096): ByteArray? {
        val session = sessions[sessionId] ?: return null
        val lock = sessionLocks.getOrPut(sessionId) { ReentrantLock() }
        lock.lock()
        try {
            val data = session.read(maxBytes)
            if (data != null) {
                session.lastActiveAt = System.currentTimeMillis()
            }
            return data
        } catch (e: Exception) {
            Log.e(TAG, "readFromSession failed: $sessionId", e)
            closeSession(sessionId)
            return null
        } finally {
            lock.unlock()
        }
    }

    /**
     * 在会话中执行命令并返回流式结果
     *
     * @param sessionId 会话 ID
     * @param command 要执行的命令
     * @param timeoutSeconds 超时秒数（默认 30）
     * @param onOutput 可选：实时输出回调
     * @return Flow 输出流
     */
    fun executeInSession(
        sessionId: String,
        command: String,
        timeoutSeconds: Long = 30L,
        onOutput: ((String) -> Unit)? = null,
    ): Flow<SessionOutputChunk> = flow {
        val session = sessions[sessionId]
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        // 写入命令（带换行）
        val cmdBytes = "$command\n".toByteArray(Charsets.UTF_8)
        session.write(cmdBytes)

        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000L
        val outputBuffer = StringBuilder()
        var foundPrompt = false

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > timeoutMs) {
                emit(SessionOutputChunk.Timeout(timeoutSeconds))
                break
            }

            val data = session.read(4096)
            if (data == null || data.isEmpty()) {
                delay(50)
                continue
            }

            val text = data.toString(Charsets.UTF_8)
            outputBuffer.append(text)
            emit(SessionOutputChunk.Stdout(text))
            onOutput?.invoke(text)

            // 输出截断保护（128KB）
            if (outputBuffer.length > 128 * 1024) {
                emit(SessionOutputChunk.Stdout("\n[Output truncated at 128KB]"))
                break
            }

            // 检测命令提示符（简单检测）
            if (!foundPrompt && (text.contains("$ ") || text.contains("# ") || text.contains("❯ "))) {
                foundPrompt = true
                // 等待 200ms 确保完整输出
                delay(200)
                val extra = session.read(4096)
                if (extra != null && extra.isNotEmpty()) {
                    val extraText = extra.toString(Charsets.UTF_8)
                    outputBuffer.append(extraText)
                    emit(SessionOutputChunk.Stdout(extraText))
                    onOutput?.invoke(extraText)
                }

                // 检查进程状态
                if (!session.isAlive()) {
                    emit(SessionOutputChunk.ExitCode(session.exitCode()))
                } else {
                    emit(SessionOutputChunk.ExitCode(0))
                }
                break
            }
        }
    }

    /**
     * 发送信号到会话进程
     */
    fun sendSignal(sessionId: String, signal: Signal): Boolean {
        val session = sessions[sessionId] ?: return false
        return try {
            session.sendSignal(signal)
            session.lastActiveAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendSignal failed: $sessionId", e)
            false
        }
    }

    /**
     * 关闭会话
     */
    fun closeSession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "closeSession error: $sessionId", e)
        }
        Log.d(TAG, "Closed session: $sessionId")
    }

    /**
     * 获取会话
     */
    fun getSession(sessionId: String): TerminalSession? = sessions[sessionId]

    /**
     * 列出所有活跃会话
     */
    fun listSessions(): List<TerminalSessionInfo> =
        sessions.values.map { it.toInfo() }

    /**
     * 清理超时空闲会话
     */
    fun cleanupIdleSessions(maxIdleMs: Long = 15 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        val toRemove = sessions.values
            .filter { now - it.lastActiveAt > maxIdleMs }
            .map { it.id }
        toRemove.forEach { closeSession(it) }
        if (toRemove.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${toRemove.size} idle sessions")
        }
    }

    /**
     * 关闭所有会话
     */
    fun closeAllSessions() {
        sessions.keys.toList().forEach { closeSession(it) }
    }

    // ── PTY 模式 ─────────────────────────────────────

    private fun createPtySession(
        sessionId: String,
        cwd: String?,
        env: Map<String, String>,
        rows: Int,
        columns: Int,
    ): TerminalSession {
        val shell = guessShell()
        val workDir = cwd ?: "/workspace"

        val envList = mutableListOf(
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "HOME=$workDir",
            "TMPDIR=/tmp",
            "SHELL=$shell",
        )
        env.forEach { (k, v) -> envList.add("$k=$v") }

        val pidArray = IntArray(1)
        val masterFd = TermuxJNI.createSubprocess(
            cmd = shell,
            cwd = workDir,
            args = arrayOf("-l"),
            envVars = envList.toTypedArray(),
            processId = pidArray,
            rows = rows,
            columns = columns,
        )

        if (masterFd < 0) {
            throw RuntimeException("Failed to create PTY subprocess (fd=$masterFd)")
        }

        val pid = pidArray[0]
        Log.d(TAG, "PTY session created: id=$sessionId, shell=$shell, pid=$pid, fd=$masterFd")

        return PtyTerminalSession(
            id = sessionId,
            masterFd = masterFd,
            pid = pid,
            cwd = workDir,
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
        ).also { sessions[sessionId] = it }
    }

    // ── ProcessBuilder 模式（回退） ──────────────────

    private fun createProcessSession(
        sessionId: String,
        cwd: String?,
        env: Map<String, String>,
    ): TerminalSession {
        val shell = guessShell()
        val workDir = cwd?.let { File(it) } ?: File("/workspace")

        val pb = ProcessBuilder(shell, "-l")
            .directory(if (workDir.isDirectory) workDir else File("/"))
            .redirectErrorStream(true)

        pb.environment().apply {
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
            put("LC_ALL", "C.UTF-8")
            put("HOME", workDir.absolutePath)
            put("SHELL", shell)
            put("TMPDIR", "/tmp")
            putAll(env)
        }

        val process = pb.start()
        Log.d(TAG, "Process session created: id=$sessionId, shell=$shell")

        return ProcessTerminalSession(
            id = sessionId,
            process = process,
            stdin = process.outputStream,
            stdout = process.inputStream,
            cwd = workDir.absolutePath,
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
        ).also { sessions[sessionId] = it }
    }

    companion object {
        private const val TAG = "TerminalSessionManager"

        private fun guessShell(): String {
            val shells = listOf(
                "/data/data/com.termux/files/usr/bin/bash",
                "/system/bin/bash",
                "/system/bin/sh",
            )
            for (shell in shells) {
                if (File(shell).isFile) return shell
            }
            return "/system/bin/sh"
        }
    }
}

// ── 会话接口与实现 ─────────────────────────────────

/**
 * 终端会话抽象接口
 */
sealed class TerminalSession {
    abstract val id: String
    abstract val cwd: String
    abstract val createdAt: Long
    abstract var lastActiveAt: Long

    /** 向会话写入数据 */
    abstract fun write(data: ByteArray)

    /** 从会话读取数据（非阻塞） */
    abstract fun read(maxBytes: Int): ByteArray?

    /** 发送信号 */
    abstract fun sendSignal(signal: Signal)

    /** 进程是否存活 */
    abstract fun isAlive(): Boolean

    /** 获取退出码 */
    abstract fun exitCode(): Int

    /** 关闭会话 */
    abstract fun close()

    fun toInfo() = TerminalSessionInfo(
        id = id,
        cwd = cwd,
        isAlive = isAlive(),
        createdAt = createdAt,
        lastActiveAt = lastActiveAt,
    )
}

/**
 * PTY 终端会话（通过 libtermux.so JNI 实现）
 */
class PtyTerminalSession(
    override val id: String,
    val masterFd: Int,
    val pid: Int,
    override val cwd: String,
    override val createdAt: Long,
    override var lastActiveAt: Long,
) : TerminalSession() {

    override fun write(data: ByteArray) {
        TermuxJNI.writeFd(masterFd, data)
    }

    override fun read(maxBytes: Int): ByteArray? {
        val buffer = ByteArray(maxBytes)
        val read = TermuxJNI.readFd(masterFd, buffer)
        if (read <= 0) return null
        return buffer.copyOf(read)
    }

    override fun sendSignal(signal: Signal) {
        TermuxJNI.sendSignal(masterFd, signal.value)
    }

    override fun isAlive(): Boolean {
        // 通过 waitpid WNOHANG 检查
        val rc = TermuxJNI.waitFor(pid)
        return rc == -1 // -1 表示子进程状态未改变（仍在运行）
    }

    override fun exitCode(): Int {
        val rc = TermuxJNI.waitFor(pid)
        return if (rc >= 0) rc else -1
    }

    override fun close() {
        TermuxJNI.close(masterFd)
    }
}

/**
 * ProcessBuilder 终端会话（回退模式）
 */
class ProcessTerminalSession(
    override val id: String,
    val process: Process,
    val stdin: OutputStream,
    val stdout: InputStream,
    override val cwd: String,
    override val createdAt: Long,
    override var lastActiveAt: Long,
) : TerminalSession() {

    override fun write(data: ByteArray) {
        stdin.write(data)
        stdin.flush()
    }

    override fun read(maxBytes: Int): ByteArray? {
        val available = stdout.available()
        if (available <= 0) return null
        val bytes = ByteArray(minOf(available, maxBytes))
        val read = stdout.read(bytes)
        return if (read <= 0) null else bytes.copyOf(read)
    }

    override fun sendSignal(signal: Signal) {
        when (signal) {
            Signal.SIGINT -> {
                stdin.write(0x03) // Ctrl+C
                stdin.flush()
            }
            Signal.SIGTERM -> process.destroy()
            Signal.SIGKILL -> process.destroyForcibly()
            Signal.SIGQUIT -> {
                stdin.write(0x1C) // Ctrl+\
                stdin.flush()
            }
            Signal.SIGTSTP -> {
                stdin.write(0x1A) // Ctrl+Z
                stdin.flush()
            }
            Signal.SIGCONT -> {
                stdin.write("fg\n".toByteArray(Charsets.UTF_8))
                stdin.flush()
            }
        }
    }

    override fun isAlive(): Boolean = process.isAlive

    override fun exitCode(): Int = process.exitValue()

    override fun close() {
        process.destroyForcibly()
        stdin.close()
        stdout.close()
    }
}

// ── 数据类 ─────────────────────────────────────────

/**
 * 终端会话信息（不含 Process 引用，可安全序列化）
 */
data class TerminalSessionInfo(
    val id: String,
    val cwd: String,
    val isAlive: Boolean,
    val createdAt: Long,
    val lastActiveAt: Long,
) {
    val ageSeconds: Long get() = (System.currentTimeMillis() - createdAt) / 1000
    val idleSeconds: Long get() = (System.currentTimeMillis() - lastActiveAt) / 1000
}

/**
 * 会话输出块
 */
sealed class SessionOutputChunk {
    data class Stdout(val text: String) : SessionOutputChunk()
    data class ExitCode(val code: Int) : SessionOutputChunk()
    data class Timeout(val seconds: Long) : SessionOutputChunk()
}

/**
 * 信号
 */
enum class Signal(val value: Int) {
    SIGINT(2),
    SIGTERM(15),
    SIGKILL(9),
    SIGQUIT(3),
    SIGTSTP(20),
    SIGCONT(18),
}
