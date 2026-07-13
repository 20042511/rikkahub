package me.rerere.workspace

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Termux Bootstrap 安装器
 *
 * 从 APK assets 或 Termux 官方仓库下载并安装 Termux bootstrap。
 * 优先从 assets 读取（GitHub Actions 预打包），无需网络。
 */
class TermuxBootstrapInstaller(
    private val context: Context,
    private val targetDir: File,
) {
    fun interface ProgressCallback {
        fun onProgress(stage: Stage, progress: Float, message: String)
    }

    enum class Stage { DETECTING, DOWNLOADING, EXTRACTING, PATCHING, COMPLETED, FAILED }

    sealed class Result {
        data object Success : Result()
        data object AlreadyInstalled : Result()
        data class Failed(val error: String, val cause: Throwable? = null) : Result()
    }

    fun isInstalled(): Boolean =
        File(targetDir, "bin/bash").exists() &&
        File(targetDir, "bin/bash").canExecute()

    suspend fun install(callback: ProgressCallback? = null): Result = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext Result.AlreadyInstalled

        try {
            val stagingDir = File(targetDir.parentFile, "usr-staging")
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()

            callback?.onProgress(Stage.DOWNLOADING, 0f, "获取 Termux bootstrap...")

            // 1. 打开 bootstrap 数据流
            val inputStream = openBootstrapStream()

            // 2. 提取
            callback?.onProgress(Stage.EXTRACTING, 0.3f, "解压中...")
            extractArchive(inputStream, stagingDir)

            // 3. 修复 shebangs
            callback?.onProgress(Stage.PATCHING, 0.8f, "修复兼容性...")
            patchShebangs(stagingDir)

            // 4. 原子安装
            if (targetDir.exists()) targetDir.deleteRecursively()
            stagingDir.renameTo(targetDir)

            if (!isInstalled()) {
                throw RuntimeException("验证失败: bin/bash 不存在")
            }

            callback?.onProgress(Stage.COMPLETED, 1f, "Termux 就绪！")
            Result.Success

        } catch (e: Exception) {
            File(targetDir.parentFile, "usr-staging").deleteRecursively()
            callback?.onProgress(Stage.FAILED, 0f, "失败: ${e.message}")
            Result.Failed(e.message ?: "未知错误", e)
        }
    }

    // ─── bootstrap 来源 ─────────────────────────

    private fun openBootstrapStream(): java.io.InputStream {
        val arch = detectArch()

        // 1. 优先从 APK assets 读取
        val assetPath = "termux/bootstrap-${arch}.tar.xz"
        try {
            context.assets.open(assetPath).also { stream ->
                if (stream.available() > 1024 * 1024) return stream
                stream.close()
            }
        } catch (_: Exception) { /* 无 asset 版本 */ }

        // 2. 从官方仓库下载
        val url = "https://packages.termux.dev/apt/termux-main/bootstrap/bootstrap-${arch}.tar.xz"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        return conn.inputStream
    }

    private fun detectArch(): String = when {
        System.getProperty("os.arch")?.lowercase()?.contains("aarch64") == true -> "aarch64"
        System.getProperty("os.arch")?.lowercase()?.contains("arm64") == true -> "aarch64"
        System.getProperty("os.arch")?.lowercase()?.contains("arm") == true -> "arm"
        System.getProperty("os.arch")?.lowercase()?.contains("x86_64") == true -> "x86_64"
        System.getProperty("os.arch")?.lowercase()?.contains("i686") == true -> "i686"
        System.getProperty("os.arch")?.lowercase()?.contains("x86") == true -> "i686"
        else -> throw RuntimeException("Unsupported arch: ${System.getProperty("os.arch")}")
    }

    // ─── 提取 ─────────────────────────────────

    private fun extractArchive(input: java.io.InputStream, target: File) {
        // Termux APK 中的 bootstrap 是嵌入在 ELF .so 中的 ZIP 数据
        // 写入临时文件，定位 ZIP 偏移，解压
        val tempFile = File.createTempFile("bootstrap", ".so", target)
        tempFile.outputStream().use { input.copyTo(it) }
        input.close()

        val bytes = tempFile.readBytes()
        val zipStart = findZipOffset(bytes)
        val zipBytes = bytes.copyOfRange(zipStart, bytes.size)
        val tempZip = File.createTempFile("bootstrap", ".zip", target)
        tempZip.writeBytes(zipBytes)

        ZipInputStream(tempZip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(target, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it) }
                    if (entry.name.startsWith("bin/")) {
                        outFile.setExecutable(true, false)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        tempFile.delete()
        tempZip.delete()
    }

    private fun findZipOffset(data: ByteArray): Int {
        val pk = 0x50
        val kb = 0x4B.toByte()
        val et = 0x03.toByte()
        val ff = 0x04.toByte()
        for (i in 0 until data.size - 4) {
            if (data[i].toInt() == pk &&
                data[i + 1] == kb &&
                data[i + 2] == et &&
                data[i + 3] == ff
            ) return i
        }
        throw RuntimeException("ZIP header not found in bootstrap")
    }

    // ─── shebang 修复 ─────────────────────────

    private fun patchShebangs(prefixDir: File) {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val ourPrefix = prefixDir.absolutePath
        listOf("bin", "libexec").forEach { dirName ->
            val dir = File(prefixDir, dirName)
            if (dir.exists()) {
                dir.walkTopDown()
                    .filter { it.isFile && it.length() in 1..(512 * 1024) }
                    .forEach { file ->
                        try {
                            val text = file.readText()
                            if (oldPrefix in text) {
                                file.writeText(text.replace(oldPrefix, ourPrefix))
                                file.setExecutable(true, false)
                            }
                        } catch (_: Exception) { }
                    }
            }
        }
    }
}
