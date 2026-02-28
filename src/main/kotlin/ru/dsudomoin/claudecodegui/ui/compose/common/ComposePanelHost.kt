@file:OptIn(ExperimentalJewelApi::class)

package ru.dsudomoin.claudecodegui.ui.compose.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.TextUnit
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.markdown.bridge.create
import org.jetbrains.jewel.intui.markdown.bridge.styling.create
import org.jetbrains.jewel.intui.markdown.bridge.styling.extensions.github.tables.create
import org.jetbrains.jewel.markdown.extensions.github.tables.GfmTableColors
import org.jetbrains.jewel.markdown.extensions.github.tables.GfmTableStyling
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableRendererExtension
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import ru.dsudomoin.claudecodegui.ui.compose.theme.ClaudeComposeTheme
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors
import javax.swing.JComponent

/**
 * Creates a [JComponent] backed by a Compose panel, pre-configured with
 * Jewel IDE theme bridge, [ClaudeComposeTheme], and Jewel Markdown styling
 * (including GFM table support and inline code size fix).
 *
 * Use this factory for every Compose panel embedded in existing Swing containers.
 */
fun createThemedComposePanel(content: @Composable () -> Unit): JComponent {
    return JewelComposePanel(focusOnClickInside = true) {
        ClaudeComposeTheme {
            ConfiguredMarkdownStyling {
                content()
            }
        }
    }
}

/**
 * Sets up Jewel Markdown styling with:
 * - Fixed inline code font size (same as surrounding text)
 * - GFM table support (processor + renderer extensions)
 *
 * Everything is passed to a single [ProvideMarkdownStyling] call
 * to ensure the bridge context (mode, code highlighter, etc.) is preserved.
 */
@Composable
private fun ConfiguredMarkdownStyling(content: @Composable () -> Unit) {
    val colors = LocalClaudeColors.current

    // 1. Create base bridge styling
    val baseStyling = MarkdownStyling.create()

    // 2. Fix inline code size — use Unspecified so it inherits surrounding font size
    val baseInlines = baseStyling.baseInlinesStyling
    val fixedTextStyle = baseInlines.textStyle.copy(color = colors.textPrimary)
    val fixedInlineCode = baseInlines.inlineCode.copy(fontSize = TextUnit.Unspecified)
    val fixedInlines = InlinesStyling(
        textStyle = fixedTextStyle,
        inlineCode = fixedInlineCode,
        link = baseInlines.link,
        linkDisabled = baseInlines.linkDisabled,
        linkFocused = baseInlines.linkFocused,
        linkHovered = baseInlines.linkHovered,
        linkPressed = baseInlines.linkPressed,
        linkVisited = baseInlines.linkVisited,
        emphasis = baseInlines.emphasis,
        strongEmphasis = baseInlines.strongEmphasis,
        inlineHtml = baseInlines.inlineHtml,
    )
    val fixedBlockQuote = MarkdownStyling.BlockQuote.create(
        lineColor = colors.quoteBorder,
        textColor = colors.textPrimary,
    )
    val fixedStyling = MarkdownStyling.create(
        inlinesStyling = fixedInlines,
        blockQuote = fixedBlockQuote,
    )

    // 3. Create GFM table extensions — wrapped in remember to avoid
    //    recomposition issues (MarkdownProcessor has internal mutable state)
    val tableStyling = remember(colors.borderNormal, colors.surfacePrimary, colors.surfaceSecondary) {
        GfmTableStyling.create(
            colors = GfmTableColors.create(
                borderColor = colors.borderNormal,
                rowBackgroundColor = colors.surfacePrimary,
                alternateRowBackgroundColor = colors.surfaceSecondary,
            ),
        )
    }
    val tableRendererExt = remember(tableStyling, fixedStyling) {
        GitHubTableRendererExtension(tableStyling, fixedStyling)
    }
    val processor = remember { MarkdownProcessor(listOf(GitHubTableProcessorExtension)) }
    val blockRenderer = remember(fixedStyling, tableRendererExt) {
        MarkdownBlockRenderer.create(fixedStyling, listOf(tableRendererExt))
    }

    // 4. Provide everything in one call — preserves bridge context
    ProvideMarkdownStyling(
        markdownStyling = fixedStyling,
        markdownProcessor = processor,
        markdownBlockRenderer = blockRenderer,
    ) {
        content()
    }
}
