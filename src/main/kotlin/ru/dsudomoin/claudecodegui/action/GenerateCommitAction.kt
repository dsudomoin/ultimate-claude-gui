package ru.dsudomoin.claudecodegui.action

import ru.dsudomoin.claudecodegui.UcuBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import kotlinx.coroutines.*
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.core.model.StreamEvent
import ru.dsudomoin.claudecodegui.provider.claude.ClaudeProvider
import java.io.File
import javax.swing.SwingUtilities

/**
 * VCS commit toolbar action: generates a commit message from staged diff using Claude SDK.
 * Registered in Vcs.MessageActionGroup so it appears in the commit dialog.
 */
class GenerateCommitAction : AnAction("Generate Commit Message with Claude"), DumbAware {

    private val log = Logger.getInstance(GenerateCommitAction::class.java)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commitMessage: CommitMessageI? = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        if (commitMessage == null) {
            notify(project, UcuBundle.message("commit.notFound"), NotificationType.WARNING)
            return
        }

        val basePath = project.basePath ?: return

        // Get git diff (staged first, then unstaged)
        val diff = getGitDiff(basePath)
        if (diff.isNullOrBlank()) {
            notify(project, UcuBundle.message("commit.noChanges"), NotificationType.WARNING)
            return
        }

        // Show placeholder
        commitMessage.setCommitMessage(UcuBundle.message("commit.generating"))

        // Generate in background using ClaudeProvider (SDK bridge)
        val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cancelDisposable = Disposable {
            actionScope.cancel("Project/plugin disposed")
        }
        Disposer.register(project, cancelDisposable)

        actionScope.launch {
            try {
                val message = generateViaSDK(project, diff)
                if (project.isDisposed) return@launch
                SwingUtilities.invokeLater {
                    commitMessage.setCommitMessage(message)
                }
                notify(project, UcuBundle.message("commit.success"), NotificationType.INFORMATION)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                log.warn("Failed to generate commit message", ex)
                SwingUtilities.invokeLater {
                    commitMessage.setCommitMessage("")
                }
                notify(project, UcuBundle.message("commit.error", ex.message ?: ""), NotificationType.ERROR)
            }
        }.invokeOnCompletion {
            actionScope.cancel()
            runCatching { Disposer.dispose(cancelDisposable) }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }

    // ── Generate via ClaudeProvider (Node.js bridge) ────────────────────────

    private suspend fun generateViaSDK(project: Project, diff: String): String {
        val provider = ClaudeProvider().apply {
            projectDir = project.basePath
        }

        val prompt = buildPrompt(diff)
        val userMessage = Message.user(prompt)
        val responseText = StringBuilder()

        try {
            provider.sendMessage(
                messages = listOf(userMessage),
                model = "claude-sonnet-4-6",
                maxTokens = 1024,
                systemPrompt = null,
                permissionMode = "plan",  // read-only, no tools needed
                streaming = false
            ).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> responseText.append(event.text)
                    is StreamEvent.TextSnapshot -> {
                        responseText.clear()
                        responseText.append(event.text)
                    }
                    is StreamEvent.Error -> throw RuntimeException(event.message)
                    else -> { /* ignore */ }
                }
            }
        } finally {
            provider.close()
        }

        val raw = responseText.toString()
        if (raw.isBlank()) throw RuntimeException(UcuBundle.message("commit.emptyResponse"))
        return extractCommitMessage(raw)
    }

    // ── Git diff ────────────────────────────────────────────────────────────

    private fun getGitDiff(basePath: String): String? {
        // Try staged diff first
        var diff = runGit(basePath, "diff", "--staged")
        if (diff.isNullOrBlank()) {
            diff = runGit(basePath, "diff")
        }
        return diff?.take(8000)
    }

    private fun runGit(basePath: String, vararg args: String): String? {
        return try {
            val cmd = listOf("git") + args.toList()
            val process = ProcessBuilder(cmd)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.ifBlank { null }
        } catch (e: Exception) {
            log.warn("Git command failed: git ${args.joinToString(" ")}", e)
            null
        }
    }

    // ── Prompt ──────────────────────────────────────────────────────────────

    private fun buildPrompt(diff: String): String = """
Generate a concise git commit message for the following changes.

Rules:
- Use Conventional Commits format: type(scope): description
- Types: feat, fix, docs, style, refactor, perf, test, chore, ci, build
- Keep the first line under 72 characters
- Use imperative mood ("add" not "added")
- Be specific about what changed
- If changes are complex, add a blank line and bullet points for details
- Output ONLY the commit message inside <commit> tags

<commit>your commit message here</commit>

Git diff:
```
${diff.take(6000)}
```
""".trimIndent()

    // ── Parse response ─────────────────────────────────────────────────────

    private fun extractCommitMessage(text: String): String {
        // 1. XML tag extraction
        val tagMatch = Regex("<commit>(.*?)</commit>", RegexOption.DOT_MATCHES_ALL).find(text)
        if (tagMatch != null) return tagMatch.groupValues[1].trim()

        // 2. Code block fallback
        val codeMatch = Regex("```\\w*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).find(text)
        if (codeMatch != null) return codeMatch.groupValues[1].trim()

        // 3. Conventional commit pattern
        val ccMatch = Regex("^(feat|fix|docs|style|refactor|perf|test|chore|ci|build)(\\(.+?\\))?:.*", RegexOption.MULTILINE).find(text)
        if (ccMatch != null) return ccMatch.value.trim()

        // 4. Raw text
        return text.trim().lines().take(5).joinToString("\n").trim()
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude Code")
            .createNotification(message, type)
            .notify(project)
    }
}
