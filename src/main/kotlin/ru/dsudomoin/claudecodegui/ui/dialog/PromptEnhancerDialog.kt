package ru.dsudomoin.claudecodegui.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.UcuBundle
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * Dialog showing the original and enhanced prompts side by side.
 * OK = "Use Enhanced", Cancel = "Keep Original".
 */
class PromptEnhancerDialog(
    project: Project?,
    private val originalPrompt: String,
    private val enhancedPrompt: String
) : DialogWrapper(project) {

    companion object {
        private val ORIGINAL_BG = JBColor(0xF5F5F5, 0x2B2B2B)
        private val ENHANCED_BG = JBColor(0xE8F0FE, 0x1A2A3A)
        private val LABEL_COLOR = JBColor(0x666666, 0x999999)
        private val BORDER_COLOR = JBColor(0xD0D0D0, 0x3E3E42)
    }

    init {
        title = UcuBundle.message("enhancer.title")
        setOKButtonText(UcuBundle.message("enhancer.useEnhanced"))
        setCancelButtonText(UcuBundle.message("enhancer.keepOriginal"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            preferredSize = Dimension(JBUI.scale(550), JBUI.scale(400))
            border = JBUI.Borders.empty(8)
        }

        val topSection = createPromptSection(
            UcuBundle.message("enhancer.original"),
            originalPrompt,
            ORIGINAL_BG
        )
        val bottomSection = createPromptSection(
            UcuBundle.message("enhancer.enhanced"),
            enhancedPrompt,
            ENHANCED_BG
        )

        // Split vertically: original on top, enhanced on bottom
        val splitPanel = JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            isOpaque = false
            add(topSection, BorderLayout.NORTH)
            add(bottomSection, BorderLayout.CENTER)
        }

        panel.add(splitPanel, BorderLayout.CENTER)
        return panel
    }

    private fun createPromptSection(label: String, text: String, bg: JBColor): JPanel {
        val section = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
        }

        val header = JBLabel(label).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            foreground = LABEL_COLOR
        }

        val textArea = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(13))
            background = bg
            border = JBUI.Borders.empty(10)
        }

        val scrollPane = JBScrollPane(textArea).apply {
            border = BorderFactory.createLineBorder(BORDER_COLOR, 1)
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(0, JBUI.scale(150))
        }

        section.add(header, BorderLayout.NORTH)
        section.add(scrollPane, BorderLayout.CENTER)
        return section
    }
}
