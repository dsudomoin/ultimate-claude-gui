# Промпт для создания IntelliJ-плагина Claude Code GUI (Kotlin-версия)

## Контекст

Ты — опытный разработчик IntelliJ-плагинов. Тебе нужно создать IntelliJ IDEA плагин для интеграции с AI-движками (Claude Code, OpenAI Codex) **с нуля**, используя **нативный стек IntelliJ Platform**: Kotlin, Coroutines, Swing/IntelliJ UI DSL. Плагин должен полностью заменить существующую реализацию на Java + JCEF (Chromium webview) + React + Node.js bridge, устранив архитектурные слабости и значительно повысив производительность, стабильность и удобство поддержки.

---

## Почему переписываем

Текущая реализация имеет следующие проблемы:

1. **JCEF (Chromium) тяжеловесен** — каждая вкладка чата поднимает отдельный браузерный процесс (~150–300 MB RAM), случаются крэши, OSR-рендеринг нестабилен на разных ОС.
2. **Node.js bridge** — для каждого сообщения спавнится отдельный Node-процесс (`node channel-manager.js`), IPC через stdin/stdout — хрупко, медленно, сложно отлаживать.
3. **Три рантайма** (JVM + Chromium + Node.js) — огромный overhead по памяти, CPU и времени холодного старта.
4. **React/TypeScript/Ant Design webview** (~214 файлов) — избыточен для tool window IDE, проблемы с масштабированием шрифтов, синхронизацией тем, accessibility.
5. **JSON IPC через JS↔Java мост** — теряются типы, легко рассинхронизировать протокол, сложная отладка.
6. **Двойное управление состоянием** — React state + Java session state должны быть синхронизированы вручную.

---

## Целевая архитектура

### Стек технологий

| Слой | Технология |
|------|-----------|
| Язык | **Kotlin 2.x** (100% Kotlin, без Java) |
| Асинхронность | **Kotlin Coroutines** + `Flow` для стриминга |
| UI | **IntelliJ Platform UI** — Swing + IntelliJ UI DSL 2.0 (`com.intellij.ui.dsl.builder`) |
| Рендеринг Markdown | **IntelliJ Markdown** (`org.intellij.plugins.markdown`) или встроенный `JEditorPane`/`JBHtmlPane` с подсветкой синтаксиса через IntelliJ `EditorHighlighter` |
| Подсветка кода | **IntelliJ Editor API** — встроенные `EditorFragment` для code blocks |
| HTTP-клиент | **Ktor Client** или **OkHttp** (для прямых API-вызовов к Anthropic/OpenAI) |
| Сериализация | **kotlinx.serialization** (JSON) |
| DI | **IntelliJ Service API** (`@Service`, `service<T>()`) |
| Хранение | **IntelliJ PersistentStateComponent** для настроек, файловая система для истории |
| Diff | **IntelliJ DiffManager API** (нативный diff viewer) |
| MCP | Прямая реализация на Kotlin (stdio/SSE/HTTP транспорты) |
| Процессы | **`GeneralCommandLine` + `ProcessHandler`** из IntelliJ Platform |

### Ключевое отличие от текущей версии

**Нет JCEF, нет Node.js, нет React.** Весь UI — нативный Swing через IntelliJ Platform API. Все сетевые вызовы — напрямую из JVM через HTTP-клиент. MCP-серверы управляются напрямую из Kotlin.

---

## Структура проекта

```
src/main/kotlin/com/github/claudecodegui/
├── plugin/                          # Plugin lifecycle
│   ├── ClaudeCodePlugin.kt         # Plugin entry point
│   └── StartupActivity.kt          # Post-startup initialization
│
├── core/                            # Core domain
│   ├── model/                       # Domain models
│   │   ├── Message.kt              # Chat message (role, content blocks, metadata)
│   │   ├── ContentBlock.kt         # Text, Code, ToolUse, ToolResult, Thinking
│   │   ├── Conversation.kt         # Conversation state (messages, sessionId, metadata)
│   │   ├── Provider.kt             # sealed class: Claude, Codex
│   │   ├── ToolCall.kt             # Tool invocation model
│   │   ├── Permission.kt           # Permission request/decision
│   │   └── McpServer.kt            # MCP server config
│   │
│   ├── session/                     # Session management
│   │   ├── SessionManager.kt       # Create/load/switch/delete sessions
│   │   ├── SessionStorage.kt       # JSONL persistence
│   │   └── SessionState.kt         # Reactive session state (StateFlow)
│   │
│   └── context/                     # IDE context collection
│       ├── EditorContextCollector.kt    # Active file, selection, caret position
│       ├── JavaContextCollector.kt      # Java/Kotlin PSI analysis
│       ├── PythonContextCollector.kt    # Python PSI analysis
│       └── ProjectContextCollector.kt   # Git status, project structure
│
├── provider/                        # AI Provider abstraction
│   ├── AiProvider.kt               # interface: sendMessage(), streamResponse()
│   ├── ProviderFactory.kt          # Create provider by type
│   │
│   ├── claude/                      # Claude implementation
│   │   ├── ClaudeProvider.kt       # Anthropic Messages API client
│   │   ├── ClaudeStreamParser.kt   # SSE stream parser → Flow<StreamEvent>
│   │   ├── ClaudeToolExecutor.kt   # Execute tools (Write, Edit, Bash, etc.)
│   │   └── ClaudeModels.kt         # Available models enum
│   │
│   └── codex/                       # Codex implementation
│       ├── CodexProvider.kt         # OpenAI Chat Completions API client
│       ├── CodexStreamParser.kt     # SSE stream parser → Flow<StreamEvent>
│       └── CodexModels.kt          # Available models enum
│
├── tool/                            # Tool system (agentic)
│   ├── Tool.kt                     # sealed interface for all tools
│   ├── ToolRegistry.kt             # Register and lookup tools
│   ├── ToolExecutor.kt             # Execute tool with permission check
│   │
│   ├── impl/                        # Built-in tool implementations
│   │   ├── ReadFileTool.kt
│   │   ├── WriteFileTool.kt
│   │   ├── EditFileTool.kt
│   │   ├── BashTool.kt
│   │   ├── GlobTool.kt
│   │   ├── GrepTool.kt
│   │   ├── WebFetchTool.kt
│   │   └── SubagentTool.kt
│   │
│   └── permission/                  # Permission system
│       ├── PermissionService.kt     # Check/request permissions
│       ├── PermissionRule.kt        # Rules: always allow, always deny, ask
│       └── PermissionStorage.kt     # Persist permission decisions
│
├── mcp/                             # MCP (Model Context Protocol) client
│   ├── McpClient.kt                # Main client: connect, list tools, call tool
│   ├── McpTransport.kt             # sealed class: Stdio, Sse, Http
│   ├── McpStdioTransport.kt        # Stdio transport implementation
│   ├── McpSseTransport.kt          # SSE transport implementation
│   ├── McpHttpTransport.kt         # HTTP transport implementation
│   ├── McpServerManager.kt         # Lifecycle: start, stop, validate servers
│   └── McpProtocol.kt              # JSON-RPC protocol messages
│
├── ui/                              # UI layer (all native Swing/IntelliJ)
│   ├── toolwindow/
│   │   ├── ClaudeToolWindowFactory.kt   # ToolWindowFactory registration
│   │   └── ClaudeToolWindow.kt          # Main tool window with tabs
│   │
│   ├── chat/                        # Chat panel
│   │   ├── ChatPanel.kt            # Main chat panel (message list + input)
│   │   ├── MessageListPanel.kt     # Scrollable message list (virtual/lazy)
│   │   ├── MessageBubble.kt        # Single message render (markdown + code)
│   │   ├── CodeBlockPanel.kt       # Code block with syntax highlighting
│   │   ├── ThinkingPanel.kt        # Collapsible thinking/reasoning block
│   │   ├── ToolCallPanel.kt        # Tool invocation display
│   │   └── StreamingIndicator.kt   # Typing/streaming animation
│   │
│   ├── input/                       # Input area
│   │   ├── ChatInputPanel.kt       # Multi-line input with attachments
│   │   ├── AttachmentBar.kt        # File/image attachment chips
│   │   ├── SlashCommandPopup.kt    # Autocomplete for /commands
│   │   ├── FileReferencePopup.kt   # Autocomplete for @file references
│   │   └── ModelSelector.kt        # Provider + model dropdown
│   │
│   ├── dialog/                      # Dialogs
│   │   ├── PermissionDialog.kt     # Tool permission approval
│   │   ├── PlanApprovalDialog.kt   # Plan review and approval
│   │   ├── AskUserDialog.kt        # AI asks user a question
│   │   └── RewindDialog.kt         # Conversation rewind selection
│   │
│   ├── diff/                        # Diff integration
│   │   ├── InlineDiffPanel.kt      # Inline diff preview in chat
│   │   └── DiffApplyAction.kt      # Apply/discard diff actions
│   │
│   ├── settings/                    # Settings UI
│   │   ├── ClaudeSettingsConfigurable.kt  # Settings page entry
│   │   ├── ProviderSettingsPanel.kt       # API keys, models, endpoints
│   │   ├── McpSettingsPanel.kt            # MCP server management
│   │   ├── PermissionSettingsPanel.kt     # Permission rules
│   │   ├── PromptSettingsPanel.kt         # System prompts
│   │   └── AppearanceSettingsPanel.kt     # Theme, font, language
│   │
│   ├── status/                      # Status panel
│   │   ├── StatusPanel.kt          # Multi-tab status (tasks, agents, changes)
│   │   ├── TasksTab.kt             # Current tasks
│   │   ├── AgentTab.kt             # Subagent execution log
│   │   └── ChangesTab.kt           # File changes list
│   │
│   ├── history/                     # Session history
│   │   ├── HistoryPanel.kt         # Session list with search
│   │   └── SessionPreviewPanel.kt  # Session detail/preview
│   │
│   └── common/                      # Reusable UI components
│       ├── MarkdownRenderer.kt     # Markdown → Swing component tree
│       ├── SyntaxHighlighter.kt    # Code highlighting via IntelliJ
│       ├── AnimatedPanel.kt        # Expandable/collapsible panels
│       ├── TokenCounter.kt         # Token usage display
│       └── ThemeSync.kt            # IDE theme ↔ chat theme sync
│
├── action/                          # IDE Actions
│   ├── SendSelectionAction.kt      # Ctrl+Alt+K — send selection to chat
│   ├── QuickFixAction.kt           # Ctrl+Shift+Q — quick fix with AI
│   ├── SendFilePathAction.kt       # Right-click → send file path
│   ├── GenerateCommitAction.kt     # Generate commit message
│   └── NewTabAction.kt             # Create new chat tab
│
├── service/                         # Application/project services
│   ├── SettingsService.kt          # PersistentStateComponent for config
│   ├── ConversationService.kt      # Manage active conversations
│   ├── ProviderService.kt          # Manage AI provider state
│   ├── DependencyService.kt        # Check/install SDK dependencies
│   └── NotificationService.kt      # IDE notifications
│
├── config/                          # Configuration
│   ├── PluginConfig.kt             # Plugin-wide config data class
│   ├── ProviderConfig.kt           # Per-provider config (API key, model, etc.)
│   └── McpConfig.kt                # MCP server configurations
│
└── util/                            # Utilities
    ├── CoroutineScopes.kt          # Plugin-scoped coroutine contexts
    ├── JsonUtils.kt                # kotlinx.serialization helpers
    ├── FileUtils.kt                # VirtualFile helpers
    ├── GitUtils.kt                 # Git operations via IntelliJ VCS API
    └── PlatformUtils.kt           # OS detection, path resolution
```

---

## Детальная спецификация ключевых компонентов

### 1. AI Provider Layer

```kotlin
// provider/AiProvider.kt
interface AiProvider {
    val id: String
    val displayName: String
    val availableModels: List<AiModel>

    /**
     * Отправить сообщение и получить стрим ответа.
     * Flow эмитит StreamEvent по мере получения токенов от API.
     */
    fun sendMessage(
        conversation: Conversation,
        config: ProviderConfig,
        tools: List<ToolDefinition> = emptyList(),
        mcpTools: List<McpToolDefinition> = emptyList()
    ): Flow<StreamEvent>

    fun cancelCurrentRequest()
}

// Стрим-события
sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class ThinkingDelta(val text: String) : StreamEvent()
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : StreamEvent()
    data class ToolResult(val id: String, val content: String, val isError: Boolean) : StreamEvent()
    data class Usage(val inputTokens: Int, val outputTokens: Int, val cacheRead: Int = 0) : StreamEvent()
    data class Error(val message: String, val code: String? = null) : StreamEvent()
    data object StreamStart : StreamEvent()
    data object StreamEnd : StreamEvent()
    data object MessageStop : StreamEvent()
}
```

#### Claude Provider — прямые HTTP-вызовы

```kotlin
// provider/claude/ClaudeProvider.kt
class ClaudeProvider(private val httpClient: HttpClient) : AiProvider {

    override fun sendMessage(
        conversation: Conversation,
        config: ProviderConfig,
        tools: List<ToolDefinition>,
        mcpTools: List<McpToolDefinition>
    ): Flow<StreamEvent> = flow {
        val request = buildAnthropicRequest(conversation, config, tools + mcpTools)

        // SSE streaming через Ktor/OkHttp
        httpClient.preparePost("${config.baseUrl}/v1/messages") {
            header("x-api-key", config.apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(request))
        }.execute { response ->
            val channel = response.bodyAsChannel()
            // Парсим SSE: data: {...}\n\n
            channel.parseSSE().collect { event ->
                emit(parseStreamEvent(event))
            }
        }
    }.flowOn(Dispatchers.IO)
}
```

> **Важно**: Никакого Node.js. Прямые HTTP-вызовы к `api.anthropic.com` через Ktor Client с поддержкой SSE-стриминга. То же самое для OpenAI.

### 2. Agentic Loop (Tool Execution)

```kotlin
// tool/AgenticLoop.kt
class AgenticLoop(
    private val provider: AiProvider,
    private val toolExecutor: ToolExecutor,
    private val permissionService: PermissionService,
    private val scope: CoroutineScope
) {
    /**
     * Основной цикл агента:
     * 1. Отправить сообщение → получить ответ
     * 2. Если ответ содержит tool_use → выполнить инструмент
     * 3. Добавить tool_result → отправить снова
     * 4. Повторять пока AI не завершит (stop_reason != "tool_use")
     */
    fun run(
        conversation: Conversation,
        config: ProviderConfig,
        onEvent: suspend (StreamEvent) -> Unit
    ): Flow<AgentEvent> = flow {
        var currentConversation = conversation
        var continueLoop = true

        while (continueLoop) {
            val toolCalls = mutableListOf<ToolCall>()

            provider.sendMessage(currentConversation, config, getTools())
                .collect { event ->
                    onEvent(event)
                    when (event) {
                        is StreamEvent.ToolUse -> toolCalls.add(ToolCall(event.id, event.name, event.input))
                        is StreamEvent.MessageStop -> continueLoop = false
                        is StreamEvent.StreamEnd -> {
                            if (toolCalls.isNotEmpty()) {
                                // Выполнить все tool calls
                                val results = toolCalls.map { call ->
                                    executeToolWithPermission(call)
                                }
                                // Добавить результаты в conversation и продолжить
                                currentConversation = currentConversation.addToolResults(results)
                                continueLoop = true
                            }
                        }
                        else -> {}
                    }
                }
        }
    }

    private suspend fun executeToolWithPermission(call: ToolCall): ToolResult {
        // Проверить разрешение
        val decision = permissionService.checkPermission(call)
        return when (decision) {
            PermissionDecision.ALLOW -> toolExecutor.execute(call)
            PermissionDecision.DENY -> ToolResult(call.id, "Permission denied by user", isError = true)
            PermissionDecision.ASK -> {
                // Показать диалог и ждать решения пользователя
                val userDecision = permissionService.requestUserDecision(call)
                if (userDecision.allowed) toolExecutor.execute(call)
                else ToolResult(call.id, "Permission denied by user", isError = true)
            }
        }
    }
}
```

### 3. UI — Chat Panel (нативный Swing)

```kotlin
// ui/chat/ChatPanel.kt
class ChatPanel(
    private val project: Project,
    private val session: SessionState,
    private val scope: CoroutineScope
) : JBPanel<ChatPanel>(BorderLayout()) {

    private val messageList = MessageListPanel(project)
    private val inputPanel = ChatInputPanel(project, ::onSendMessage)
    private val statusPanel = StatusPanel()
    private val splitter = OnePixelSplitter(true, 0.75f).apply {
        firstComponent = JBScrollPane(messageList).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        secondComponent = inputPanel
    }

    init {
        add(splitter, BorderLayout.CENTER)
        add(statusPanel, BorderLayout.SOUTH)

        // Подписка на изменения сессии через StateFlow
        scope.launch {
            session.messages.collect { messages ->
                withContext(Dispatchers.EDT) {
                    messageList.updateMessages(messages)
                }
            }
        }

        scope.launch {
            session.streamingContent.collect { delta ->
                withContext(Dispatchers.EDT) {
                    messageList.appendStreamingDelta(delta)
                }
            }
        }
    }

    private fun onSendMessage(text: String, attachments: List<Attachment>) {
        scope.launch {
            session.sendMessage(text, attachments)
        }
    }
}
```

#### Рендеринг сообщений

```kotlin
// ui/chat/MessageBubble.kt
class MessageBubble(
    private val project: Project,
    private val message: Message
) : JBPanel<MessageBubble>(VerticalLayout(JBUI.scale(4))) {

    init {
        border = JBUI.Borders.empty(8, 12)
        background = when (message.role) {
            Role.USER -> JBColor.namedColor("Claude.UserBubble", JBColor(0xE3F2FD, 0x1A3A4A))
            Role.ASSISTANT -> JBColor.namedColor("Claude.AssistantBubble", JBColor(0xFFFFFF, 0x2B2D30))
        }

        message.content.forEach { block ->
            add(renderContentBlock(block))
        }
    }

    private fun renderContentBlock(block: ContentBlock): JComponent = when (block) {
        is ContentBlock.Text -> MarkdownRenderer.render(project, block.text)
        is ContentBlock.Code -> CodeBlockPanel(project, block.code, block.language)
        is ContentBlock.ToolUse -> ToolCallPanel(block.toolName, block.input, block.result)
        is ContentBlock.Thinking -> ThinkingPanel(block.text)
        is ContentBlock.Image -> ImagePanel(block.data)
    }
}
```

#### Подсветка кода — через IntelliJ Editor

```kotlin
// ui/chat/CodeBlockPanel.kt
class CodeBlockPanel(
    private val project: Project,
    private val code: String,
    private val language: String?
) : JBPanel<CodeBlockPanel>(BorderLayout()) {

    init {
        // Создаём read-only Editor fragment с подсветкой
        val fileType = language?.let { FileTypeManager.getInstance().getFileTypeByExtension(it) }
            ?: PlainTextFileType.INSTANCE
        val document = EditorFactory.getInstance().createDocument(code)
        val editor = EditorFactory.getInstance().createViewer(document, project).apply {
            settings.apply {
                isLineNumbersShown = true
                isFoldingOutlineShown = false
                additionalLinesCount = 0
                additionalColumnsCount = 0
                isCaretRowShown = false
            }
            // Применить подсветку по типу файла
            (this as? EditorEx)?.highlighter =
                EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileType)
        }

        // Toolbar: Copy, Apply
        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(ActionButton(CopyCodeAction(code), "Copy"))
            add(ActionButton(ApplyCodeAction(project, code), "Apply"))
        }

        add(toolbar, BorderLayout.NORTH)
        add(editor.component, BorderLayout.CENTER)

        // Dispose editor при удалении панели
        Disposer.register(this) { EditorFactory.getInstance().releaseEditor(editor) }
    }
}
```

### 4. Permission Dialog (нативный IntelliJ)

```kotlin
// ui/dialog/PermissionDialog.kt
class PermissionDialog(
    project: Project,
    private val request: PermissionRequest
) : DialogWrapper(project) {

    private var rememberDecision = false

    init {
        title = "Tool Permission Request"
        setOKButtonText("Allow")
        setCancelButtonText("Deny")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            icon(AllIcons.General.QuestionDialog)
            label("${request.toolName} wants to access:").bold()
        }
        row {
            // Показать деталь — путь к файлу, команду и т.д.
            text(request.description).applyToComponent {
                foreground = JBColor.GRAY
            }
        }
        if (request.filePath != null) {
            row {
                label("File: ${request.filePath}")
            }
        }
        separator()
        row {
            checkBox("Always allow ${request.toolName}")
                .bindSelected(::rememberDecision)
        }
    }

    fun getDecision(): PermissionDecision = when {
        isOK && rememberDecision -> PermissionDecision.ALWAYS_ALLOW
        isOK -> PermissionDecision.ALLOW_ONCE
        else -> PermissionDecision.DENY
    }
}
```

### 5. MCP Client (чистый Kotlin)

```kotlin
// mcp/McpClient.kt
class McpClient(
    private val config: McpServerConfig,
    private val scope: CoroutineScope
) {
    private var transport: McpTransport? = null
    private var requestId = AtomicInteger(0)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    suspend fun connect() {
        transport = when (config.transport) {
            is McpTransportConfig.Stdio -> McpStdioTransport(config.command, config.args, config.env)
            is McpTransportConfig.Sse -> McpSseTransport(config.url)
            is McpTransportConfig.Http -> McpHttpTransport(config.url)
        }
        transport!!.connect()
        // Initialize handshake
        sendRequest("initialize", buildJsonObject {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "claude-code-gui")
                put("version", "1.0.0")
            }
        })
        sendNotification("notifications/initialized")
    }

    suspend fun listTools(): List<McpToolDefinition> {
        val response = sendRequest("tools/list", buildJsonObject {})
        return Json.decodeFromJsonElement(response["tools"]!!)
    }

    suspend fun callTool(name: String, arguments: JsonObject): JsonObject {
        return sendRequest("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
    }

    private suspend fun sendRequest(method: String, params: JsonObject): JsonObject {
        val id = requestId.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred
        transport!!.send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        })
        return withTimeout(30_000) { deferred.await() }
    }

    suspend fun disconnect() {
        transport?.disconnect()
    }
}
```

### 6. Session Management

```kotlin
// core/session/SessionState.kt
class SessionState(
    val sessionId: String,
    private val storage: SessionStorage,
    private val scope: CoroutineScope
) {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _streamingContent = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 64)
    val streamingContent: SharedFlow<StreamEvent> = _streamingContent.asSharedFlow()

    private val _status = MutableStateFlow(SessionStatus.IDLE)
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private var currentJob: Job? = null

    suspend fun sendMessage(text: String, attachments: List<Attachment>) {
        val userMessage = Message.user(text, attachments)
        _messages.update { it + userMessage }
        _status.value = SessionStatus.STREAMING

        currentJob = scope.launch {
            try {
                agenticLoop.run(
                    conversation = Conversation(sessionId, _messages.value),
                    config = providerConfig,
                    onEvent = { event ->
                        _streamingContent.emit(event)
                        when (event) {
                            is StreamEvent.StreamEnd -> {
                                // Финализировать сообщение ассистента
                                _messages.update { it + currentAssistantMessage }
                            }
                            else -> {}
                        }
                    }
                ).collect { agentEvent ->
                    // Handle agentic events (tool calls, etc.)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = SessionStatus.ERROR(e.message ?: "Unknown error")
            } finally {
                _status.value = SessionStatus.IDLE
                // Persist to disk
                storage.save(sessionId, _messages.value)
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
    }

    fun rewindTo(messageIndex: Int) {
        _messages.update { it.take(messageIndex + 1) }
        scope.launch { storage.save(sessionId, _messages.value) }
    }
}
```

### 7. Settings (PersistentStateComponent)

```kotlin
// service/SettingsService.kt
@Service(Service.Level.APP)
@State(
    name = "ClaudeCodeGuiSettings",
    storages = [Storage("claudeCodeGui.xml")]
)
class SettingsService : PersistentStateComponent<SettingsService.State> {

    data class State(
        var claudeApiKey: String = "",
        var claudeBaseUrl: String = "https://api.anthropic.com",
        var claudeModel: String = "claude-sonnet-4-20250514",
        var codexApiKey: String = "",
        var codexBaseUrl: String = "https://api.openai.com",
        var codexModel: String = "gpt-4o",
        var activeProvider: String = "claude",
        var language: String = "en",
        var fontScale: Float = 1.0f,
        var mcpServers: MutableList<McpServerConfig> = mutableListOf(),
        var permissionRules: MutableList<PermissionRule> = mutableListOf(),
        var systemPrompt: String = "",
        var maxTokens: Int = 8192
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }
}
```

### 8. Markdown Renderer

```kotlin
// ui/common/MarkdownRenderer.kt
object MarkdownRenderer {

    /**
     * Рендерит Markdown в дерево Swing-компонентов.
     * Использует commonmark-java для парсинга и кастомный visitor для рендеринга.
     *
     * - Текстовые блоки → JBLabel с HTML
     * - Код с подсветкой → CodeBlockPanel (IntelliJ Editor)
     * - Списки → вертикальные панели с маркерами
     * - Таблицы → JBTable
     * - Ссылки → кликабельные HyperlinkLabel
     */
    fun render(project: Project, markdown: String): JComponent {
        val parser = Parser.builder().build()
        val document = parser.parse(markdown)
        val visitor = SwingNodeVisitor(project)
        document.accept(visitor)
        return visitor.result
    }
}

// Внутренний visitor преобразует AST → Swing-компоненты
class SwingNodeVisitor(private val project: Project) : AbstractVisitor() {
    val result = JBPanel<JBPanel<*>>(VerticalLayout(4))

    override fun visit(fencedCodeBlock: FencedCodeBlock) {
        val lang = fencedCodeBlock.info?.toString()?.trim()
        val code = fencedCodeBlock.literal.trimEnd()
        result.add(CodeBlockPanel(project, code, lang))
    }

    override fun visit(paragraph: Paragraph) {
        val html = HtmlRenderer.builder().build().render(paragraph)
        result.add(JBLabel("<html>$html</html>"))
    }

    // ... другие visit-методы для списков, таблиц, ссылок и т.д.
}
```

### 9. Tool Window Factory

```kotlin
// ui/toolwindow/ClaudeToolWindowFactory.kt
class ClaudeToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Disposer.register(toolWindow.disposable) { scope.cancel() }

        val contentFactory = ContentFactory.getInstance()
        val sessionManager = project.service<SessionManager>()

        // Создать первую вкладку
        val session = sessionManager.createSession()
        val chatPanel = ChatPanel(project, session, scope)
        val content = contentFactory.createContent(chatPanel, "Chat 1", false).apply {
            isCloseable = true
            setDisposer(chatPanel)
        }
        toolWindow.contentManager.addContent(content)

        // Добавить кнопку "+" для новых вкладок
        toolWindow.setTitleActions(listOf(
            object : AnAction("New Chat", "Create new chat tab", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    val newSession = sessionManager.createSession()
                    val newPanel = ChatPanel(project, newSession, scope)
                    val newContent = contentFactory.createContent(
                        newPanel,
                        "Chat ${toolWindow.contentManager.contentCount + 1}",
                        false
                    ).apply { isCloseable = true }
                    toolWindow.contentManager.addContent(newContent)
                    toolWindow.contentManager.setSelectedContent(newContent)
                }
            }
        ))
    }
}
```

### 10. Coroutine Architecture

```kotlin
// util/CoroutineScopes.kt
object PluginCoroutineScope {
    /**
     * Application-level scope — живёт пока плагин загружен.
     * Для долгоживущих сервисов (MCP, settings watcher).
     */
    val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("ClaudeCodeGui")
    )

    /**
     * IO scope — для сетевых вызовов, файловых операций.
     */
    val ioScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("ClaudeCodeGui-IO")
    )
}

// В каждом Project-level service:
@Service(Service.Level.PROJECT)
class ConversationService(private val project: Project) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun dispose() {
        scope.cancel()
    }
}
```

---

## Plugin.xml

```xml
<idea-plugin>
    <id>com.github.claudecodegui</id>
    <name>Claude Code GUI</name>
    <vendor>claudecodegui</vendor>
    <depends>com.intellij.modules.platform</depends>
    <depends optional="true" config-file="java-features.xml">com.intellij.java</depends>
    <depends optional="true" config-file="python-features.xml">com.intellij.modules.python</depends>
    <depends optional="true" config-file="terminal-features.xml">org.jetbrains.plugins.terminal</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Tool Window -->
        <toolWindow id="Claude Code" anchor="right" icon="/icons/claude.svg"
                    factoryClass="com.github.claudecodegui.ui.toolwindow.ClaudeToolWindowFactory"/>

        <!-- Settings -->
        <applicationConfigurable instance="com.github.claudecodegui.ui.settings.ClaudeSettingsConfigurable"
                                 displayName="Claude Code GUI" id="claude.code.gui.settings"/>

        <!-- Services -->
        <applicationService serviceImplementation="com.github.claudecodegui.service.SettingsService"/>
        <projectService serviceImplementation="com.github.claudecodegui.service.ConversationService"/>
        <projectService serviceImplementation="com.github.claudecodegui.service.ProviderService"/>
        <projectService serviceImplementation="com.github.claudecodegui.core.session.SessionManager"/>
        <projectService serviceImplementation="com.github.claudecodegui.tool.permission.PermissionService"/>
        <projectService serviceImplementation="com.github.claudecodegui.mcp.McpServerManager"/>

        <!-- Startup -->
        <postStartupActivity implementation="com.github.claudecodegui.plugin.StartupActivity"/>

        <!-- Status Bar -->
        <statusBarWidgetFactory id="ClaudeCodeStatus"
                                implementation="com.github.claudecodegui.ui.status.ClaudeStatusBarWidgetFactory"/>

        <!-- Notifications -->
        <notificationGroup id="Claude Code" displayType="BALLOON"/>
    </extensions>

    <actions>
        <action id="Claude.SendSelection" class="com.github.claudecodegui.action.SendSelectionAction"
                text="Send to Claude" description="Send selected code to Claude"
                icon="/icons/claude.svg">
            <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt K"/>
            <add-to-group group-id="EditorPopupMenu" anchor="last"/>
        </action>

        <action id="Claude.QuickFix" class="com.github.claudecodegui.action.QuickFixAction"
                text="Quick Fix with Claude" description="Fix code with Claude AI">
            <keyboard-shortcut keymap="$default" first-keystroke="ctrl shift Q"/>
            <add-to-group group-id="EditorPopupMenu" anchor="last"/>
        </action>

        <action id="Claude.SendFilePath" class="com.github.claudecodegui.action.SendFilePathAction"
                text="Send File Path to Claude">
            <add-to-group group-id="ProjectViewPopupMenu" anchor="last"/>
        </action>

        <action id="Claude.GenerateCommit" class="com.github.claudecodegui.action.GenerateCommitAction"
                text="Generate Commit Message" description="Generate commit message with AI"
                icon="/icons/claude.svg">
            <add-to-group group-id="Vcs.MessageActionGroup" anchor="first"/>
        </action>
    </actions>
</idea-plugin>
```

---

## Gradle Build (Kotlin DSL)

```kotlin
// build.gradle.kts
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "com.github.claudecodegui"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.plugins.terminal")
        instrumentationTools()
    }

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0") // Dispatchers.EDT

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // HTTP Client (Ktor)
    implementation("io.ktor:ktor-client-core:3.1.0")
    implementation("io.ktor:ktor-client-cio:3.1.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.0")
    implementation("io.ktor:ktor-client-logging:3.1.0")

    // Markdown parsing
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.24.0")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "233"
            untilBuild = "263.*"
        }
    }
}
```

---

## Ожидания по производительности

| Метрика | Текущая (Java+JCEF+Node) | Целевая (Kotlin native) |
|---------|--------------------------|------------------------|
| RAM на вкладку | ~150–300 MB (Chromium) | ~5–15 MB (Swing panels) |
| Холодный старт | 3–5 сек (JCEF + bridge extract) | <0.5 сек |
| Время до первого сообщения | ~2 сек (Node spawn) | <100 мс (прямой HTTP) |
| Количество процессов | 3 (JVM + Chromium + Node) | 1 (JVM) |
| Размер плагина | ~15 MB (ai-bridge.zip + webview) | ~3–5 MB |
| Совместимость с Remote Dev | Нет (JCEF не работает) | Да (нативный UI) |

---

## Функциональные требования (Feature Parity)

Плагин должен реализовать **все** возможности текущей версии:

### Обязательные (P0)
1. Многовкладочный чат с Claude и Codex
2. Стриминг ответов в реальном времени с Markdown-рендерингом
3. Подсветка синтаксиса в code blocks через IntelliJ Editor
4. Agentic loop: tool_use → execute → tool_result → continue
5. Встроенные инструменты: Read, Write, Edit, Bash, Glob, Grep, WebFetch, SubAgent
6. Система разрешений: ask/allow/deny с запоминанием
7. MCP-сервер клиент (stdio, SSE, HTTP транспорты)
8. Inline diff preview + apply/discard через IntelliJ DiffManager
9. Ctrl+Alt+K — отправить выделенный код в чат
10. Ctrl+Shift+Q — быстрый фикс ошибок
11. Генерация commit-сообщений
12. Сохранение/загрузка истории сессий (JSONL)
13. Настройки: API keys, модели, base URL, system prompt
14. Slash-команды (/init, /review, /commit, и т.д.)
15. Plan approval dialog (агент предлагает план → пользователь одобряет)
16. Rewind conversation (откат к предыдущему сообщению)

### Важные (P1)
17. @file-ссылки — подставить содержимое файла в контекст
18. Вложения (файлы, изображения)
19. Контекст из IDE — активный файл, выделение, ошибки компиляции
20. Token counter (отображение использования токенов)
21. Thinking/reasoning блоки (сворачиваемые)
22. Status bar widget (индикатор текущей задачи)
23. Тёмная/светлая тема — автосинхронизация с IDE
24. Session favorites (избранные сессии)
25. Export transcripts
26. Многоязычность (i18n): en, ru, zh, ja, fr, hi, es, zh-TW

### Желательные (P2)
27. Mermaid-диаграммы (PlantUML как альтернатива)
28. Terminal output monitoring
29. Java/Python PSI context collection
30. Interactive diff editing (построчное редактирование перед применением)

---

## Правила и ограничения

1. **100% Kotlin** — никакого Java-кода.
2. **Никакого JCEF/webview** — только нативный Swing/IntelliJ UI.
3. **Никакого Node.js** — прямые HTTP-вызовы из JVM.
4. **Kotlin Coroutines** для всей асинхронности — никаких `Thread`, `ExecutorService`, callback hell.
5. **`kotlinx.serialization`** для JSON — никакого Gson/Jackson.
6. **IntelliJ UI DSL 2.0** (`panel { row { ... } }`) для форм и настроек.
7. **`StateFlow`/`SharedFlow`** для реактивного UI — никаких самописных event bus.
8. **IntelliJ Editor API** для подсветки кода — не сторонний JS-highlighter.
9. **IntelliJ DiffManager** для показа изменений — не кастомный diff viewer.
10. **Structured concurrency**: каждый scope привязан к Disposable.
11. **Минимальные зависимости**: только Ktor, kotlinx.serialization, commonmark.
12. **Thread safety**: EDT для UI, IO dispatcher для сети, Default для вычислений.
13. Все API-ключи хранить через `PasswordSafe` (IntelliJ Credential Store).
14. Все длительные операции — с `ProgressIndicator`.
15. Поддержка IntelliJ 2023.3+ (build 233+) до 2026.3+ (build 263+).

---

## Порядок реализации (рекомендуемый)

### Фаза 1: Ядро (1-2 недели)
- [ ] Структура проекта, Gradle, plugin.xml
- [ ] Domain models (Message, Conversation, ContentBlock)
- [ ] SettingsService (PersistentStateComponent)
- [ ] ClaudeProvider — прямые HTTP-вызовы с SSE-стримингом
- [ ] Базовый ToolWindow с одной вкладкой чата
- [ ] Простой MessageList + ChatInput
- [ ] Отправка/получение сообщений, стриминг в UI

### Фаза 2: Инструменты и агент (1-2 недели)
- [ ] ToolRegistry + базовые инструменты (Read, Write, Edit, Bash)
- [ ] AgenticLoop (tool_use → execute → tool_result)
- [ ] PermissionService + PermissionDialog
- [ ] Inline diff preview

### Фаза 3: UI Polish (1 неделя)
- [ ] Markdown renderer (commonmark → Swing)
- [ ] CodeBlockPanel с IntelliJ Editor подсветкой
- [ ] ThinkingPanel (сворачиваемый)
- [ ] StreamingIndicator
- [ ] Multi-tab support
- [ ] Session persistence (JSONL)

### Фаза 4: Advanced Features (1-2 недели)
- [ ] MCP Client (все 3 транспорта)
- [ ] Slash-команды
- [ ] Plan approval dialog
- [ ] Rewind conversation
- [ ] @file references + attachments
- [ ] IDE context collection (active file, selection)
- [ ] Quick Fix action
- [ ] Commit message generation

### Фаза 5: Polishing (1 неделя)
- [ ] CodexProvider (OpenAI)
- [ ] i18n (все 8 языков)
- [ ] Settings UI (IntelliJ DSL)
- [ ] Token counter
- [ ] Status bar widget
- [ ] History panel + favorites
- [ ] Theme sync
- [ ] Export transcripts
- [ ] PasswordSafe для API ключей

---

## Резюме

Это промпт для создания **нативного IntelliJ-плагина на Kotlin**, который полностью заменяет текущую архитектуру Java + JCEF + React + Node.js. Ключевые выигрыши:

- **В 10-20x меньше потребление RAM** (нет Chromium)
- **Мгновенный старт** (нет распаковки bridge, нет спавна Node)
- **Один процесс** (только JVM)
- **Нативный UX** (тема IDE, шрифты, accessibility из коробки)
- **Поддержка Remote Development** (Swing работает в thin client)
- **Простота отладки** (один стек, один язык, одна IDE)
- **Меньше кода** (Kotlin DSL, coroutines, sealed classes вместо JS+Java+Node)

## Референсный проект
/Users/dsudomoin/IdeaProjects/idea-claude-code-gui

---

## Текущий статус реализации (Feature Parity Tracker)

Сравнение текущего Kotlin-проекта с референсным Java+JCEF+React проектом.

### Условные обозначения
- ✅ — Реализовано
- 🔧 — Частично реализовано
- ❌ — Не реализовано
- 🔜 — Следующий в очереди

---

### Tier 0: Ядро (Core) — уже работает

| # | Фича | Статус | Примечание |
|---|-------|--------|------------|
| 1 | Структура проекта, Gradle, plugin.xml | ✅ | Kotlin 2.1.20, IntelliJ 2025.2 |
| 2 | Domain models (Message, ContentBlock, Conversation, StreamEvent) | ✅ | `@Serializable`, sealed classes |
| 3 | SettingsService (PersistentStateComponent) | ✅ | baseUrl, model, maxTokens, systemPrompt, nodePath и т.д. |
| 4 | ClaudeProvider через Node.js bridge + SDK | ✅ | claude-bridge.mjs, stdin/stdout IPC |
| 5 | Базовый ToolWindow с вкладками | ✅ | ChatPanel + "New Chat" кнопка |
| 6 | MessageListPanel + ChatInputPanel | ✅ | Кастомный rounded input с тулбаром |
| 7 | Стриминг ответов в реальном времени | ✅ | TextDelta, ThinkingDelta, Snapshot |
| 8 | Markdown рендеринг (commonmark → Swing) | ✅ | Headings, code blocks, lists, tables, quotes |
| 9 | Подсветка кода через IntelliJ Editor | ✅ | EditorFactory + EditorHighlighter |
| 10 | ThinkingPanel (сворачиваемый) | ✅ | Expand/collapse, gold theme |
| 11 | ToolUseBlock (карточки инструментов) | ✅ | Pending/Complete/Error, diff, bash, анимация |
| 12 | Permission dialog (Allow/Deny) | ✅ | DialogWrapper с деталями инструмента |
| 13 | OAuth аутентификация | ✅ | Keychain + file fallback, token refresh |
| 14 | Node.js auto-detection | ✅ | Homebrew, nvm, fnm, Volta, system |
| 15 | Селектор модели (popup) | ✅ | Sonnet 4.6, Opus 4.6, Haiku 4.5, Opus 1M |
| 16 | Селектор permission mode (popup) | ✅ | Default, Plan, YOLO |
| 17 | Toggle streaming / thinking | ✅ | Шестерёнка → popup с iOS-style toggles |
| 18 | Token usage display | ✅ | Status bar: in/out/cache |
| 19 | Ctrl+Alt+K — Send Selection to Chat | ✅ | Код + файл + язык |
| 20 | Вложения скриншотов (picker + paste + DnD) | ✅ | Thumbnail chips, click-to-open, Cmd+V |
| 21 | File context chip (текущий файл + строки) | ✅ | FileEditorManagerListener + CaretListener |
| 22 | Изображения в сообщениях чата | ✅ | ContentBlock.Image, thumbnail 280x200 |
| 23 | Multi-tab чат | ✅ | Новые вкладки через "+" кнопку |
| 24 | Dark/Light theme sync | ✅ | JBColor.namedColor, автоматически |

---

### Tier 1: Важные недостающие фичи — Высокий приоритет

| # | Фича | Статус | Описание |
|---|-------|--------|----------|
| 25 | История сессий (save/load) | ✅ | JSONL persistence, SessionStorage, SessionManager |
| 26 | Панель истории (sidebar/view) | ✅ | HistoryPanel с CardLayout, Back/Refresh, double-click load |
| 27 | Session resume (продолжение сессии) | ✅ | loadSession() в ChatPanel, продолжение диалога |
| 28 | Генерация commit message | ✅ | GenerateCommitAction в Vcs.MessageActionGroup, через ClaudeProvider SDK |
| 29 | Quick Fix with Claude (Ctrl+Shift+Q) | ❌ | Popup для быстрого фикса выделенного кода. Ref: `QuickFixWithClaudeAction` |
| 30 | Send File Path (right-click в Project View) | ❌ | Action в контекстном меню проекта. Ref: `SendFilePathToInputAction` |
| 31 | Input history (↑/↓ навигация) | ❌ | История отправленных сообщений, навигация клавишами. Ref: `input-history-service.cjs` |
| 32 | Copy code button в code blocks | 🔧 | Есть кнопка Copy, но можно улучшить UX |
| 33 | Undo file changes | ❌ | Откат изменений файлов, сделанных AI. Ref: `UndoFileHandler`, `StatusPanel` |

---

### Tier 2: Продвинутые фичи — Средний приоритет

| # | Фича | Статус | Описание |
|---|-------|--------|----------|
| 34 | Interactive diff (Apply/Reject) | ✅ | InteractiveDiffManager + Show Diff/Reject кнопки в ToolUseBlock |
| 35 | StatusPanel (file changes, todos, subagents) | ✅ | StatusPanel с 3 вкладками + trackToolUseForStatus/trackToolResultForStatus в ChatPanel |
| 36 | Plan approval dialog | ❌ | Когда Claude в plan mode — показать план для одобрения. Ref: `PlanApprovalDialog` |
| 37 | AskUserQuestion dialog | ❌ | Claude задаёт вопрос пользователю через UI. Ref: `AskUserQuestionDialog` |
| 38 | Rewind conversation | ❌ | Откат разговора к предыдущему сообщению + файлы. Ref: `RewindHandler`, `RewindSelectDialog` |
| 39 | Status bar widget | ❌ | Индикатор текущей задачи в статусной строке IDE. Ref: `ClaudeStatusBarWidget` |
| 40 | Grouped tool blocks | ✅ | ToolGroupBlock + streaming/history grouping в MessageBubble |
| 41 | Export session (MD/JSON) | ❌ | Экспорт чата в файл. Ref: `FileExportHandler` |
| 42 | Permission "Always Allow" | ❌ | Запоминание разрешения для инструмента. Ref: `PermissionService` с правилами |
| 43 | Auto-deny dangerous paths | ❌ | Автоматический отказ для /etc, ~/.ssh и т.д. Ref: `PermissionHandler` |

---

### Tier 3: Продвинутые фичи — Ниже приоритет

| # | Фича | Статус | Описание |
|---|-------|--------|----------|
| 44 | Agent system (именованные агенты) | ❌ | Кастомные агенты с system prompt. Ref: `AgentHandler`, `AgentDialog` |
| 45 | Skills / Slash commands | ❌ | Markdown-скиллы из ~/.claude/commands/. Ref: `SkillHandler`, `SkillService` |
| 46 | @file references + autocomplete | ❌ | Ссылки на файлы в input с автокомплитом. Ref: `FileReferencePopup`, `Dropdown` |
| 47 | Prompt enhancer | ❌ | AI улучшает промпт пользователя. Ref: `PromptEnhancerHandler` |
| 48 | MCP client (stdio/SSE/HTTP) | ❌ | Model Context Protocol для внешних инструментов. Ref: `McpClient`, `mcp-status/` |
| 49 | Terminal monitoring | ❌ | Мониторинг вывода терминала IDE. Ref: `TerminalMonitorService` |
| 50 | Run/Debug output monitoring | ❌ | Мониторинг Run/Debug конфигураций. Ref: `RunConfigMonitorService` |
| 51 | Codex provider (OpenAI) | ❌ | Второй AI-провайдер. Ref: `CodexProvider`, `codex-channel.js` |
| 52 | Multiple provider configs | ❌ | CRUD провайдеров, импорт из cc-switch. Ref: `ProviderHandler` |
| 53 | Message anchor rail | ❌ | Боковая навигация по сообщениям. Ref: `MessageAnchorRail` |
| 54 | Message queue | ❌ | Очередь сообщений во время генерации. Ref: `MessageQueue.tsx` |

---

### Tier 4: Polish & Extras — Низкий приоритет

| # | Фича | Статус | Описание |
|---|-------|--------|----------|
| 55 | i18n (многоязычность) | ❌ | en, ru, zh, ja и т.д. Ref: `react-i18next` |
| 56 | MCP Settings UI | ❌ | Управление MCP серверами в настройках. Ref: `McpSettingsSection` |
| 57 | Font scale / custom chat colors | ❌ | Настройка размера шрифта и цветов. Ref: `ThemeConfigService` |
| 58 | Tab rename | ❌ | Переименование вкладок чата. Ref: `RenameTabAction` |
| 59 | BridgePreloader (startup) | ❌ | Предзагрузка bridge при старте IDE. Ref: `BridgePreloader` |
| 60 | PasswordSafe для API ключей | ❌ | Хранение ключей в IntelliJ Credential Store |
| 61 | Token percentage ring indicator | ❌ | Визуальный индикатор расхода токенов. Ref: `TokenIndicator` |
| 62 | Changelog dialog | ❌ | Показ изменений при обновлении. Ref: `ChangelogDialog` |
| 63 | Auto-retry на ошибках сети | ❌ | Повторная попытка при ECONNRESET и т.д. Ref: `AUTO_RETRY_CONFIG` |
| 64 | Tool result truncation | ❌ | Обрезка длинных результатов (20k chars). Ref: `message-service.js` |
| 65 | Streaming indicator (анимация) | ❌ | Анимация ожидания/стриминга. Ref: `WaitingIndicator`, `BlinkingLogo` |
| 66 | Welcome screen | ❌ | Экран приветствия без сообщений. Ref: `WelcomeScreen` |
| 67 | Scroll-to-bottom floating button | ❌ | Кнопка прокрутки вниз. Ref: `ScrollControl` |

---

### Порядок реализации (рекомендуемый)

**Сейчас работаем над Tier 1 — по порядку:**

1. **#25 История сессий** — save/load чатов в JSONL
2. **#26 Панель истории** — sidebar со списком сессий
3. **#27 Session resume** — продолжение сессии
4. **#28 Генерация commit message** — VCS action
5. **#29 Quick Fix** — Ctrl+Shift+Q
6. **#30 Send File Path** — right-click action
7. **#31 Input history** — ↑/↓ навигация
8. **#33 Undo file changes** — откат изменений

После Tier 1 переходим к Tier 2, затем Tier 3, Tier 4.