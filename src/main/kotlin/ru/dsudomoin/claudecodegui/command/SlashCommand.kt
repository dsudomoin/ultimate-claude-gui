package ru.dsudomoin.claudecodegui.command

enum class CommandCategory { LOCAL, SDK }

data class SlashCommand(
    val name: String,
    val description: String,
    val category: CommandCategory
)

object SlashCommandRegistry {

    /** Commands handled locally by the plugin (never sent to SDK). */
    private val LOCAL_COMMANDS = listOf(
        SlashCommand("/clear", "Clear conversation history", CommandCategory.LOCAL),
        SlashCommand("/new", "Start a new conversation", CommandCategory.LOCAL),
        SlashCommand("/reset", "Reset conversation", CommandCategory.LOCAL),
        SlashCommand("/help", "Show available commands", CommandCategory.LOCAL),
    )

    /** New-session aliases — all create a new chat. */
    val NEW_SESSION_ALIASES = setOf("/clear", "/new", "/reset")

    /** Commands hidden from the autocomplete popup. */
    private val HIDDEN_COMMANDS = setOf(
        "/context", "/cost", "/pr-comments", "/release-notes",
        "/security-review", "/todo", "/login", "/logout",
        "/doctor", "/terminal-setup", "/status"
    )

    /** Cached SDK commands (loaded asynchronously from bridge). */
    @Volatile
    private var sdkCommands: List<SlashCommand> = emptyList()

    /** Whether SDK commands have been loaded at least once. */
    @Volatile
    var isLoaded: Boolean = false
        private set

    /** Update SDK commands cache (called after bridge fetch). */
    fun updateSdkCommands(commands: List<Pair<String, String>>) {
        sdkCommands = commands
            .map { (name, desc) ->
                // Normalize: SDK may return "compact" or "/compact"
                val normalized = if (name.startsWith("/")) name else "/$name"
                normalized to desc
            }
            .filter { (name, _) -> name !in HIDDEN_COMMANDS }
            .filter { (name, _) -> LOCAL_COMMANDS.none { it.name == name } }
            .map { (name, desc) -> SlashCommand(name, desc, CommandCategory.SDK) }
        isLoaded = true
    }

    /** Get all visible commands (local + SDK). */
    fun all(): List<SlashCommand> = LOCAL_COMMANDS + sdkCommands

    /** Filter commands by prefix (for autocomplete). */
    fun filter(prefix: String): List<SlashCommand> =
        all().filter { it.name.startsWith(prefix, ignoreCase = true) }

    /** Find exact command by name. */
    fun find(name: String): SlashCommand? =
        all().find { it.name.equals(name, ignoreCase = true) }
}
