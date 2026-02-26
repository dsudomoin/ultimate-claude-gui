package ru.dsudomoin.claudecodegui.bridge

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.dsudomoin.claudecodegui.bridge.ProcessEnvironment.withNodeEnvironment
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Paths

/**
 * Manages the Node.js bridge lifecycle:
 * 1. Extracts bridge resources to ~/.claude-code-gui/bridge/
 * 2. Runs `npm install` if node_modules is missing
 * 3. Provides the bridge directory path for process spawning
 */
object BridgeManager {

    private val log = Logger.getInstance(BridgeManager::class.java)
    private val mutex = Mutex()

    val bridgeDir: File by lazy {
        val home = System.getProperty("user.home")
        File(Paths.get(home, ".claude-code-gui", "bridge").toString())
    }

    val bridgeScript: File get() = File(bridgeDir, "claude-bridge.mjs")
    val isReady: Boolean get() = bridgeScript.exists() && File(bridgeDir, "node_modules").isDirectory

    /**
     * Ensures the bridge is extracted and dependencies are installed.
     * Thread-safe via Mutex — concurrent callers will wait.
     *
     * Always re-extracts bridge scripts to pick up plugin updates.
     *
     * @return true if the bridge is ready to use
     */
    suspend fun ensureReady(): Boolean = mutex.withLock {
        try {
            extractBridge()
            val nodeModules = File(bridgeDir, "node_modules")
            val needsInstall = !nodeModules.isDirectory
                // If old SDK package exists but new one doesn't, need reinstall
                || (File(bridgeDir, "node_modules/@anthropic-ai/claude-code").isDirectory
                    && !File(bridgeDir, "node_modules/@anthropic-ai/claude-agent-sdk").isDirectory)
            if (needsInstall) {
                if (nodeModules.exists()) nodeModules.deleteRecursively()
                installDependencies()
            }
            isReady
        } catch (e: Exception) {
            log.error("Bridge setup failed", e)
            false
        }
    }

    /**
     * Ensures bridge scripts are up-to-date on disk.
     * Called before each query to pick up changes after plugin updates.
     */
    suspend fun ensureScriptsUpdated() = mutex.withLock {
        try {
            extractBridge()
        } catch (e: Exception) {
            log.warn("Failed to update bridge scripts: ${e.message}")
        }
    }

    /**
     * Extracts bridge files from plugin JAR resources to disk.
     * Uses atomic move for crash-safety: writes to tmp file first, then renames.
     */
    private fun extractBridge() {
        bridgeDir.mkdirs()

        val resources = listOf("claude-bridge.mjs", "package.json")
        for (name in resources) {
            val input = javaClass.getResourceAsStream("/bridge/$name")
            if (input == null) {
                log.error("Bridge resource not found: /bridge/$name")
                continue
            }
            val target = File(bridgeDir, name)
            val tmp = File(bridgeDir, "$name.tmp")
            input.use { src ->
                tmp.outputStream().use { dst ->
                    src.copyTo(dst)
                }
            }
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.io.IOException) {
                // Filesystem doesn't support atomic move — fallback to plain replace
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            log.info("Extracted bridge resource: ${target.absolutePath}")
        }
    }

    /**
     * Runs `npm ci` (or falls back to `npm install`) in the bridge directory.
     * Requires node/npm to be available on PATH or detected by NodeDetector.
     */
    private fun installDependencies() {
        val nodePath = NodeDetector.detect()
            ?: throw IllegalStateException("Node.js not found. Install Node.js 18+ first.")

        // Derive npm path from node path
        val nodeDir = File(nodePath).parentFile
        val npmCmd = if (System.getProperty("os.name").lowercase().contains("win")) {
            File(nodeDir, "npm.cmd").absolutePath
        } else {
            File(nodeDir, "npm").absolutePath.takeIf { File(it).canExecute() }
                ?: "npm" // fallback to PATH
        }

        // Try npm ci first (faster, reproducible), fall back to npm install
        val hasLockFile = File(bridgeDir, "package-lock.json").exists()
        val installArgs = if (hasLockFile) {
            listOf(npmCmd, "ci", "--omit=dev", "--no-optional")
        } else {
            listOf(npmCmd, "install", "--omit=dev", "--no-optional")
        }

        log.info("Running ${installArgs.drop(1).joinToString(" ")} in ${bridgeDir.absolutePath}")

        val process = ProcessBuilder(installArgs)
            .directory(bridgeDir)
            .withNodeEnvironment(nodePath)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            // If npm ci failed, fall back to npm install
            if (hasLockFile) {
                log.warn("npm ci failed (exit=$exitCode), falling back to npm install")
                val fallback = ProcessBuilder(npmCmd, "install", "--omit=dev", "--no-optional")
                    .directory(bridgeDir)
                    .withNodeEnvironment(nodePath)
                    .redirectErrorStream(true)
                    .start()
                val fbOutput = fallback.inputStream.bufferedReader().readText()
                val fbExit = fallback.waitFor()
                if (fbExit != 0) {
                    log.error("npm install failed (exit=$fbExit): $fbOutput")
                    throw RuntimeException("npm install failed: $fbOutput")
                }
            } else {
                log.error("npm install failed (exit=$exitCode): $output")
                throw RuntimeException("npm install failed: $output")
            }
        }

        log.info("npm install completed successfully")
    }

    /**
     * Returns the installed SDK version (e.g. "1.0.18"), or null if not installed.
     */
    fun detectSdkVersion(): String? {
        // Check new Agent SDK package first, then legacy claude-code package
        val pkgFile = File(bridgeDir, "node_modules/@anthropic-ai/claude-agent-sdk/package.json")
            .takeIf { it.exists() }
            ?: File(bridgeDir, "node_modules/@anthropic-ai/claude-code/package.json")
        if (!pkgFile.exists()) return null
        return try {
            val json = Json.parseToJsonElement(pkgFile.readText()).jsonObject
            json["version"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            log.warn("Failed to read SDK version: ${e.message}")
            null
        }
    }

    /**
     * Queries npm registry for the latest SDK version.
     * Returns version string (e.g. "0.3.0") or null if offline/error.
     */
    fun checkLatestSdkVersion(): String? {
        val nodePath = NodeDetector.detect() ?: return null
        val nodeDir = File(nodePath).parentFile
        val npmCmd = if (System.getProperty("os.name").lowercase().contains("win")) {
            File(nodeDir, "npm.cmd").absolutePath
        } else {
            File(nodeDir, "npm").absolutePath.takeIf { File(it).canExecute() }
                ?: "npm"
        }

        return try {
            val process = ProcessBuilder(npmCmd, "view", "@anthropic-ai/claude-agent-sdk", "version")
                .directory(bridgeDir)
                .withNodeEnvironment(nodePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.matches(Regex("\\d+\\.\\d+\\.\\d+.*"))) {
                output
            } else {
                log.warn("npm view failed (exit=$exitCode): $output")
                null
            }
        } catch (e: Exception) {
            log.warn("Failed to check latest SDK version: ${e.message}")
            null
        }
    }

    /**
     * Updates SDK to latest version via npm install.
     * Thread-safe via Mutex.
     */
    suspend fun updateSdk(): Boolean = mutex.withLock {
        val nodePath = NodeDetector.detect()
            ?: throw IllegalStateException("Node.js not found")

        val nodeDir = File(nodePath).parentFile
        val npmCmd = if (System.getProperty("os.name").lowercase().contains("win")) {
            File(nodeDir, "npm.cmd").absolutePath
        } else {
            File(nodeDir, "npm").absolutePath.takeIf { File(it).canExecute() }
                ?: "npm"
        }

        log.info("Updating SDK to latest version...")
        val process = ProcessBuilder(npmCmd, "install", "@anthropic-ai/claude-agent-sdk@latest", "--omit=dev", "--no-optional")
            .directory(bridgeDir)
            .withNodeEnvironment(nodePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            log.error("SDK update failed (exit=$exitCode): $output")
            throw RuntimeException("npm install failed: $output")
        }
        log.info("SDK updated successfully")
        true
    }

    /**
     * Force re-install of dependencies (e.g., after plugin update).
     */
    suspend fun reinstall() = mutex.withLock {
        val nodeModules = File(bridgeDir, "node_modules")
        if (nodeModules.exists()) {
            nodeModules.deleteRecursively()
        }
        // Call internal logic directly (already holding the lock)
        try {
            extractBridge()
            installDependencies()
        } catch (e: Exception) {
            log.error("Reinstall failed", e)
        }
    }
}
