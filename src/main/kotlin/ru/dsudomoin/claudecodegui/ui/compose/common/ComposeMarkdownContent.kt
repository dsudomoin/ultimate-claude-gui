@file:OptIn(ExperimentalJewelApi::class)

package ru.dsudomoin.claudecodegui.ui.compose.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.Markdown

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

    Markdown(
        markdown = markdown,
        modifier = modifier.fillMaxWidth(),
        selectable = selectable,
        onUrlClick = onUrlClick,
    )
}
