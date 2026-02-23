# Claude Code GUI — IntelliJ Plugin

Kotlin/Swing-based IntelliJ IDEA plugin for interacting with Claude Code SDK.

## Features

- Chat UI with message bubbles and markdown rendering
- Tool use blocks with interactive diffs
- Claude SDK bridge via Node.js process
- Session management and history
- Permission dialogs for tool use approval
- Prompt enhancer with Claude Haiku
- Floating scroll navigation with smooth animation
- Localization (EN/RU)
- Generate commit action and send selection action

## Plugin structure

```
.
├── gradle/                 Gradle Wrapper
├── src/main
│   ├── kotlin/             Kotlin sources
│   │   ├── action/         IDE actions
│   │   ├── bridge/         Node.js bridge communication
│   │   ├── core/           Models, session management
│   │   ├── provider/       AI provider abstraction
│   │   ├── service/        Settings, OAuth, prompt enhancer
│   │   ├── settings/       Plugin settings UI
│   │   └── ui/             Swing UI components
│   └── resources/
│       ├── META-INF/       plugin.xml, icon
│       ├── bridge/         Node.js bridge script
│       └── messages/       i18n bundles
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

## Getting Started

1. Clone the repository
2. Open in IntelliJ IDEA
3. Run `./gradlew runIde` to launch a sandbox IDE with the plugin
