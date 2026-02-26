@file:OptIn(ExperimentalJewelApi::class)

package ru.dsudomoin.claudecodegui.ui.compose.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

private val log = Logger.getInstance("ComposeMarkdownContent")

/**
 * Renders Markdown text using Jewel's native Markdown composable.
 *
 * Must be placed inside [ProvideMarkdownStyling] (set up by [createThemedComposePanel]).
 *
 * Compose equivalent of [ru.dsudomoin.claudecodegui.ui.common.MarkdownRenderer].
 */
@Composable
fun ComposeMarkdownContent(
    markdown: String,
    onUrlClick: (String) -> Unit = {},
    selectable: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (markdown.isBlank()) return

    val colors = LocalClaudeColors.current

    LaunchedEffect(markdown) {
        log.info("ComposeMarkdownContent: len=${markdown.length}, blank=${markdown.isBlank()}, first200='${markdown.take(200)}'")
    }

    Markdown(
        markdown = markdown,
        modifier = modifier.fillMaxWidth(),
        selectable = selectable,
        onUrlClick = onUrlClick,
    )
}
