package ru.dsudomoin.claudecodegui.bridge

import com.intellij.openapi.diagnostic.Logger
import ru.dsudomoin.claudecodegui.service.SettingsService
import java.io.File

/**
 * Locates the `node` and `claude` binaries on the system.
 *
 * Search order for node:
 * 1. Settings (user-configured path)
 * 2. Common install locations (nvm, fnm, homebrew, volta, system)
 * 3. PATH via `which`
 *
 * Search order for claude:
 * 1. Settings (user-configured path)
 * 2. Common install locations
 * 3. PATH via `which`
 */
object NodeDetector {

    private val log = Logger.getInstance(NodeDetector::class.java)

    fun detect(): String? {
        // 1. User-configured path from settings
        try {
            val settings = SettingsService.getInstance()
            settings.state.nodePath.takeIf { it.isNotBlank() && File(it).canExecute() }?.let {
                log.info("Node found via settings: $it")
                return it
            }
        } catch (_: Exception) {
            // Settings might not be available during init
        }

        // 2. Common locations (macOS / Linux)
        val home = System.getProperty("user.home")
        val candidates = listOf(
            // homebrew (Apple Silicon + Intel)
            "/opt/homebrew/bin/node",
            "/usr/local/bin/node",
            // nvm
            "$home/.nvm/current/bin/node",
            // fnm
            "$home/.local/share/fnm/aliases/default/bin/node",
            "$home/Library/Application Support/fnm/aliases/default/bin/node",
            // volta
            "$home/.volta/bin/node",
            // system
            "/usr/bin/node",
        )

        for (path in candidates) {
            if (File(path).canExecute()) {
                log.info("Node found at: $path")
                return path
            }
        }

        // 3. nvm — pick latest installed version
        val nvmVersionsDir = File("$home/.nvm/versions/node")
        if (nvmVersionsDir.isDirectory) {
            val latest = nvmVersionsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedDescending()
                ?.firstOrNull()
            val nodeBin = latest?.let { File(it, "bin/node") }
            if (nodeBin != null && nodeBin.canExecute()) {
                log.info("Node found via nvm versions: ${nodeBin.absolutePath}")
                return nodeBin.absolutePath
            }
        }

        // 4. which / where fallback
        return try {
            val cmd = if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which"
            val proc = ProcessBuilder(cmd, "node")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val exitCode = proc.waitFor()
            if (exitCode == 0 && output.isNotBlank()) {
                val firstLine = output.lines().first().trim()
                if (File(firstLine).canExecute()) {
                    log.info("Node found via $cmd: $firstLine")
                    return firstLine
                }
            }
            log.warn("Node not found on this system")
            null
        } catch (e: Exception) {
            log.warn("Node detection failed", e)
            null
        }
    }

    /**
     * Detects the `claude` CLI binary path.
     */
    fun detectClaude(): String? {
        // 1. User-configured path from settings
        try {
            val settings = SettingsService.getInstance()
            settings.state.claudeExecutablePath.takeIf { it.isNotBlank() && File(it).canExecute() }?.let {
                log.info("Claude found via settings: $it")
                return it
            }
        } catch (_: Exception) { }

        // 2. Common locations
        val home = System.getProperty("user.home")
        val candidates = listOf(
            "$home/.local/bin/claude",
            "$home/.claude/local/claude",
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",
        )

        for (path in candidates) {
            if (File(path).canExecute()) {
                log.info("Claude found at: $path")
                return path
            }
        }

        // 3. which fallback
        return try {
            val cmd = if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which"
            val proc = ProcessBuilder(cmd, "claude")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val exitCode = proc.waitFor()
            if (exitCode == 0 && output.isNotBlank()) {
                val firstLine = output.lines().first().trim()
                if (File(firstLine).canExecute()) {
                    log.info("Claude found via $cmd: $firstLine")
                    return firstLine
                }
            }
            log.warn("Claude CLI not found on this system")
            null
        } catch (e: Exception) {
            log.warn("Claude detection failed", e)
            null
        }
    }
}
