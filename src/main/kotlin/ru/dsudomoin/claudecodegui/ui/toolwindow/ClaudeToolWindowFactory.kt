package ru.dsudomoin.claudecodegui.ui.toolwindow

import ru.dsudomoin.claudecodegui.UcuBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import ru.dsudomoin.claudecodegui.ui.chat.ChatPanel
import ru.dsudomoin.claudecodegui.ui.history.HistoryPanel
import ru.dsudomoin.claudecodegui.ui.theme.ThemeManager
import java.awt.CardLayout
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ClaudeToolWindowFactory : ToolWindowFactory {

    private val tabCounter = AtomicInteger(0)

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        ThemeManager.initialize()
        addNewTab(project, toolWindow)

        // Manage tab closeable state: hide X when only 1 tab remains
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentAdded(event: ContentManagerEvent) {
                updateTabCloseableState(toolWindow)
            }
            override fun contentRemoved(event: ContentManagerEvent) {
                if (toolWindow.contentManager.contentCount == 0) {
                    addNewTab(project, toolWindow)
                }
                updateTabCloseableState(toolWindow)
            }
        })

        toolWindow.setTitleActions(listOf(
            object : AnAction(UcuBundle.message("toolwindow.newChat"), UcuBundle.message("toolwindow.newChatDesc"), AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = addNewTab(project, toolWindow)
                override fun update(e: AnActionEvent) {
                    e.presentation.text = UcuBundle.message("toolwindow.newChat")
                    e.presentation.description = UcuBundle.message("toolwindow.newChatDesc")
                }
            },
            object : AnAction(UcuBundle.message("toolwindow.history"), UcuBundle.message("toolwindow.historyDesc"), AllIcons.Vcs.History) {
                override fun actionPerformed(e: AnActionEvent) = toggleHistory(project, toolWindow)
                override fun update(e: AnActionEvent) {
                    e.presentation.text = UcuBundle.message("toolwindow.history")
                    e.presentation.description = UcuBundle.message("toolwindow.historyDesc")
                }
            },
            object : AnAction(UcuBundle.message("toolwindow.settings"), UcuBundle.message("toolwindow.settingsDesc"), AllIcons.General.GearPlain) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, ru.dsudomoin.claudecodegui.settings.ClaudeSettingsConfigurable::class.java)
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.text = UcuBundle.message("toolwindow.settings")
                    e.presentation.description = UcuBundle.message("toolwindow.settingsDesc")
                }
            }
        ))

        // Gear dropdown: Rename only.
        // Close Tab is NOT here — gear actions leak into tab right-click menu
        // and always act on selectedContent (the active tab), not the right-clicked tab.
        // Tab closing uses IntelliJ's built-in mechanism (isCloseable = true on each Content).
        val renameAction = object : AnAction(UcuBundle.message("toolwindow.rename"), UcuBundle.message("toolwindow.renameDesc"), AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) {
                renameCurrentTab(project, toolWindow)
            }
        }
        toolWindow.setAdditionalGearActions(DefaultActionGroup(renameAction))

        // Double-click on tab area to rename
        SwingUtilities.invokeLater {
            installDoubleClickRename(project, toolWindow)
        }
    }

    private fun addNewTab(project: Project, toolWindow: ToolWindow) {
        val num = tabCounter.incrementAndGet()
        val container = ChatContainerPanel(project, toolWindow)
        val title = if (num == 1) UcuBundle.message("chat.tab") else "${UcuBundle.message("chat.tab")} $num"
        val content = ContentFactory.getInstance().createContent(container, title, false).apply {
            isCloseable = true
            setDisposer(container)
        }
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
    }

    private fun updateTabCloseableState(toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val closeable = contentManager.contentCount > 1
        for (content in contentManager.contents) {
            content.isCloseable = closeable
        }
    }

    private var doubleClickInstalled = false

    private fun installDoubleClickRename(project: Project, toolWindow: ToolWindow) {
        if (doubleClickInstalled) return
        doubleClickInstalled = true

        // Install double-click listener on the content manager component for rename
        toolWindow.contentManager.component.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    renameCurrentTab(project, toolWindow)
                }
            }
        })
    }

    private fun toggleHistory(project: Project, toolWindow: ToolWindow) {
        val selectedContent = toolWindow.contentManager.selectedContent ?: return
        val container = selectedContent.component as? ChatContainerPanel ?: return
        container.toggleHistory()
    }

    private fun renameCurrentTab(project: Project, toolWindow: ToolWindow) {
        val content = toolWindow.contentManager.selectedContent ?: return
        val container = content.component as? ChatContainerPanel ?: return
        val chatPanel = container.chatPanel

        val currentTitle = content.displayName ?: UcuBundle.message("chat.tab")
        val newTitle = Messages.showInputDialog(
            project,
            UcuBundle.message("toolwindow.renamePrompt"),
            UcuBundle.message("toolwindow.rename"),
            null,
            currentTitle,
            null
        ) ?: return

        if (newTitle.isNotBlank()) {
            content.displayName = newTitle
            chatPanel.customTitle = newTitle
        }
    }
}

/**
 * Container that switches between chat and history views using CardLayout.
 */
class ChatContainerPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) : JPanel(CardLayout()), Disposable {

    val chatPanel = ChatPanel(project)
    private var historyPanel: HistoryPanel? = null
    private var showingHistory = false

    init {
        add(chatPanel, CARD_CHAT)
    }

    fun toggleHistory() {
        val layout = layout as CardLayout
        if (showingHistory) {
            layout.show(this, CARD_CHAT)
            showingHistory = false
        } else {
            if (historyPanel == null) {
                historyPanel = HistoryPanel(
                    project = project,
                    onLoadSession = { sessionId ->
                        chatPanel.loadSession(sessionId)
                        toolWindow.contentManager.selectedContent?.displayName = chatPanel.sessionTitle
                        (this.layout as CardLayout).show(this, CARD_CHAT)
                        showingHistory = false
                    },
                    onBack = {
                        (this.layout as CardLayout).show(this, CARD_CHAT)
                        showingHistory = false
                    }
                )
                add(historyPanel!!, CARD_HISTORY)
            } else {
                historyPanel!!.refresh()
            }
            layout.show(this, CARD_HISTORY)
            showingHistory = true
        }
    }

    override fun dispose() {
        chatPanel.dispose()
    }

    companion object {
        private const val CARD_CHAT = "chat"
        private const val CARD_HISTORY = "history"
    }
}
