# Plan: StatusPanel Redesign — Custom-Painted Modern UI

## Context

Текущий StatusPanel (todos / files / agents) выглядит generic и flat — JLabel-based табы, FlowLayout, Unicode-иконки (○, ◉, ✓, ⟳), нет hover-эффектов, нет strikethrough, нет spinning-анимации. Нужно привести к виду референсного проекта с custom-painted панелями (как ToolUseBlock/ToolGroupBlock).

**Public API не меняется** — ChatPanel не трогаем (кроме dispose).

## Файлы

| Файл | Действие |
|---|---|
| `ui/theme/ThemeColors.kt` | Добавить `surfaceTertiary` цвет |
| `ui/status/StatusPanel.kt` | **Полный rewrite** (~650 строк) |
| `ui/chat/ChatPanel.kt` | Добавить `statusPanel.dispose()` в dispose |

## Step 1: ThemeColors — добавить `surfaceTertiary`

В `ThemeColors.kt`:
- DEFAULTS: `"surfaceTertiary" to (0xF0F0F0 to 0x2A2D30)`
- var: `var surfaceTertiary = jbColor(0xF0F0F0, 0x2A2D30)`
- `setColorByKey`: + `"surfaceTertiary" -> surfaceTertiary = color`
- `getColorByKey`: + `"surfaceTertiary" -> surfaceTertiary`

## Step 2: StatusPanel.kt — полный rewrite

### Структура

```
StatusPanel (BorderLayout)
├── TabBarPanel (NORTH) — custom-painted, 32px, один компонент
│   Рисует 3 таба в rounded контейнере + collapse toggle
│   Mouse tracking: hoveredTab, click → switch tab
└── JScrollPane (CENTER, max 180px)
    └── CardLayout
        ├── TodosListPanel (BoxLayout.Y_AXIS)
        │   └── TodoRowPanel* (custom-painted, 32px, hover)
        ├── FilesListPanel (BorderLayout)
        │   ├── BatchActionsBar (NORTH, 32px) — Discard All / Keep All
        │   └── filesList (BoxLayout.Y_AXIS)
        │       └── FileRowPanel* (custom-painted, 32px, hover, action buttons)
        └── AgentsListPanel (BoxLayout.Y_AXIS)
            └── AgentRowPanel* (custom-painted, 32px, hover)
```

### TabBarPanel — custom-painted tab bar

Один JPanel, всё в `paintComponent`:
- Outer rounded rect (arc=8, bg=surfacePrimary, border=borderNormal)
- 3 таба равной ширины, разделены border-right (1px borderNormal)
- **Active tab**: bg=surfaceTertiary, text=textPrimary, font=BOLD
- **Hovered tab**: bg=surfaceHover, text=textPrimary
- **Inactive tab**: text=textSecondary
- Каждый таб: `[icon 14px] [4px] [label 11px] [4px] [stats 10px] [spinner?]`
- Иконки: AllIcons.Actions.Checked (Todos), AllIcons.Actions.Edit (Files), AllIcons.Nodes.ConfigFolder (Agents)
- Stats: "3/5" для todos/agents; "+12 -4" (diffAddFg/diffDelFg) для files
- Spinner рядом со stats если есть in_progress/running
- Collapse toggle: справа, AllIcons.General.ArrowDown / ArrowRight

Hit-testing: `tabBounds[0..2]`, `collapseBounds` — Rectangle[], заполняются в paintComponent.

### TodoRowPanel — 32px custom-painted строка

- Hover: `fillRoundRect(arc=4)` с surfaceHover
- Status icon (14px):
  - PENDING: `g2.drawOval()` с statusPending
  - IN_PROGRESS: `g2.drawArc(spinAngle, 270)` с statusProgress (вращающийся)
  - COMPLETED: AllIcons.Actions.Checked
- Content text: 12px, textPrimary
- **COMPLETED**: font с `TextAttribute.STRIKETHROUGH`, цвет textSecondary

### FileRowPanel — 32px custom-painted строка

- Hover: `fillRoundRect(arc=4)` с surfaceHover
- [A/M badge] [file icon] [filename] ... [+N -N] [diff btn] [undo btn]
- Badge "A": зелёный (statusSuccess), "M": синий (accent)
- File icon: `FileTypeManager.getInstance().getFileTypeByFileName()`
- Filename: textPrimary, underline on hover (LINK_COLOR)
- Stats: diffAddFg / diffDelFg
- Action buttons (20px): AllIcons.Actions.Diff, AllIcons.Actions.Rollback
  - hover: iconHoverBg fillRoundRect
  - hit-testing через Rectangle bounds (паттерн из ToolGroupBlock.ItemRowPanel)

### BatchActionsBar — 32px панель над файлами

- bg=surfaceTertiary, borderBottom=1px borderNormal
- Справа: "Discard All" (красная рамка), "Keep All" (обычная рамка)
- Discard All: итерирует fileChanges → InteractiveDiffManager.revertEdit/revertWrite
- Keep All: скрывает бар

### AgentRowPanel — 32px custom-painted строка

- Hover: `fillRoundRect(arc=4)` с surfaceHover
- Status icon: spinner для RUNNING, AllIcons.Actions.Checked для COMPLETED, AllIcons.General.Error для ERROR
- Type badge: `fillRoundRect(arc=4)` с surfaceTertiary, текст 10px BOLD textSecondary
- Description: 12px textPrimary, ellipsis truncated

### Spinning Animation

Один общий Timer(50ms) для всех спиннеров:
```kotlin
private var spinAngle = 0
private val spinTimer = Timer(50) { spinAngle = (spinAngle + 15) % 360; repaintSpinners() }
```

Start/stop: `updateSpinState()` — запускает если есть IN_PROGRESS todo или RUNNING agent, останавливает если нет.

`paintSpinner(g2, x, y, size, color)`: `g2.drawArc(x, y, size, size, spinAngle, 270)` со stroke=2px CAP_ROUND.

### dispose()

`spinTimer.stop()` — вызывается из ChatPanel.dispose().

## Step 3: ChatPanel — dispose

Добавить `statusPanel.dispose()` в существующий `ChatPanel.dispose()`.

## Verification

1. `./gradlew compileKotlin` — сборка без ошибок
2. Запустить плагин → открыть чат → попросить Claude что-нибудь сделать
3. Проверить: todos обновляются, файлы отображаются, agents показываются
4. Проверить: hover-эффекты на строках, strikethrough на completed todos
5. Проверить: spinner крутится для in-progress, останавливается при complete
6. Проверить: табы переключаются, collapse работает
7. Проверить: diff/undo кнопки в файлах работают
8. Проверить: dark/light theme — все цвета корректны
