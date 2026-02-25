package ru.dsudomoin.claudecodegui.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import ru.dsudomoin.claudecodegui.ui.toolwindow.ChatContainerPanel

/**
 * Sends the selected text (or current file context) to the Claude Code chat.
 * Keyboard shortcut: Ctrl+Alt+K
 */
class SendSelectionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        // Build context message
        val sb = StringBuilder()

        val selectedText = editor?.selectionModel?.selectedText
        val filePath = file?.path

        if (selectedText != null && selectedText.isNotBlank()) {
            // Send selected code with file context
            val lang = file?.extension ?: ""
            if (filePath != null) {
                sb.append("From `$filePath`:\n")
            }
            sb.append("```$lang\n$selectedText\n```\n")
        } else if (editor != null && filePath != null) {
            // No selection — send current file path and cursor position
            val caretOffset = editor.caretModel.offset
            val lineNumber = editor.document.getLineNumber(caretOffset) + 1
            sb.append("I'm working in `$filePath` (line $lineNumber).\n")
        } else if (filePath != null) {
            // From project tree — send file path
            sb.append("File: `$filePath`\n")
        }

        if (sb.isEmpty()) return

        // Open tool window and send the message
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude Code") ?: return
        toolWindow.show {
            val content = toolWindow.contentManager.selectedContent ?: return@show
            val container = content.component as? ChatContainerPanel ?: return@show
            container.controller.sendMessage(sb.toString())
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
