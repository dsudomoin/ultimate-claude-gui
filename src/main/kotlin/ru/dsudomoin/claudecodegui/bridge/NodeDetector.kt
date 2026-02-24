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
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun detect(): String? {
        // 1. User-configured path from settings
        try {
            val settings = SettingsService.getInstance()
            settings.state.nodePath.takeIf { it.isNotBlank() && fileExists(it) }?.let {
                log.info("Node found via settings: $it")
                return it
            }
        } catch (_: Exception) {
            // Settings might not be available during init
        }

        val home = System.getProperty("user.home")

        // 2. Common locations
        val candidates = if (isWindows) {
            val appData = System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
            val localAppData = System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local"
            val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            listOf(
                // nvm4w
                "$appData\\nvm\\current\\node.exe",
                // nvm4w symlink dir
                System.getenv("NVM_SYMLINK")?.let { "$it\\node.exe" },
                // fnm
                "$localAppData\\fnm_multishells\\default\\node.exe",
                // volta
                "$localAppData\\Volta\\bin\\node.exe",
                // direct install
                "$programFiles\\nodejs\\node.exe",
            ).filterNotNull()
        } else {
            listOf(
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
        }

        for (path in candidates) {
            if (fileExists(path)) {
                log.info("Node found at: $path")
                return path
            }
        }

        // 3. nvm — pick latest installed version
        if (!isWindows) {
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
        }

        // 4. which / where fallback
        return findViaWhich("node")
    }

    /**
     * Detects the `claude` CLI binary path.
     */
    fun detectClaude(): String? {
        // 1. User-configured path from settings
        try {
            val settings = SettingsService.getInstance()
            settings.state.claudeExecutablePath.takeIf { it.isNotBlank() && fileExists(it) }?.let {
                log.info("Claude found via settings: $it")
                return it
            }
        } catch (_: Exception) { }

        val home = System.getProperty("user.home")

        // 2. Common locations
        val candidates = if (isWindows) {
            val appData = System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
            val localAppData = System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local"
            listOf(
                // npm global (nvm4w, standard nodejs)
                "$appData\\nvm\\current\\claude.cmd",
                System.getenv("NVM_SYMLINK")?.let { "$it\\claude.cmd" },
                "$appData\\npm\\claude.cmd",
                "$localAppData\\Volta\\bin\\claude.exe",
                // Claude native app
                "$localAppData\\Programs\\claude\\claude.exe",
                "$localAppData\\AnthropicClaude\\claude.exe",
            ).filterNotNull()
        } else {
            listOf(
                "$home/.local/bin/claude",
                "$home/.claude/local/claude",
                "/opt/homebrew/bin/claude",
                "/usr/local/bin/claude",
            )
        }

        for (path in candidates) {
            if (fileExists(path)) {
                log.info("Claude found at: $path")
                return path
            }
        }

        // 3. which / where fallback
        return findViaWhich("claude")
    }

    /**
     * Check if a file exists and is usable.
     * On Windows, File.canExecute() may return false for .cmd files, so we just check exists().
     */
    private fun fileExists(path: String): Boolean {
        val f = File(path)
        return if (isWindows) f.exists() else f.canExecute()
    }

    /**
     * Find a binary via `which` (Unix) or `where` (Windows).
     * On Windows, `where` may return paths without extensions — we try adding .cmd/.exe.
     */
    private fun findViaWhich(name: String): String? {
        return try {
            val cmd = if (isWindows) "where" else "which"
            val proc = ProcessBuilder(cmd, name)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val exitCode = proc.waitFor()
            if (exitCode == 0 && output.isNotBlank()) {
                // `where` on Windows can return multiple lines
                for (line in output.lines()) {
                    val path = line.trim()
                    if (path.isBlank()) continue
                    if (fileExists(path)) {
                        log.info("$name found via $cmd: $path")
                        return path
                    }
                    // On Windows, try with extensions if path has none
                    if (isWindows && !path.contains('.')) {
                        for (ext in listOf(".cmd", ".exe", ".bat")) {
                            val withExt = "$path$ext"
                            if (fileExists(withExt)) {
                                log.info("$name found via $cmd (with ext): $withExt")
                                return withExt
                            }
                        }
                    }
                }
            }
            log.warn("$name not found on this system")
            null
        } catch (e: Exception) {
            log.warn("$name detection failed", e)
            null
        }
    }
}
