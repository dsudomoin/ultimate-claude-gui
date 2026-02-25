package ru.dsudomoin.claudecodegui.ui.compose.toolwindow

import ru.dsudomoin.claudecodegui.ui.compose.chat.ChatViewModel
import ru.dsudomoin.claudecodegui.ui.compose.common.createThemedComposePanel
import javax.swing.JComponent

/**
 * Factory that creates the Compose chat panel as a regular [JComponent].
 *
 * This isolates the `@Composable` lambda from non-Compose Swing code,
 * keeping all Compose compiler–generated references (e.g. `ComposableLambdaKt`)
 * inside the `ui.compose` package.
 */
fun createComposeChatPanel(viewModel: ChatViewModel, callbacks: ChatCallbacks): JComponent {
    return createThemedComposePanel {
        ComposeChatContainer(
            viewModel = viewModel,
            callbacks = callbacks,
        )
    }
}
