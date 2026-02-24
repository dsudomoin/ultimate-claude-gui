package ru.dsudomoin.claudecodegui.bridge

import com.intellij.openapi.diagnostic.Logger
import ru.dsudomoin.claudecodegui.bridge.ProcessEnvironment.withNodeEnvironment
import java.io.File
import java.nio.file.Paths

/**
 * Manages the Node.js bridge lifecycle:
 * 1. Extracts bridge resources to ~/.claude-code-gui/bridge/
 * 2. Runs `npm install` if node_modules is missing
 * 3. Provides the bridge directory path for process spawning
 */
object BridgeManager {

    private val log = Logger.getInstance(BridgeManager::class.java)

    private val bridgeDir: File by lazy {
        val home = System.getProperty("user.home")
        File(Paths.get(home, ".claude-code-gui", "bridge").toString())
    }

    val bridgeScript: File get() = File(bridgeDir, "claude-bridge.mjs")
    val isReady: Boolean get() = bridgeScript.exists() && File(bridgeDir, "node_modules").isDirectory

    /**
     * Ensures the bridge is extracted and dependencies are installed.
     * Should be called from a background thread.
     *
     * Always re-extracts bridge scripts to pick up plugin updates.
     *
     * @return true if the bridge is ready to use
     */
    fun ensureReady(): Boolean {
        try {
            extractBridge()
            if (!File(bridgeDir, "node_modules").isDirectory) {
                installDependencies()
            }
            return isReady
        } catch (e: Exception) {
            log.error("Bridge setup failed", e)
            return false
        }
    }

    /**
     * Ensures bridge scripts are up-to-date on disk.
     * Called before each query to pick up changes after plugin updates.
     */
    fun ensureScriptsUpdated() {
        try {
            extractBridge()
        } catch (e: Exception) {
            log.warn("Failed to update bridge scripts: ${e.message}")
        }
    }

    /**
     * Extracts bridge files from plugin JAR resources to disk.
     * Overwrites existing files to ensure the latest version is used.
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
            input.use { src ->
                target.outputStream().use { dst ->
                    src.copyTo(dst)
                }
            }
            log.info("Extracted bridge resource: ${target.absolutePath}")
        }
    }

    /**
     * Runs `npm install` in the bridge directory.
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

        log.info("Running npm install in ${bridgeDir.absolutePath}")

        val process = ProcessBuilder(npmCmd, "install", "--omit=dev", "--no-optional")
            .directory(bridgeDir)
            .withNodeEnvironment(nodePath)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            log.error("npm install failed (exit=$exitCode): $output")
            throw RuntimeException("npm install failed: $output")
        }

        log.info("npm install completed successfully")
    }

    /**
     * Force re-install of dependencies (e.g., after plugin update).
     */
    fun reinstall() {
        val nodeModules = File(bridgeDir, "node_modules")
        if (nodeModules.exists()) {
            nodeModules.deleteRecursively()
        }
        ensureReady()
    }
}
