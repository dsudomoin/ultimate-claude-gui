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
- Settings UI: slider + number input in `ComposeSettingsPanel`

### Files to modify
- `SettingsService.kt` — add `fontScale` persistent field
- New: `ui/compose/theme/FontScale.kt` — `LocalFontScale`, `scaledSp()`
- `ComposePanelHost.kt` — provide `LocalFontScale`
- All Compose files with `*.sp` literals (~15 files)
- `ComposeSettingsPanel.kt` — add slider UI
- i18n: `settings.fontScale`, `settings.fontScale.desc`

---

## 2. Terminal — "Open in Terminal"

### Goal
One-click paste of bash commands into IntelliJ's built-in terminal.

### Design
- Hover-action button "Terminal" on bash tool blocks (alongside Diff/Revert)
- Visible for tools: `bash`, `run_terminal_cmd`, `execute_command`, `executecommand`, `shell_command`
- Callback chain: `onOpenInTerminal: ((String) -> Unit)?` from `ComposeToolUseBlock` → bubble → panel → `ChatController`
- Command extracted via `ToolSummaryExtractor.extractBashCommand(input)`
- `ChatController.openCommandInTerminal(command: String)`:
  - Uses `TerminalView.getInstance(project)` to get terminal
  - Creates new tab with `createLocalShellWidget(projectPath)`
  - Types command text without executing (user presses Enter)

### Files to modify
- `ComposeToolUseBlock.kt` — add "Terminal" hover button for bash tools
- `ComposeMessageBubble.kt` — pass `onOpenInTerminal` callback through
- `ComposeChatPanel.kt` — pass callback from ChatCallbacks
- `ChatController.kt` — implement `openCommandInTerminal()`
- `ChatCallbacks.kt` — add `onOpenInTerminal` field
- i18n: `tool.action.terminal`

### Dependencies
- IntelliJ Terminal plugin (`org.jetbrains.plugins.terminal`) — optional dependency in `plugin.xml`

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
- Callback: `onTodoToggle: ((Int) -> Unit)?` → `ChatController`
- New status: `CANCELED` added to `TodoStatus` enum (displayed as strikethrough + gray icon)

#### Copy to clipboard
- Button "Copy plan" in tab header
- Formats all todos as markdown checklist: `- [x] done`, `- [ ] pending`, `- [-] canceled`

#### Status icons
- DONE: green checkmark
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
  - Filters Edit/Write tools with result blocks
  - Calls `InteractiveDiffManager.revertChange()` for each
  - Shows notification with success/failure count

#### Post-revert visual
- Reverted tool blocks get dimmed style (alpha 0.5) with "Reverted" label
- Track reverted state via `Set<String>` of tool IDs in ViewModel

### Files to modify
- `ComposeMessageBubble.kt` — add hover overlay with revert button on `AssistantBubble`
- `ComposeMessageList.kt` — pass `onMessageRevert` callback
- `ComposeChatPanel.kt` — wire callback
- `ChatController.kt` — implement `revertMessageChanges()`
- `ChatViewModel.kt` — add `revertedToolIds: Set<String>` field
- i18n: `message.revert`, `message.revert.confirm`, `message.reverted`

---

## 5. VCS Actions on Files

### Goal
Full VCS integration for file changes in status panel Edits tab.

### Design

#### Git status badge
- Badge next to each filename: `A` (green), `M` (blue), `D` (red), `?` (gray)
- Determined via `ChangeListManager.getInstance(project).getChange(vFile)` → `FileStatus`

#### Hover actions (Row, right-aligned)
| Action | Icon | Implementation |
|--------|------|----------------|
| Open | `📂` | `FileEditorManager.openFile(vFile, true)` |
| Diff | `⇔` | `DiffManager.showDiff()` (existing pattern) |
| Commit | `✓` | Open IntelliJ commit dialog for single file |
| Revert | `↩` | `InteractiveDiffManager.revertChange()` or `RollbackChangesAction` |
| Delete | `🗑` | `vFile.delete()` with confirm popup |
| Remove from VCS | `⊘` | `git rm --cached` via `GitLineHandler` |

#### Commit strategy
- Use IntelliJ's built-in `CheckinProjectPanel` / commit dialog for reliability
- Pre-select the specific file in the changelist

#### New service
- `VcsActionService` — encapsulates all VCS operations, injected as project-level service
- Methods: `getFileStatus()`, `commitFile()`, `removeFromVcs()`, `revertFile()`, `deleteFile()`

#### Data model
- `FileGitStatus` enum: `ADDED`, `MODIFIED`, `DELETED`, `UNTRACKED`, `UNKNOWN`
- `FileChangeSummary` gets new field: `gitStatus: FileGitStatus`

### Files to modify
- New: `service/VcsActionService.kt` — VCS operations service
- `StatusModels.kt` — add `FileGitStatus` enum, extend `FileChangeSummary`
- `ComposeEditsTab.kt` — git badges, hover action buttons
- `ChatController.kt` — populate `gitStatus` when building file change list
- `plugin.xml` — register `VcsActionService`
- i18n: `file.action.open`, `file.action.diff`, `file.action.commit`, `file.action.revert`, `file.action.delete`, `file.action.removeVcs`, `file.action.delete.confirm`

### Dependencies
- `com.intellij.git4idea` — optional dependency for git-specific operations
