# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

IntelliJ IDEA plugin for Claude Code SDK interaction. Pure Kotlin/Swing — no JCEF, no React, no webviews. Communicates with Claude via a Node.js bridge process that wraps the `@anthropic-ai/claude-code` SDK.

## Build Commands

```bash
./gradlew runIde        # Launch sandbox IDE with plugin loaded
./gradlew build         # Full build (compile + package)
./gradlew buildPlugin   # Build plugin distribution ZIP
./gradlew test          # Run tests
```

## Tech Stack

- **Kotlin 2.1.20**, JVM 21, kotlinx-serialization, kotlinx-coroutines
- **IntelliJ Platform SDK** 2025.2.4 (since-build 252.25557), plugin via `org.jetbrains.intellij.platform` Gradle plugin v2.10.2
- **Ktor** 3.1.1 for HTTP, **CommonMark** 0.24.0 for Markdown rendering
- **Node.js bridge** (`src/main/resources/bridge/claude-bridge.mjs`) — spawned subprocess using `@anthropic-ai/claude-code` SDK

## Architecture

### Communication Protocol (Plugin ↔ Bridge ↔ SDK)

`ClaudeProvider` spawns a Node.js process running `claude-bridge.mjs`. Communication is line-based via stdin/stdout:

- **stdin line 1:** JSON command `{message, sessionId, cwd, model, permissionMode, ...}`
- **stdin subsequent:** Permission responses `{allow: true/false, message?}`
- **stdout:** Tagged lines `[TAG]payload` — parsed by `SDKMessageParser` via regex `^\[([A-Z_]+)](.*)$`
- Tags: `STREAM_START`, `CONTENT_DELTA`, `THINKING_DELTA`, `TOOL_USE`, `PERMISSION_REQUEST`, `RESULT`, `ERROR`, etc.

The provider returns `Flow<StreamEvent>` consumed by UI coroutines.

### Key Layers

- **`core/model/`** — Domain types: `Message`, `ContentBlock` (sealed: Text, Code, Thinking, ToolUse, ToolResult, Image), `StreamEvent` (sealed class for all streaming events)
- **`provider/claude/ClaudeProvider`** — Manages Node.js bridge subprocess lifecycle, sends commands, collects stdout into `Flow<StreamEvent>`
- **`bridge/BridgeManager`** — Extracts bridge script to `~/.claude-code-gui/bridge/`, runs `npm install`
- **`bridge/SDKMessageParser`** — Parses tagged stdout lines into `StreamEvent` instances
- **`core/session/SessionStorage`** — Reads JSONL files from `~/.claude/projects/` for session history
- **`ui/chat/ChatPanel`** — Main UI orchestrator: message list, input panel, status panel, streaming display
- **`ui/approval/`** — Permission dialogs for tool use (Bash, Edit, Write, etc.)

### Services (registered in plugin.xml)

- `SettingsService` — application-level, `PersistentStateComponent` for all settings
- `SessionManager` — project-level, reads/lists sessions from disk
- `OAuthCredentialService` — application-level, reads tokens from Keychain/file

### Bridge Lifecycle

1. `BridgeManager` extracts `claude-bridge.mjs` + `package.json` from plugin resources to `~/.claude-code-gui/bridge/`
2. Runs `npm install --production` if `node_modules/` is missing
3. `NodeDetector` finds Node.js and Claude CLI binaries (configured path → common locations → PATH → nvm/fnm/volta)

## Conventions

- **100% Kotlin** — no Java source files
- **Sealed classes** for algebraic types (`ContentBlock`, `StreamEvent`)
- **Coroutines + Flow** for all async/streaming (no callbacks, no CompletableFuture)
- **i18n** via `MyMessageBundle` with `.properties` files (EN + RU). Key access: `MyMessageBundle.message("key")`
- **Plugin descriptor**: `src/main/resources/META-INF/plugin.xml` — register all services, actions, and extensions there
- Theme system in `ui/theme/` with `ThemeManager`, presets, and custom color overrides
