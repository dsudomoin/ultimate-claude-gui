package ru.dsudomoin.claudecodegui.ui.dialog

import ru.dsudomoin.claudecodegui.MyMessageBundle
import com.intellij.ui.JBColor
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Replaces the input area when Claude sends AskUserQuestion.
 * Shows question text, option chips, "Other" with text input, and Submit button.
 */
class QuestionSelectionPanel(
    private val questions: JsonArray,
    private val onSubmit: (JsonObject) -> Unit,
    private val onCancel: () -> Unit
) : JPanel(BorderLayout()) {

    companion object {
        private val BG get() = ThemeColors.surfaceSecondary
        private val BORDER_COLOR get() = ThemeColors.borderNormal
        private val TEXT_PRIMARY get() = ThemeColors.textPrimary
        private val TEXT_SECONDARY get() = ThemeColors.textSecondary
        private val ACCENT get() = ThemeColors.accent
        private val CHIP_BG get() = ThemeColors.chipBg
        private val CHIP_SELECTED_BG get() = ThemeColors.chipSelectedBg
        private val CHIP_HOVER get() = ThemeColors.chipHover
        private val CHIP_BORDER get() = ThemeColors.chipBorder
        private val CHIP_SELECTED_BORDER get() = ThemeColors.chipSelectedBorder
        private val OTHER_BORDER get() = ThemeColors.quoteBorder
        private const val ARC = 8
        private const val OTHER_MARKER = "__OTHER__"
    }

    private var currentQuestionIndex = 0
    // question text → selected options
    private val answers = mutableMapOf<String, MutableSet<String>>()
    // question text → custom input text
    private val customInputs = mutableMapOf<String, String>()
    // whether the "Other" textarea is currently open for editing
    private var otherInputOpen = false

    private val contentPanel = JPanel(BorderLayout()).apply { isOpaque = false }

    init {
        isOpaque = true
        background = BG
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR),
            JBUI.Borders.empty(8, 12)
        )
        add(contentPanel, BorderLayout.CENTER)
        rebuildForCurrentQuestion()
    }

    private fun currentQuestion(): JsonObject? {
        if (currentQuestionIndex >= questions.size) return null
        return questions[currentQuestionIndex].jsonObject
    }

    private fun rebuildForCurrentQuestion() {
        contentPanel.removeAll()
        val q = currentQuestion() ?: return

        val questionText = q["question"]?.jsonPrimitive?.contentOrNull ?: ""
        val header = q["header"]?.jsonPrimitive?.contentOrNull ?: ""
        val options = q["options"]?.jsonArray ?: JsonArray(emptyList())
        val multiSelect = q["multiSelect"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

        val selected = answers.getOrPut(questionText) { mutableSetOf() }

        // ── Top: header chip + question text ──
        val topPanel = JPanel(BorderLayout()).apply { isOpaque = false }

        val headerLine = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }
        if (header.isNotEmpty()) {
            headerLine.add(JLabel(header).apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                foreground = ACCENT
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ACCENT, 1),
                    JBUI.Borders.empty(1, 6, 1, 6)
                )
            })
        }
        if (questions.size > 1) {
            headerLine.add(JLabel("${currentQuestionIndex + 1} / ${questions.size}").apply {
                font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                foreground = TEXT_SECONDARY
            })
        }
        topPanel.add(headerLine, BorderLayout.NORTH)

        topPanel.add(JLabel("<html><body style='width:400px'>${questionText}</body></html>").apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(13).toFloat())
            foreground = TEXT_PRIMARY
            border = JBUI.Borders.empty(2, 0, 8, 0)
        }, BorderLayout.CENTER)

        contentPanel.add(topPanel, BorderLayout.NORTH)

        // ── Center: options grid ──
        val optionsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 8, 0)
        }

        for (opt in options) {
            val obj = opt.jsonObject
            val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: continue
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
            val isSelected = label in selected

            optionsPanel.add(createOptionChip(label, desc, isSelected, multiSelect) {
                if (multiSelect) {
                    if (label in selected) selected.remove(label) else selected.add(label)
                } else {
                    selected.clear()
                    selected.add(label)
                    selected.remove(OTHER_MARKER)
                }
                rebuildForCurrentQuestion()
            })
            optionsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        // ── "Other" option ──
        val isOtherSelected = OTHER_MARKER in selected
        val customText = customInputs[questionText]?.trim() ?: ""
        val otherDesc = if (isOtherSelected && customText.isNotEmpty() && !otherInputOpen) {
            MyMessageBundle.message("question.otherAnswer", customText)
        } else {
            MyMessageBundle.message("question.otherDesc")
        }
        optionsPanel.add(createOptionChip(
            label = MyMessageBundle.message("question.otherLabel"),
            desc = otherDesc,
            isSelected = isOtherSelected,
            multiSelect = multiSelect
        ) {
            if (multiSelect) {
                if (OTHER_MARKER in selected) {
                    selected.remove(OTHER_MARKER)
                    otherInputOpen = false
                } else {
                    selected.add(OTHER_MARKER)
                    otherInputOpen = true
                }
            } else {
                selected.clear()
                selected.add(OTHER_MARKER)
                otherInputOpen = true
            }
            rebuildForCurrentQuestion()
        })

        // ── Custom input textarea (only when Other is selected AND input is open) ──
        if (isOtherSelected && otherInputOpen) {
            optionsPanel.add(Box.createVerticalStrut(JBUI.scale(6)))
            val textField = JTextArea(customInputs[questionText] ?: "").apply {
                rows = 2
                lineWrap = true
                wrapStyleWord = true
                font = font.deriveFont(Font.PLAIN, JBUI.scale(13).toFloat())
                foreground = TEXT_PRIMARY
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ACCENT, 1),
                    JBUI.Borders.empty(6, 8)
                )
            }
            textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = save()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = save()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = save()
                fun save() { customInputs[questionText] = textField.text }
            })
            textField.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when {
                        e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown -> {
                            e.consume()
                            textField.insert("\n", textField.caretPosition)
                        }
                        e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown -> {
                            e.consume()
                            // Close textarea, show answer in chip
                            customInputs[questionText] = textField.text
                            otherInputOpen = false
                            rebuildForCurrentQuestion()
                        }
                    }
                }
            })
            val wrapper = JPanel(BorderLayout()).apply {
                isOpaque = false
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(60))
                add(JScrollPane(textField).apply {
                    border = JBUI.Borders.empty()
                    preferredSize = Dimension(0, JBUI.scale(50))
                }, BorderLayout.CENTER)
            }
            optionsPanel.add(wrapper)
            SwingUtilities.invokeLater {
                textField.requestFocusInWindow()
                SwingUtilities.invokeLater {
                    val sp = optionsPanel.parent?.parent as? JScrollPane
                    if (sp != null) {
                        sp.verticalScrollBar.value = sp.verticalScrollBar.maximum
                    }
                }
            }
        }

        val scrollPane = JScrollPane(optionsPanel).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(200))
            preferredSize = Dimension(0, JBUI.scale(160))
        }
        contentPanel.add(scrollPane, BorderLayout.CENTER)

        // ── Bottom: Cancel + Submit ──
        val bottomPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 0, 0)
        }

        bottomPanel.add(createActionButton(MyMessageBundle.message("question.cancel"), false) { onCancel() })

        val isLast = currentQuestionIndex >= questions.size - 1
        val canProceed = canProceedCurrent()
        val submitLabel = if (isLast) MyMessageBundle.message("question.submit") else MyMessageBundle.message("question.next")
        bottomPanel.add(createActionButton(submitLabel, true, enabled = canProceed) {
            if (isLast) {
                submitAll()
            } else {
                currentQuestionIndex++
                rebuildForCurrentQuestion()
            }
        })

        contentPanel.add(bottomPanel, BorderLayout.SOUTH)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun canProceedCurrent(): Boolean {
        val q = currentQuestion() ?: return false
        val questionText = q["question"]?.jsonPrimitive?.contentOrNull ?: return false
        val selected = answers[questionText] ?: return false
        val hasRegular = selected.any { it != OTHER_MARKER }
        val hasOtherInput = OTHER_MARKER in selected &&
                (customInputs[questionText]?.isNotBlank() == true)
        return hasRegular || hasOtherInput
    }

    private fun submitAll() {
        val answersJson = buildJsonObject {
            for ((questionText, selected) in answers) {
                val labels = selected.filter { it != OTHER_MARKER }.toMutableList()
                if (OTHER_MARKER in selected) {
                    val custom = customInputs[questionText]?.trim()
                    if (!custom.isNullOrEmpty()) labels.add(custom)
                }
                if (labels.size == 1) {
                    put(questionText, labels[0])
                } else if (labels.size > 1) {
                    put(questionText, buildJsonArray { labels.forEach { add(JsonPrimitive(it)) } })
                }
            }
        }
        val updatedInput = buildJsonObject {
            put("questions", questions)
            put("answers", answersJson)
        }
        onSubmit(updatedInput)
    }

    // ── Option chip ──

    private fun createOptionChip(
        label: String,
        desc: String,
        isSelected: Boolean,
        multiSelect: Boolean,
        onClick: () -> Unit
    ): JComponent {
        return object : JPanel(BorderLayout()) {
            private var hover = false
            init {
                isOpaque = false
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(48))
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(4, 8, 4, 8)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                    override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                    override fun mouseClicked(e: MouseEvent) { onClick() }
                })
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(ARC).toFloat()
                val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc)
                g2.color = if (isSelected) CHIP_SELECTED_BG else if (hover) CHIP_HOVER else CHIP_BG
                g2.fill(shape)
                g2.color = if (isSelected) CHIP_SELECTED_BORDER else CHIP_BORDER
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)
            }
        }.apply {
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
            }
            // Radio/checkbox icon
            val icon = if (multiSelect) {
                if (isSelected) "\u2611" else "\u2610"
            } else {
                if (isSelected) "\u25C9" else "\u25CB"
            }
            leftPanel.add(JLabel(icon).apply {
                foreground = if (isSelected) ACCENT else TEXT_SECONDARY
                font = font.deriveFont(Font.PLAIN, JBUI.scale(14).toFloat())
            })
            val textPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
            textPanel.add(JLabel(label).apply {
                foreground = TEXT_PRIMARY
                font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
                alignmentX = Component.LEFT_ALIGNMENT
            })
            if (desc.isNotEmpty()) {
                textPanel.add(JLabel(desc).apply {
                    foreground = TEXT_SECONDARY
                    font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
            leftPanel.add(textPanel)
            add(leftPanel, BorderLayout.CENTER)
        }
    }



    // ── Action buttons ──

    private fun createActionButton(text: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit): JComponent {
        return object : JPanel() {
            private var hover = false
            init {
                isOpaque = false
                preferredSize = Dimension(JBUI.scale(90), JBUI.scale(30))
                cursor = if (enabled) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { if (enabled) { hover = true; repaint() } }
                    override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                    override fun mouseClicked(e: MouseEvent) { if (enabled) onClick() }
                })
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val arc = JBUI.scale(6).toFloat()
                val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc)
                if (primary && enabled) {
                    g2.color = if (hover) ACCENT.brighter() else ACCENT
                    g2.fill(shape)
                    g2.color = Color.WHITE
                } else {
                    g2.color = if (hover) CHIP_HOVER else CHIP_BG
                    g2.fill(shape)
                    g2.color = CHIP_BORDER
                    g2.draw(shape)
                    g2.color = if (enabled) TEXT_PRIMARY else TEXT_SECONDARY
                }
                val fm = g2.getFontMetrics(font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat()))
                g2.font = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
                val tx = (width - fm.stringWidth(text)) / 2
                val ty = (height + fm.ascent - fm.descent) / 2
                g2.drawString(text, tx, ty)
            }
        }
    }
}
