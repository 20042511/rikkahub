package me.rerere.workspace

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * 持久化终端会话管理器
 *
 * 通过 ProcessBuilder 管理持久 shell 会话，保持工作目录和环境变量。
 * PTY 支持可通过后续集成 termux 原生库实现。
 */
class TerminalSessionManager {

    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    /**
     * 创建持久终端会话
     */
    fun createSession(
        sessionId: String = "session_${System.nanoTime()}",
        cwd: String? = null,
        env: Map<String, String> = emptyMap(),
    ): TerminalSession {
        // 关闭已存在的同名会话
        closeSession(sessionId)

        val shell = guessShell()
        val workDir = cwd?.let { File(it) } ?: File("/")

        val process = ProcessBuilder(shell, "-l")
            .directory(if (workDir.isDirectory) workDir else File("/"))
            .apply {
                environment().apply {
                    put("TERM", "xterm-256color")
                    put("LANG", "C.UTF-8")
                    put("LC_ALL", "C.UTF-8")
                    put("HOME", workDir.absolutePath)
                    put("SHELL", shell)
                    put("TMPDIR", "/tmp")
                    putAll(env)
                }
            }
            .redirectErrorStream(true)
            .start()

        val session = TerminalSession(
            id = sessionId,
            process = process,
            stdin = process.outputStream,
            stdout = process.inputStream,
            cwd = workDir.absolutePath,
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
        )
        sessions[sessionId] = session
        Log.d(TAG, "Created session: $sessionId, shell=$shell, cwd=${workDir.absolutePath}")
        return session
    }

    /**
     * 向会话写入命令（stdin）
     */
    fun writeToSession(sessionId: String, input: String): Boolean {
        val session = sessions[sessionId] ?: return false
        return try {
            session.stdin.write((input + "\n").toByteArray(Charsets.UTF_8))
            session.stdin.flush()
            session.lastActiveAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeToSession failed: $sessionId", e)
            closeSession(sessionId)
            false
        }
    }

    /**
     * 从会话读取输出（非阻塞）
     */
    fun readFromSession(sessionId: String, maxBytes: Int = 4096): ByteArray? {
        val session = sessions[sessionId] ?: return null
        return try {
            val available = session.stdout.available()
            if (available <= 0) return null
            val bytes = ByteArray(minOf(available, maxBytes))
            val read = session.stdout.read(bytes)
            if (read <= 0) return null
            session.lastActiveAt = System.currentTimeMillis()
            bytes.copyOf(read)
        } catch (e: Exception) {
            Log.e(TAG, "readFromSession failed: $sessionId", e)
            closeSession(sessionId)
            null
        }
    }

    /**
     * 在会话中执行命令并返回流式结果
     */
    fun executeInSession(
        sessionId: String,
        command: String,
        timeoutSeconds: Long = 30L,
    ): Flow<SessionOutputChunk> = flow {
        val session = sessions[sessionId]
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        // 写入命令
        writeToSession(sessionId, command)

        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000L

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > timeoutMs) {
                emit(SessionOutputChunk.Timeout(timeoutSeconds))
                break
            }

            val data = readFromSession(sessionId, 4096)
            if (data == null || data.isEmpty()) {
                kotlinx.coroutines.delay(50)
                continue
            }

            val text = data.toString(Charsets.UTF_8)
            emit(SessionOutputChunk.Stdout(text))

            // 检测提示符（简单实现）
            if (text.contains("$ ") || text.contains("# ") || text.contains("❯ ")) {
                kotlinx.coroutines.delay(100)
                // 再看一次有没有额外数据
                val extra = readFromSession(sessionId, 4096)
                if (extra != null) {
                    emit(SessionOutputChunk.Stdout(extra.toString(Charsets.UTF_8)))
                }
                // 检查进程是否还活着
                if (!session.process.isAlive) {
                    emit(SessionOutputChunk.ExitCode(session.process.exitValue()))
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
            when (signal) {
                Signal.SIGINT -> {
                    // 对 Process 发送 Ctrl+C 通过写入 stdin 实现
                    session.stdin.write(0x03) // Ctrl+C
                    session.stdin.flush()
                }
                Signal.SIGTERM -> session.process.destroy()
                Signal.SIGKILL -> session.process.destroyForcibly()
                Signal.SIGQUIT -> {
                    session.stdin.write(0x1C) // Ctrl+\
                    session.stdin.flush()
                }
                Signal.SIGTSTP -> {
                    session.stdin.write(0x1A) // Ctrl+Z
                    session.stdin.flush()
                }
                Signal.SIGCONT -> {
                    // 通过发送 SIGCONT 信号恢复进程 - 在 Process API 中无法直接发送信号
                    // 这里使用 fg 命令恢复
                    session.stdin.write("fg\n".toByteArray(Charsets.UTF_8))
                    session.stdin.flush()
                }
            }
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
            session.process.destroyForcibly()
            session.stdin.close()
            session.stdout.close()
        } catch (e: Exception) {
            Log.w(TAG, "closeSession error: $sessionId", e)
        }
        Log.d(TAG, "Closed session: $sessionId")
    }

    /**
     * 获取会话信息
     */
    fun getSession(sessionId: String): TerminalSession? = sessions[sessionId]

    /**
     * 列出所有活跃会话
     */
    fun listSessions(): List<TerminalSessionInfo> = sessions.values.map { it.toInfo() }

    /**
     * 清理超时会话
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
        val ids = sessions.keys.toList()
        ids.forEach { closeSession(it) }
    }

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

    companion object {
        private const val TAG = "TerminalSessionManager"
    }
}

/**
 * 终端会话
 */
data class TerminalSession(
    val id: String,
    val process: Process,
    val stdin: OutputStream,
    val stdout: InputStream,
    val cwd: String,
    val createdAt: Long,
    val lastActiveAt: Long,
) {
    fun toInfo() = TerminalSessionInfo(
        id = id,
        cwd = cwd,
        isAlive = process.isAlive,
        createdAt = createdAt,
        lastActiveAt = lastActiveAt,
    )
}

/**
 * 终端会话信息（不含 Process 引用）
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
