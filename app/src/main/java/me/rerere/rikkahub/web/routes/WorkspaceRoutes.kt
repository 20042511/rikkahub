package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Workspace REST API
 *
 * 直接调用 workspace 操作，不经过 AI 聊天流程。
 * 用于测试、调试和外部集成。
 */
fun Route.workspaceRoutes(
    workspaceRepository: WorkspaceRepository,
) {
    route("/workspace") {

        // POST /api/workspace/shell - 执行 shell 命令
        post("/shell") {
            val request = call.receive<WorkspaceShellRequest>()
            val workspace = workspaceRepository.getById(request.workspaceId)
                ?: throw NotFoundException("Workspace not found: ${request.workspaceId}")
            if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
                throw BadRequestException("Workspace shell is not ready (status: ${workspace.shellStatus})")
            }

            val result = workspaceRepository.executeCommand(
                id = request.workspaceId,
                command = request.command,
                cwd = request.cwd ?: "",
                timeoutMillis = (request.timeoutSeconds ?: 30L).coerceIn(1L, 600L) * 1000L,
            )

            call.respond(
                HttpStatusCode.OK,
                WorkspaceShellResponse(
                    exitCode = result.exitCode,
                    stdout = result.stdout,
                    stderr = result.stderr,
                    timedOut = result.timedOut,
                    truncated = result.truncated,
                )
            )
        }

        // POST /api/workspace/read - 读文件
        post("/read") {
            val request = call.receive<WorkspaceReadRequest>()
            val workspace = workspaceRepository.getById(request.workspaceId)
                ?: throw NotFoundException("Workspace not found: ${request.workspaceId}")

            val (area, relativePath) = resolvePath(request.path)
            val buffer = java.io.ByteArrayOutputStream()
            workspaceRepository.exportFile(
                id = request.workspaceId,
                area = area,
                path = relativePath,
                outputStream = buffer,
            )
            val text = buffer.toString(Charsets.UTF_8)

            call.respond(
                HttpStatusCode.OK,
                WorkspaceReadResponse(
                    path = request.path,
                    text = text,
                    sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
                )
            )
        }

        // POST /api/workspace/write - 写文件
        post("/write") {
            val request = call.receive<WorkspaceWriteRequest>()
            val workspace = workspaceRepository.getById(request.workspaceId)
                ?: throw NotFoundException("Workspace not found: ${request.workspaceId}")

            val entry = workspaceRepository.writeText(
                id = request.workspaceId,
                path = request.path,
                text = request.text,
                overwrite = request.overwrite ?: true,
            )

            call.respond(
                HttpStatusCode.OK,
                WorkspaceWriteResponse(
                    path = entry.path,
                    sizeBytes = entry.sizeBytes,
                )
            )
        }

        // POST /api/workspace/list - 列出目录
        post("/list") {
            val request = call.receive<WorkspaceListRequest>()
            val workspace = workspaceRepository.getById(request.workspaceId)
                ?: throw NotFoundException("Workspace not found: ${request.workspaceId}")

            val (area, relativePath) = resolvePath(request.path ?: "/workspace")
            val files = workspaceRepository.listFiles(
                id = request.workspaceId,
                area = area,
                path = relativePath,
            )

            call.respond(
                HttpStatusCode.OK,
                WorkspaceListResponse(
                    path = request.path ?: "/workspace",
                    entries = files.map { entry ->
                        FileEntry(
                            path = entry.path,
                            name = entry.name,
                            isDirectory = entry.isDirectory,
                            sizeBytes = entry.sizeBytes,
                        )
                    }
                )
            )
        }
    }
}

private fun resolvePath(path: String): Pair<me.rerere.workspace.WorkspaceStorageArea, String> {
    val trimmed = path.trimEnd('/')
    return if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
        me.rerere.workspace.WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
    } else {
        me.rerere.workspace.WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
    }
}

// ── DTOs ──────────────────────────────────────────────

@Serializable
data class WorkspaceShellRequest(
    val workspaceId: String,
    val command: String,
    val cwd: String? = null,
    val timeoutSeconds: Long? = null,
)

@Serializable
data class WorkspaceShellResponse(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

@Serializable
data class WorkspaceReadRequest(
    val workspaceId: String,
    val path: String,
)

@Serializable
data class WorkspaceReadResponse(
    val path: String,
    val text: String,
    val sizeBytes: Long,
)

@Serializable
data class WorkspaceWriteRequest(
    val workspaceId: String,
    val path: String,
    val text: String,
    val overwrite: Boolean? = null,
)

@Serializable
data class WorkspaceWriteResponse(
    val path: String,
    val sizeBytes: Long,
)

@Serializable
data class WorkspaceListRequest(
    val workspaceId: String,
    val path: String? = null,
)

@Serializable
data class WorkspaceListResponse(
    val path: String,
    val entries: List<FileEntry>,
)

@Serializable
data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)
