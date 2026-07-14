package me.rerere.rikkahub.data.ai.tools.dev

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.Signal
import me.rerere.workspace.TerminalSessionManager

/**
 * AI 终端会话工具
 *
 * 提供持久化的终端会话管理能力：
 * - terminal_session_create: 创建持久会话
 * - terminal_session_exec: 在会话中执行命令
 * - terminal_session_signal: 发送信号
 * - terminal_session_list: 列出活跃会话
 * - terminal_session_close: 关闭会话
 */
object TerminalToolNames {
    const val CREATE_SESSION = "terminal_session_create"
    const val EXEC_IN_SESSION = "terminal_session_exec"
    const val SEND_SIGNAL = "terminal_session_signal"
    const val LIST_SESSIONS = "terminal_session_list"
    const val CLOSE_SESSION = "terminal_session_close"
}

val TerminalToolDefaultApprovals: Map<String, Boolean> = mapOf(
    TerminalToolNames.CREATE_SESSION to false,
    TerminalToolNames.EXEC_IN_SESSION to true,
    TerminalToolNames.SEND_SIGNAL to true,
    TerminalToolNames.LIST_SESSIONS to false,
    TerminalToolNames.CLOSE_SESSION to false,
)

fun createTerminalTools(
    terminalSessionManager: TerminalSessionManager,
    approvalOverrides: Map<String, Boolean>,
): List<Tool> {
    fun needsApproval(name: String): Boolean =
        approvalOverrides[name] ?: TerminalToolDefaultApprovals[name] ?: true

    return listOf(
        // ── 创建会话 ──
        Tool(
            name = TerminalToolNames.CREATE_SESSION,
            description = "Create a persistent terminal session that maintains state (cwd, env) across commands.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("session_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional session identifier. Auto-generated if not provided.")
                        })
                        put("cwd", buildJsonObject {
                            put("type", "string")
                            put("description", "Initial working directory (optional)")
                        })
                    },
                )
            },
            needsApproval = { needsApproval(TerminalToolNames.CREATE_SESSION) },
            execute = { args ->
                val params = args.jsonObject
                val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull
                val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull
                val session = terminalSessionManager.createSession(
                    sessionId = sessionId ?: "session_${System.nanoTime()}",
                    cwd = cwd,
                )
                listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("session_id", session.id)
                        put("cwd", session.cwd)
                        put("created_at", session.createdAt)
                        put("message", "Session created. Use terminal_session_exec to run commands.")
                    }.toString()
                ))
            },
        ),

        // ── 执行命令 ──
        Tool(
            name = TerminalToolNames.EXEC_IN_SESSION,
            description = "Execute a command in an existing terminal session. The session persists cwd and environment.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("session_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Session ID from terminal_session_create")
                        })
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "Command to execute")
                        })
                        put("timeout", buildJsonObject {
                            put("type", "integer")
                            put("description", "Timeout in seconds (default: 30, max: 600)")
                        })
                    },
                    required = listOf("session_id", "command"),
                )
            },
            needsApproval = { needsApproval(TerminalToolNames.EXEC_IN_SESSION) },
            execute = { args ->
                val params = args.jsonObject
                val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull
                    ?: error("session_id is required")
                val command = params["command"]?.jsonPrimitive?.contentOrNull
                    ?: error("command is required")
                val timeout = params["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?.coerceIn(1L, 600L) ?: 30L

                val session = terminalSessionManager.getSession(sessionId)
                    ?: return@Tool listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "Session not found: $sessionId")
                        }.toString()
                    ))

                // 写入命令
                val written = terminalSessionManager.writeToSession(sessionId, "$command\n")
                if (!written) {
                    return@Tool listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "Failed to write to session: $sessionId")
                        }.toString()
                    ))
                }

                // 读取输出
                val outputBuilder = StringBuilder()
                val startTime = System.currentTimeMillis()
                val timeoutMs = timeout * 1000L

                while (true) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > timeoutMs) {
                        outputBuilder.append("\n[Command timed out after ${timeout}s]")
                        break
                    }
                    val data = terminalSessionManager.readFromSession(sessionId)
                    if (data == null || data.isEmpty()) {
                        Thread.sleep(50)
                        continue
                    }
                    val text = data.toString(Charsets.UTF_8)
                    outputBuilder.append(text)
                    // 如果超过 128KB 截断
                    if (outputBuilder.length > 128 * 1024) {
                        outputBuilder.append("\n[Output truncated at 128KB]")
                        break
                    }
                    // 检测命令结束（简单通过是否返回提示符判断）
                    if (text.contains("$ ") || text.contains("# ")) {
                        // 再给一点时间确保完整输出
                        Thread.sleep(200)
                        val extra = terminalSessionManager.readFromSession(sessionId)
                        if (extra != null) {
                            outputBuilder.append(extra.toString(Charsets.UTF_8))
                        }
                        break
                    }
                }

                listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("session_id", sessionId)
                        put("output", outputBuilder.toString())
                        put("elapsed_ms", System.currentTimeMillis() - startTime)
                    }.toString()
                ))
            },
        ),

        // ── 发送信号 ──
        Tool(
            name = TerminalToolNames.SEND_SIGNAL,
            description = "Send a signal (SIGINT, SIGTERM, SIGKILL) to a running process in a session.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("session_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Session ID")
                        })
                        put("signal", buildJsonObject {
                            put("type", "string")
                            put("description", "Signal type: SIGINT (Ctrl+C), SIGTERM, SIGKILL, SIGQUIT, SIGTSTP (Ctrl+Z)")
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("SIGINT"))
                                add(JsonPrimitive("SIGTERM"))
                                add(JsonPrimitive("SIGKILL"))
                                add(JsonPrimitive("SIGQUIT"))
                                add(JsonPrimitive("SIGTSTP"))
                            })
                        })
                    },
                    required = listOf("session_id", "signal"),
                )
            },
            needsApproval = { needsApproval(TerminalToolNames.SEND_SIGNAL) },
            execute = { args ->
                val params = args.jsonObject
                val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull
                    ?: error("session_id is required")
                val signalStr = params["signal"]?.jsonPrimitive?.contentOrNull
                    ?: error("signal is required")
                val signal = try {
                    Signal.valueOf(signalStr)
                } catch (e: IllegalArgumentException) {
                    return@Tool listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "Unknown signal: $signalStr. Use SIGINT, SIGTERM, SIGKILL, SIGQUIT, or SIGTSTP.")
                        }.toString()
                    ))
                }
                val success = terminalSessionManager.sendSignal(sessionId, signal)
                listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("session_id", sessionId)
                        put("signal", signalStr)
                        put("success", success)
                    }.toString()
                ))
            },
        ),

        // ── 列出会话 ──
        Tool(
            name = TerminalToolNames.LIST_SESSIONS,
            description = "List all active terminal sessions with their IDs and status.",
            execute = {
                val sessions = terminalSessionManager.listSessions()
                val now = System.currentTimeMillis()
                val sessionList = sessions.map { session ->
                    buildJsonObject {
                        put("id", session.id)
                        put("cwd", session.cwd)
                        put("age_seconds", (now - session.createdAt) / 1000)
                        put("idle_seconds", (now - session.lastActiveAt) / 1000)
                    }
                }
                listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("active_sessions", sessionList.size)
                        put("sessions", sessionList.joinToString(", ") { it.toString() })
                    }.toString()
                ))
            },
        ),

        // ── 关闭会话 ──
        Tool(
            name = TerminalToolNames.CLOSE_SESSION,
            description = "Close and clean up a terminal session.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("session_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Session ID to close")
                        })
                    },
                    required = listOf("session_id"),
                )
            },
            needsApproval = { needsApproval(TerminalToolNames.CLOSE_SESSION) },
            execute = { args ->
                val sessionId = args.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
                    ?: error("session_id is required")
                terminalSessionManager.closeSession(sessionId)
                listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("session_id", sessionId)
                        put("closed", true)
                    }.toString()
                ))
            },
        ),
    )
}
