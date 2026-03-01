# Changelog

## [Unreleased]

## [1.0.8]
### Added
- Full Claude SDK integration: elicitation dialogs, extended settings, bridge control commands
- Extended settings panel: effort level, max budget, beta context, allowed/disallowed tools, MCP servers, fallback model, agents, hooks, sandbox, plugins, file checkpointing
- Bridge abort/control commands: setModel, setPermissionMode, rewindFiles
- Improved session management with custom titles and SDK session listing
- Node.js 18+ compatibility (relaxed from LTS-only detection)

## [1.0.2]
### Added
- @file mentions in chat input with project file index
### Fixed
- Bug fixes and stability improvements

## [1.0.1]
### Fixed
- Node.js bridge setup reliability improvements

## [1.0.0]
### Added
- Initial release with native Compose chat UI
- Claude Code SDK integration via Node.js bridge
- Streaming responses with thinking blocks and tool use display
- Permission approval dialogs for tool use (Bash, Edit, Write, etc.)
- Session management: create, resume, and list sessions
- Slash commands support
- Internationalization (English + Russian)
- Theme system with presets and custom color overrides
