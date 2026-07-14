package me.rerere.rikkahub.data.ai.tools.dev

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.DevToolsProvider

/**
 * AI 开发工具集
 *
 * 为 AI Agent 提供完整的代码开发工作流工具：
 * - Git 操作（clone, status, diff, commit, log）
 * - 包管理（install）
 * - 项目构建与测试
 */
object DevToolNames {
    const val GIT_CLONE = "git_clone"
    const val GIT_STATUS = "git_status"
    const val GIT_DIFF = "git_diff"
    const val GIT_COMMIT = "git_commit"
    const val GIT_LOG = "git_log"
    const val PKG_INSTALL = "pkg_install"
    const val BUILD_PROJECT = "build_project"
    const val RUN_TESTS = "run_tests"
}

val DevToolDefaultApprovals: Map<String, Boolean> = mapOf(
    DevToolNames.GIT_CLONE to true,
    DevToolNames.GIT_STATUS to false,
    DevToolNames.GIT_DIFF to false,
    DevToolNames.GIT_COMMIT to true,
    DevToolNames.GIT_LOG to false,
    DevToolNames.PKG_INSTALL to true,
    DevToolNames.BUILD_PROJECT to true,
    DevToolNames.RUN_TESTS to true,
)

/**
 * 创建 AI 开发工具列表
 *
 * @param workspaceId 工作区 ID
 * @param workspaceRoot 工作区根目录路径（用于文件操作）
 * @param approvalOverrides 审批覆盖配置
 * @param devTools 开发工具提供者（Git, 包管理, 构建）
 */
fun createDevTools(
    workspaceId: String,
    workspaceRoot: String,
    approvalOverrides: Map<String, Boolean>,
    devTools: DevToolsProvider,
): List<Tool> {
    fun needsApproval(name: String): Boolean =
        approvalOverrides[name] ?: DevToolDefaultApprovals[name] ?: true

    return listOf(
        createGitCloneTool(workspaceRoot, ::needsApproval, devTools),
        createGitStatusTool(workspaceRoot, ::needsApproval, devTools),
        createGitDiffTool(workspaceRoot, ::needsApproval, devTools),
        createGitCommitTool(workspaceRoot, ::needsApproval, devTools),
        createGitLogTool(workspaceRoot, ::needsApproval, devTools),
        createPkgInstallTool(workspaceRoot, ::needsApproval, devTools),
        createBuildProjectTool(workspaceRoot, ::needsApproval, devTools),
        createRunTestsTool(workspaceRoot, ::needsApproval, devTools),
    )
}

// ── Git Clone ────────────────────────────────────────

private fun createGitCloneTool(
    workspaceRoot: String,
    needsApproval: (String) -> Boolean,
    devTools: DevToolsProvider,
) = Tool(
    name = DevToolNames.GIT_CLONE,
    description = "Clone a git repository into the workspace. Clones into /workspace by default.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Repository URL to clone")
                })
                put("directory", buildJsonObject {
                    put("type", "string")
                    put("description", "Target directory name (optional, defaults to repo name)")
                })
                put("branch", buildJsonObject {
                    put("type", "string")
                    put("description", "Branch to clone (optional)")
                })
                put("depth", buildJsonObject {
                    put("type", "integer")
                    put("description", "Clone depth for shallow clone (optional)")
                })
            },
            required = listOf("url"),
        )
    },
    needsApproval = { needsApproval(DevToolNames.GIT_CLONE) },
    execute = { args ->
        val params = args.jsonObject
        val url = params["url"]?.jsonPrimitive?.contentOrNull ?: error("url is required")
        val directory = params["directory"]?.jsonPrimitive?.contentOrNull
        val branch = params["branch"]?.jsonPrimitive?.contentOrNull
        val depth = params["depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val result = devTools.gitClone(workspaceRoot, url, directory, branch, depth)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", result.success)
                put("output", result.output)
                put("exitCode", result.exitCode)
            }.toString()
        ))
    },
)

// ── Git Status ────────────────────────────────────────

private fun createGitStatusTool(
    workspaceRoot: String,
    needsApproval: (String) -> Boolean,
    devTools: DevToolsProvider,
) = Tool(
    name = DevToolNames.GIT_STATUS,
    description = "Show the working tree status of a git repository in the workspace.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory relative to workspace root")
                })
            },
        )
    },
    needsApproval = { needsApproval(DevToolNames.GIT_STATUS) },
    execute = { args ->
        val params = args.jsonObject
        val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull ?: ""
        val result = devTools.gitStatus(workspaceRoot, cwd)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", result.success)
                put("output", result.output)
                put("exitCode", result.exitCode)
            }.toString()
        ))
    },
)

// ── Git Diff ──────────────────────────────────────────

private fun createGitDiffTool(
    workspaceRoot: String,
    needsApproval: (String) -> Boolean,
    devTools: DevToolsProvider,
) = Tool(
    name = DevToolNames.GIT_DIFF,
    description = "Show file diffs in a git repository (unstaged changes by default).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("staged", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Show staged changes only")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Specific file path to diff (optional)")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory relative to workspace root")
                })
            },
        )
    },
    needsApproval = { needsApproval(DevToolNames.GIT_DIFF) },
    execute = { args ->
        val params = args.jsonObject
        val staged = params["staged"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val path = params["path"]?.jsonPrimitive?.contentOrNull
        val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull ?: ""
        val result = devTools.gitDiff(workspaceRoot, staged, path, cwd)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", result.success)
                put("output", result.output)
                put("exitCode", result.exitCode)
            }.toString()
        ))
    },
)

// ── Git Commit ────────────────────────────────────────

private fun createGitCommitTool(
    workspaceRoot: String,
    needsApproval: (String) -> Boolean,
    devTools: DevToolsProvider,
) = Tool(
    name = DevToolNames.GIT_COMMIT,
    description = "Create a new commit in the git repository.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Commit message")
                })
                put("all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Auto-stage all tracked files")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory relative to workspace root")
                })
            },
            required = listOf("message"),
        )
    },
    needsApproval = { needsApproval(DevToolNames.GIT_COMMIT) },
    execute = { args ->
        val params = args.jsonObject
        val message = params["message"]?.jsonPrimitive?.contentOrNull ?: error("message is required")
        val all = params["all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull ?: ""
        val result = devTools.gitCommit(workspaceRoot, message, all, cwd)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", result.success)
                put("output", result.output)
                put("exitCode", result.exitCode)
            }.toString()
        ))
    },
)

// ── Git Log ───────────────────────────────────────────

private fun createGitLogTool(
    workspaceRoot: String,
    needsApproval: (String) -> Boolean,
    devTools: DevToolsProvider,
) = Tool(
    name = DevToolNames.GIT_LOG,
    description = "Show commit logs in the git repository.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("maxCount", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of commits to show (default: 10)")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory relative to workspace root")
                })
            },
        )
    },
    needsApproval = { needsApproval(DevToolNames.GIT_LOG) },
    execute = { args ->
        val params = args.jsonObject
        val maxCount = params["maxCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10
        val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull ?: ""
        val result = devTools.gitLog(workspaceRoot, maxCount, cwd)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", result.success)
                put("output", result.output)
                put("exitCode", result.exitCode)
            }.toString()
        ))
    },
)

// ── Package Install ───────────────────────────────────

private fun createPkgInstallTool(
    workspaceRoot: String,
    needsApproval: (String) -> Boolean,
    devTools: DevToolsProvider,
) = Tool(
    name = DevToolNames.PKG_INSTALL,
    description = "Install packages using the system package manager. Auto-detects apt/pip/npm.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("packages", buildJsonObject {
                    put("type", "string")
                    put("description", "Package names to install (space-separated)")
                })
                put("manager", buildJsonObject {
                    put("type", "string")
                    put("description", "Package manager override: apt, pip, npm, gem (optional, auto-detected)")
                })
            },
            required = listOf("packages"),
        )
    },
    needsApproval = { needsApproval(DevToolNames.PKG_INSTALL) },
    execute = { args ->
        val params = args.jsonObject
        val packagesStr = params["packages"]?.jsonPrimitive?.contentOrNull ?: error("packages is required")
        val manager = params["manager"]?.jsonPrimitive?.contentOrNull
        val packages = packagesStr.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val result = devTools.installPackages(workspaceRoot, packages, manager)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", result.success)
                put("output", result.output)
                put("exitCode", result.exitCode)
            }.toString()
        ))
    },
)

// ── Build Project ─────────────────────────────────────

private fun createBuildProjectTool(
    workspaceRoot: String,
    needsApproval: (String) -> Boolean,
    devTools: DevToolsProvider,
) = Tool(
    name = DevToolNames.BUILD_PROJECT,
    description = "Build a project in the workspace. Auto-detects Gradle, Maven, npm, Make, Cargo, CMake.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Project path relative to workspace root")
                })
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Custom build command override (optional)")
                })
            },
        )
    },
    needsApproval = { needsApproval(DevToolNames.BUILD_PROJECT) },
    execute = { args ->
        val params = args.jsonObject
        val path = params["path"]?.jsonPrimitive?.contentOrNull ?: ""
        val command = params["command"]?.jsonPrimitive?.contentOrNull
        val result = devTools.buildProject(workspaceRoot, path, command)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", result.success)
                put("output", result.output)
                put("buildSystem", result.buildSystem)
                put("exitCode", result.exitCode)
            }.toString()
        ))
    },
)

// ── Run Tests ─────────────────────────────────────────

private fun createRunTestsTool(
    workspaceRoot: String,
    needsApproval: (String) -> Boolean,
    devTools: DevToolsProvider,
) = Tool(
    name = DevToolNames.RUN_TESTS,
    description = "Run tests for a project in the workspace. Auto-detects test framework.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Project path relative to workspace root")
                })
                put("filter", buildJsonObject {
                    put("type", "string")
                    put("description", "Test filter pattern (optional)")
                })
            },
        )
    },
    needsApproval = { needsApproval(DevToolNames.RUN_TESTS) },
    execute = { args ->
        val params = args.jsonObject
        val path = params["path"]?.jsonPrimitive?.contentOrNull ?: ""
        val filter = params["filter"]?.jsonPrimitive?.contentOrNull
        val result = devTools.runTests(workspaceRoot, path, filter)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", result.success)
                put("output", result.output)
                put("buildSystem", result.buildSystem)
                put("exitCode", result.exitCode)
            }.toString()
        ))
    },
)
