# Централизованная система цветов + UI настроек

## Контекст

Сейчас ~85 цветовых констант разбросаны по 11 UI-файлам (companion objects). Нет возможности настройки цветов пользователем. Цель — вынести все цвета в единый реестр, добавить UI с color picker'ами и пресеты тем.

## Новые файлы (6 шт.)

### 1. `ui/theme/ThemeColors.kt` — Центральный реестр цветов

Kotlin `object` с `var` свойствами (`JBColor`). ~20 базовых настраиваемых цветов + ~10 вычисляемых (`val` с getter).

**Группы:**
| Группа | Свойства |
|--------|----------|
| Accent | `accent`, `accentSecondary` |
| User Bubble | `userBubbleBg`, `userBubbleFg` |
| Text | `textPrimary`, `textSecondary` |
| Surfaces | `surfacePrimary`, `surfaceSecondary`, `surfaceHover` |
| Borders | `borderNormal` |
| Status | `statusSuccess`, `statusWarning`, `statusError` |
| Diff | `diffAddFg`, `diffDelFg`, `diffAddBg`, `diffDelBg` |
| Code | `codeBg`, `codeFg` |
| Actions | `approveBg`, `denyBg` |

**Вычисляемые (не настраиваемые):** `userBubbleBorder`, `borderFocus` (=accent), `separatorColor`, `hoverOverlay`, `iconHoverBg`.

Методы: `resetToDefaults()`, `applyOverrides(Map<String, Pair<Int, Int>>)`, `setColorByKey(key, lightRgb, darkRgb)`.

### 2. `ui/theme/ThemePreset.kt` — Пресеты тем

```kotlin
data class ThemePreset(val id: String, val displayName: String, val colors: Map<String, Pair<Int, Int>>)
```

3 пресета: **Default** (пустой — заводские цвета), **Dark+** (VS Code-стиль), **Warm** (тёплые оранжевые тона).

### 3. `ui/theme/ThemeColorSerializer.kt` — Сериализация

Формат: `"RRGGBB:RRGGBB"` (light:dark hex). Методы `serialize(Color, Color): String` и `deserialize(String): Pair<Int, Int>?`.

### 4. `ui/theme/ThemeChangeListener.kt` — MessageBus topic

```kotlin
fun interface ThemeChangeListener {
    companion object { val TOPIC = Topic.create(...) }
    fun themeColorsChanged()
}
```

### 5. `ui/theme/ThemeManager.kt` — Оркестратор

- `initialize()` — загрузка из SettingsService при старте
- `applyTheme(presetId, customOverrides)` — reset + preset + overrides + broadcast
- Вызов `initialize()` из `ClaudeToolWindowFactory.createToolWindowContent()`

### 6. `settings/AppearanceConfigurable.kt` — UI настроек

Регистрируется как дочерний configurable. Использует `com.intellij.ui.ColorPanel` для каждого цвета. Layout:

```
[Preset: ▾ Default / Dark+ / Warm]  [Сбросить всё]

── Сообщения пользователя ──
  Фон пузыря       [■ light] [■ dark]
  Текст пузыря     [■ light] [■ dark]
── Акцент и ссылки ──
  Основной          [■ light] [■ dark]
  Вторичный         [■ light] [■ dark]
── Текст ──  (Primary / Secondary)
── Поверхности и границы ── (Primary / Secondary / Hover / Border)
── Статус ── (Success / Warning / Error)
── Код ──  (Background / Foreground)
── Diff ── (Added / Deleted)
── Действия ── (Approve / Deny)
```

20 пар color picker'ов (light + dark для каждого).

## Изменяемые файлы

### `SettingsService.kt` — +2 поля в State

```kotlin
var themePresetId: String = "default"
var customColorOverrides: Map<String, String> = emptyMap()  // "accent" → "0078D4:589DF6"
```

### `plugin.xml` — регистрация AppearanceConfigurable

```xml
<applicationConfigurable parentId="ru.dsudomoin.claudecodegui.settings"
    instance="ru.dsudomoin.claudecodegui.settings.AppearanceConfigurable"
    id="ru.dsudomoin.claudecodegui.settings.appearance"
    displayName="Appearance"/>
```

### Миграция 11 UI-компонентов

Для каждого файла: удалить цветовые `val` из companion object → заменить на `ThemeColors.*` → добавить подписку на `ThemeChangeListener.TOPIC` → `repaint()`.

| Файл | Было → Стало (примеры) |
|------|------------------------|
| `MessageBubble.kt` | `USER_BG` → `ThemeColors.userBubbleBg` |
| `ChatInputPanel.kt` | `BORDER_FOCUS` → `ThemeColors.accent`, `INPUT_BG` → `ThemeColors.surfacePrimary` |
| `ToolUseBlock.kt` | `STATUS_SUCCESS` → `ThemeColors.statusSuccess`, `BG` → `ThemeColors.surfacePrimary` |
| `MessageListPanel.kt` | `WELCOME_ACCENT` → `ThemeColors.accentSecondary` |
| `MarkdownRenderer.kt` | inline `JBColor(...)` → `ThemeColors.codeBg` и т.д. + `invalidateCache()` |
| `ChatPanel.kt` | `NAV_ARROW` → `ThemeColors.textSecondary` |
| `ThinkingPanel.kt` | `HEADER_COLOR` → `ThemeColors.textSecondary` |
| `StatusPanel.kt` | `TAB_ACTIVE` → `ThemeColors.accent` |
| `HistoryPanel.kt` | `ACCENT` → `ThemeColors.accentSecondary` |
| `PlanActionPanel.kt` | `APPROVE_BG` → `ThemeColors.approveBg` |
| `QuestionSelectionPanel.kt` | `ACCENT` → `ThemeColors.accent` |

**Особый случай — MarkdownRenderer:** CSS генерируется со вставкой hex-значений. Нужен `@Volatile cachedKit` + метод `invalidateCache()`, вызываемый при смене темы.

### Локализация

`MyMessageBundle.properties` / `_ru.properties` — ключи для секций Appearance.

## Порядок реализации

1. **ThemeColors.kt** + **ThemePreset.kt** + **ThemeColorSerializer.kt** + **ThemeChangeListener.kt** + **ThemeManager.kt**
2. **SettingsService.kt** — добавить поля
3. **AppearanceConfigurable.kt** + регистрация в **plugin.xml** + локализация
4. Миграция всех 11 компонентов (по одному)
5. Инициализация в **ClaudeToolWindowFactory**

## Проверка

1. Открыть Settings → Tools → Claude Code GUI → Appearance
2. Сменить пресет (Default → Dark+ → Warm) — цвета в чате обновляются сразу
3. Изменить индивидуальный цвет через color picker — применяется live
4. Перезагрузить IDE — кастомные цвета сохранены
5. Переключить IDE тему (Light ↔ Dark) — оба варианта цветов работают корректно
6. Нажать "Reset All" — возврат к заводским цветам
