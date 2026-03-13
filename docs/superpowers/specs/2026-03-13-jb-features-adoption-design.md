# JB Features Adoption — Design Spec

Adoption of 5 features from the JetBrains AI Agents Frontend plugin.

## 1. Font Scaling

### Goal
User-configurable font scale for all chat UI text.

### Design
- New field `fontScale: Float` (range 0.7–2.0, default 1.0) in `SettingsService`
- `CompositionLocal`: `LocalFontScale` provided at `ComposePanelHost` level
- Utility function `scaledSp(base: Int): TextUnit` — returns `(base * fontScale).sp`
- All hardcoded `*.sp` literals in Compose files replaced with `scaledSp(N)` calls
- Settings UI: slider + number input in existing `AppearanceConfigurable` (Swing-based settings panel registered in plugin.xml)

### Files to modify
- `SettingsService.kt` — add `fontScale` persistent field
- New: `ui/compose/theme/FontScale.kt` — `LocalFontScale`, `scaledSp()`
- `ComposePanelHost.kt` — provide `LocalFontScale`
- All Compose files with `*.sp` literals (validate exact count during implementation)
- `ui/settings/AppearanceConfigurable.kt` — add font scale slider to existing appearance settings
- i18n: `settings.fontScale`, `settings.fontScale.desc`

---

## 2. Terminal — "Open in Terminal"

### Goal
One-click paste of bash commands into IntelliJ's built-in terminal.

### Design
- Hover-action button "Terminal" on bash tool blocks (alongside Diff/Revert)
- Visible for tools: `bash`, `run_terminal_cmd`, `execute_command`, `executecommand`, `shell_command`
- Command stored in `ToolUseData` as new field `bashCommand: String?` — extracted at construction time via `ToolSummaryExtractor.extractBashCommand(input: JsonObject)` (in `ChatController`/`ComposeMessageBubble` where `JsonObject` is available)
- Callback chain: `onOpenInTerminal: ((String) -> Unit)?` from `ComposeToolUseBlock` → bubble → panel → `ChatController`
- `ChatController.openCommandInTerminal(command: String)`:
  - Exact terminal API to be determined during implementation after adding terminal plugin dependency (APIs changed significantly in 2025.3.x with the new block terminal)
  - Target: create or find terminal tab, type command text without executing
  - Wrapped in try-catch — gracefully does nothing if terminal plugin unavailable

### Files to modify
- `ComposeToolUseBlock.kt` — add "Terminal" hover button for bash tools
- `ToolUseData` — add `bashCommand: String?` field
- `ComposeMessageBubble.kt` — pass `onOpenInTerminal` callback through
- `ComposeChatPanel.kt` — pass callback from ChatCallbacks
- `ChatController.kt` — implement `openCommandInTerminal()`
- `ComposeChatContainer.kt` — add `onOpenInTerminal` to `ChatCallbacks` data class
- i18n: `tool.action.terminal`

### Dependencies
- `build.gradle.kts`: add `bundledPlugin("org.jetbrains.plugins.terminal")` to intellijPlatform dependencies
- `plugin.xml`: add `<depends optional="true" config-file="terminal-support.xml">org.jetbrains.plugins.terminal</depends>`
- New: `META-INF/terminal-support.xml` — empty extension point config for optional dependency

### Error handling
- If terminal plugin not installed: button hidden (check via `PluginManagerCore.isPluginInstalled(PluginId.getId("org.jetbrains.plugins.terminal"))`)
- If terminal fails to open: log warning, show IDE notification

---

## 3. Status Panel — Improved Todos

### Goal
Enhanced todo visualization with progress tracking, interactivity, and clipboard support.

### Design

#### Progress bar
- Horizontal bar at top of todos tab: filled portion = completed/total
- Text label: "3/7 completed"

#### Interactivity
- Click on todo item → toggle between COMPLETED and PENDING
- IN_PROGRESS items: click has no effect (status driven by SDK, not user)
- Callback: `onTodoToggle: ((Int) -> Unit)?` where `Int` = list index → `ChatController`
- Toggle is purely visual/local — does not send anything back to SDK (plan is read-only from SDK perspective)
- New status: `CANCELED` added to `TodoStatus` enum (displayed as strikethrough + gray icon)

#### Copy to clipboard
- Button "Copy plan" in tab header
- Formats all todos as markdown checklist: `- [x] done`, `- [ ] pending`, `- [-] canceled`

#### Status icons
- COMPLETED: green checkmark
- IN_PROGRESS: animated pulsing dot (reuse existing pattern)
- PENDING: gray circle outline
- CANCELED: gray X mark

### Files to modify
- `StatusModels.kt` — add `CANCELED` to `TodoStatus`
- `ComposeTodoTab.kt` — progress bar, click handler, copy button, improved icons
- `ChatController.kt` — handle `onTodoToggle`
- i18n: `todo.progress`, `todo.copy`, `todo.canceled`

---

## 4. Message-level Rollback

### Goal
One-click revert of all file changes from a single assistant message.

### Design

#### UI
- Hover overlay in top-right corner of assistant message bubble
- Button: "↩ Revert all"
- Visible only when message has >= 1 completed Edit/Write/Create tool call
- Click → `RevertConfirmPopup` with "Revert N file changes from this message?"

#### Logic
- Callback: `onMessageRevert: ((Message) -> Unit)?` from `ComposeMessageList` → `ComposeChatPanel` → `ChatController`
- `ChatController.revertMessageChanges(message: Message)`:
  - Collects all `ContentBlock.ToolUse` from `message.content`
  - Pairs each with its `ContentBlock.ToolResult` by `toolUseId`
  - For each tool, determines type by `toolName`:
    - Edit tools (`edit`, `edit_file`, `replace_string`): calls `InteractiveDiffManager.revertEdit(filePath, oldString, newString)`
    - Write tools (`write`, `write_to_file`, `create_file`): calls `InteractiveDiffManager.revertWrite(project, filePath)`
  - Extracts `filePath`, `oldString`, `newString` from `ContentBlock.ToolUse.input: JsonObject`
  - Shows notification with success/failure count

#### Post-revert visual
- Reverted tool blocks get dimmed style (alpha 0.5) with "Reverted" label
- Track reverted state via `var revertedToolIds: Set<String>` in `ChatViewModel` with setter calling `notifyField(Field.REVERTED_TOOL_IDS)` (new `Field` enum entry). Compose bridge in `ComposeChatPanel`'s `DisposableEffect` must observe this field and copy into local `mutableStateOf`.

### Files to modify
- `ComposeMessageBubble.kt` — add hover overlay with revert button on `AssistantBubble`
- `ComposeMessageList.kt` — pass `onMessageRevert` callback
- `ComposeChatPanel.kt` — wire callback
- `ChatController.kt` — implement `revertMessageChanges()`
- `ChatViewModel.kt` — add `revertedToolIds: Set<String>` field with listener notification
- i18n: `message.revert`, `message.revert.confirm`, `message.reverted`

---

## 5. VCS Actions on Files

### Goal
Full VCS integration for file changes in status panel Edits tab.

### Design

#### Git status badge
- Badge next to each filename: `A` (green), `M` (blue), `D` (red), `?` (gray)
- Determined via `ChangeListManager.getInstance(project).getChange(vFile)?.fileStatus` → `FileStatus`

#### Hover actions (Row, right-aligned)
| Action | Icon | Implementation |
|--------|------|----------------|
| Open | `📂` | `FileEditorManager.openFile(vFile, true)` |
| Diff | `⇔` | `DiffManager.showDiff()` (existing pattern) |
| Commit | `✓` | Open IntelliJ commit dialog for single file |
| Revert | `↩` | `InteractiveDiffManager.revertEdit()` / `revertWrite()` depending on tool type |
| Delete | `🗑` | `vFile.delete()` with confirm popup |
| Remove from VCS | `⊘` | `git rm --cached` via `git4idea.commands.GitLineHandler` |

#### Commit strategy
- Use IntelliJ's built-in VCS commit infrastructure — invoke commit via `AbstractCommonCheckinAction` or `CommitChangeListDialog.commitChanges()` with a pre-selected single-file changeset
- Pre-select the specific file in the changelist

#### New service
- `VcsActionService` — project-level service, encapsulates all VCS operations
- Methods: `getFileStatus()`, `commitFile()`, `removeFromVcs()`, `revertFile()`, `deleteFile()`
- Graceful degradation: when Git plugin not available, `getFileStatus()` returns `UNKNOWN`, git-specific actions (Commit, Remove from VCS) are hidden

#### Data model
- `FileGitStatus` enum: `ADDED`, `MODIFIED`, `DELETED`, `UNTRACKED`, `UNKNOWN`
- `FileChangeSummary` gets new field: `gitStatus: FileGitStatus`
- Note: `FileGitStatus` reflects VCS state (from `ChangeListManager`), separate from existing `FileChangeType` which reflects Claude's operation type. Both are needed.

### Files to modify
- New: `service/VcsActionService.kt` — VCS operations service
- `StatusModels.kt` — add `FileGitStatus` enum, extend `FileChangeSummary`
- `ComposeFilesTab.kt` — git badges, hover action buttons
- `ChatController.kt` — populate `gitStatus` when building file change list
- `plugin.xml` — register `VcsActionService`, add optional Git dependency
- i18n: `file.action.open`, `file.action.diff`, `file.action.commit`, `file.action.revert`, `file.action.delete`, `file.action.removeVcs`, `file.action.delete.confirm`

### Dependencies
- `build.gradle.kts`: add `bundledPlugin("Git4Idea")` to intellijPlatform dependencies
- `plugin.xml`: add `<depends optional="true" config-file="git-support.xml">Git4Idea</depends>`
- New: `META-INF/git-support.xml` — register git-dependent extensions/actions
