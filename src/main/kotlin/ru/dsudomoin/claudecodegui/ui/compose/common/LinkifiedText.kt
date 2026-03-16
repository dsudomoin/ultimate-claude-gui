package ru.dsudomoin.claudecodegui.ui.compose.common

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

private val URL_REGEX = Regex(
    """https?://[^\s<>\[\](){}'"`,;!]+(?<![.,;:!?)])"""
)

/**
 * Text composable that detects URLs and makes them clickable.
 *
 * Used in user message bubbles where full markdown rendering is not needed,
 * but links should still be interactive.
 */
@Composable
fun LinkifiedText(
    text: String,
    style: TextStyle,
    linkColor: Color,
    onUrlClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(text, style, linkColor) {
        buildLinkAnnotatedString(text, linkColor, onUrlClick)
    }

    BasicText(
        text = annotated,
        style = style,
        modifier = modifier,
    )
}

private fun buildLinkAnnotatedString(
    text: String,
    linkColor: Color,
    onUrlClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    append(text)

    URL_REGEX.findAll(text).forEach { match ->
        val url = match.value
        addLink(
            clickable = LinkAnnotation.Clickable(
                tag = "URL",
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
                linkInteractionListener = { onUrlClick(url) },
            ),
            start = match.range.first,
            end = match.range.last + 1,
        )
    }
}
