package me.rerere.rikkahub.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BugReporter — 把运行时错误、crash、workspace 命令失败全部写进一个结构化文件，
 * 然后用户可以一键复制给我，让我看到真正的错误，不再盲修。
 *
 * 用法：
 *   BugReporter.onCrash(context, thread, throwable)  // App 崩溃
 *   BugReporter.onCommandError(context, command, result) // workspace_shell 失败
 *   BugReporter.onToolError(context, toolName, args, error) // 工具执行异常
 */
object BugReporter {
    private const val TAG = "BugReporter"
    private const val LOG_FILE = "bugreport.jsonl"   // 每行一个 JSON 的日志文件
    private const val MAX_ENTRIES = 200               // 最多保留 200 条，防撑爆
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    /** 在 Application.onCreate 里调用一次，之后所有方法不再需要传 Context */
    fun init(app: Context) {
        if (_appContext == null) _appContext = app.applicationContext
    }
    private var _appContext: Context? = null
    private fun appCtx(): Context = _appContext ?: error("BugReporter.init() 未调用")

    /** 不传 Context 的便捷方法（前提是已调用 init） */
    fun onCrash(thread: Thread, throwable: Throwable) {
        val ctx = _appContext ?: return  // init 前忽略
        onCrash(ctx, thread, throwable)
    }
    fun onCommandError(command: String, exitCode: Int, stdout: String, stderr: String) {
        val ctx = _appContext ?: return
        onCommandError(ctx, command, exitCode, stdout, stderr)
    }
    fun onToolError(toolName: String, args: String, error: Throwable) {
        val ctx = _appContext ?: return
        onToolError(ctx, toolName, args, error)
    }
    fun readReport(): String {
        val ctx = _appContext ?: return "[BugReporter] 未初始化\n"
        return readReport(ctx)
    }

    // ─── 事件类型 ─────────────────────────────────────────

    @Serializable
    data class BugEvent(
        val timestamp: String,
        val type: String,          // "CRASH" / "COMMAND_ERROR" / "TOOL_ERROR"
        val thread: String? = null,
        val title: String,
        val detail: String,        // 堆栈 / stderr / 异常消息
        val command: String? = null,
        val exitCode: Int? = null,
        val stdout: String? = null,
        val stderr: String? = null,
        val appVersion: String? = null,
    )

    // ─── 写入日志 ─────────────────────────────────────────

    /** 记录一次 App 崩溃 */
    fun onCrash(context: Context, thread: Thread, throwable: Throwable) {
        val event = BugEvent(
            timestamp = now(),
            type = "CRASH",
            thread = thread.name,
            title = throwable.javaClass.name,
            detail = throwable.stackTraceToString(),
            appVersion = getAppVersion(context),
        )
        appendEvent(context, event)
        copyToClipboard(context, event)  // 崩溃时自动写到剪贴板
    }

    /** 记录一次 workspace_shell 命令失败 */
    fun onCommandError(context: Context, command: String, exitCode: Int, stdout: String, stderr: String) {
        if (exitCode == 0) return  // 成功不记录
        val event = BugEvent(
            timestamp = now(),
            type = "COMMAND_ERROR",
            title = "exit=$exitCode",
            detail = stderr.ifBlank { stdout }.take(4000),
            command = command.take(1000),
            exitCode = exitCode,
            stdout = stdout.take(2000),
            stderr = stderr.take(2000),
            appVersion = getAppVersion(context),
        )
        appendEvent(context, event)
    }

    /** 记录一次工具执行异常 */
    fun onToolError(context: Context, toolName: String, args: String, error: Throwable) {
        val event = BugEvent(
            timestamp = now(),
            type = "TOOL_ERROR",
            title = "$toolName: ${error.javaClass.name}",
            detail = error.stackTraceToString().take(4000),
            command = args.take(1000),
            appVersion = getAppVersion(context),
        )
        appendEvent(context, event)
    }

    // ─── 读取 ────────────────────────────────────────────

    /** 读取全部日志，返回纯文本格式，方便贴给我 */
    fun readReport(context: Context): String {
        val file = getLogFile(context)
        if (!file.exists()) return "[BugReporter] 尚无错误记录\n"

        val lines = file.readLines()
        val sb = StringBuilder()
        sb.appendLine("╔══════════════════════════════════════════════")
        sb.appendLine("║  RikkaHub Bug Report  (${lines.size} 条记录)")
        sb.appendLine("╚══════════════════════════════════════════════")
        sb.appendLine()

        for ((i, line) in lines.withIndex()) {
            try {
                val event = json.decodeFromString<BugEvent>(line)
                sb.appendLine("--- [${i + 1}] ${event.type} @ ${event.timestamp} ---")
                if (event.thread != null) sb.appendLine("线程: ${event.thread}")
                sb.appendLine("标题: ${event.title}")
                if (event.exitCode != null) sb.appendLine("退出码: ${event.exitCode}")
                if (event.command != null) sb.appendLine("命令: ${event.command}")
                sb.appendLine("详情:")
                sb.appendLine(event.detail)
                if (!event.stdout.isNullOrBlank()) {
                    sb.appendLine("--- stdout ---")
                    sb.appendLine(event.stdout)
                }
                if (!event.stderr.isNullOrBlank()) {
                    sb.appendLine("--- stderr ---")
                    sb.appendLine(event.stderr)
                }
                sb.appendLine()
            } catch (_: Exception) {
                sb.appendLine("[解析失败] $line")
            }
        }
        return sb.toString()
    }

    /** 日志文件路径（外部可读，可以用 adb pull） */
    fun getLogFile(context: Context): File =
        File(context.filesDir, LOG_FILE)

    // ─── 内部 ────────────────────────────────────────────

    private fun appendEvent(context: Context, event: BugEvent) {
        try {
            val file = getLogFile(context)
            file.parentFile?.mkdirs()

            val line = json.encodeToString(event)
            file.appendText(line + "\n")

            // 超出上限时裁剪旧条目
            trimExcess(context, file)
        } catch (e: Exception) {
            Log.e(TAG, "写日志失败", e)
        }
    }

    private fun trimExcess(context: Context, file: File) {
        if (!file.exists()) return
        val lines = file.readLines()
        if (lines.size <= MAX_ENTRIES) return
        val kept = lines.takeLast(MAX_ENTRIES)
        file.writeText(kept.joinToString("\n") + "\n")
    }

    private fun copyToClipboard(context: Context, event: BugEvent) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = buildString {
                appendLine("═══ RikkaHub Bug Report ═══")
                appendLine("时间: ${event.timestamp}")
                appendLine("类型: ${event.type}")
                appendLine("标题: ${event.title}")
                if (event.thread != null) appendLine("线程: ${event.thread}")
                if (event.command != null) appendLine("命令: ${event.command}")
                appendLine()
                appendLine("详情:")
                appendLine(event.detail)
            }
            cm.setPrimaryClip(ClipData.newPlainText("RikkaHub Bug Report", text))
            Log.i(TAG, "崩溃信息已写入剪贴板")
        } catch (_: Exception) {
            // 剪贴板写失败无所谓，日志文件还在
        }
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private var cachedVersion: String? = null

    private fun getAppVersion(context: Context): String {
        if (cachedVersion != null) return cachedVersion!!
        return try {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pkg.versionName} (${pkg.longVersionCode})"
        } catch (_: Exception) {
            "unknown"
        }.also { cachedVersion = it }
    }
}
