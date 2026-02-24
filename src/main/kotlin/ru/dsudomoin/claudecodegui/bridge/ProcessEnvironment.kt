package ru.dsudomoin.claudecodegui.bridge

import java.io.File

/**
 * Configures the environment for spawned Node.js processes.
 *
 * IntelliJ launches as a GUI app with a minimal PATH.
 * Tools installed via Homebrew, nvm, fnm, Volta, nvm4w are not visible.
 * This object ensures the detected node directory is prepended to PATH.
 */
object ProcessEnvironment {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /**
     * Configures a [ProcessBuilder] with the correct PATH for Node.js.
     * Prepends the directory containing the detected node binary so that
     * npm, npx, and any child processes can resolve `node` via PATH.
     *
     * On Windows: sets both PATH and Path, adds common npm paths.
     */
    fun ProcessBuilder.withNodeEnvironment(nodePath: String): ProcessBuilder {
        val env = environment()
        val nodeDir = File(nodePath).parentFile.absolutePath
        val sep = File.pathSeparator

        if (isWindows) {
            // Windows PATH is case-insensitive but env map is case-sensitive.
            // Find the existing PATH value regardless of case.
            val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
            val currentPath = env[pathKey] ?: ""

            val pathParts = mutableListOf(nodeDir)

            // Add common Windows npm/node paths
            val appData = System.getenv("APPDATA")
            val localAppData = System.getenv("LOCALAPPDATA")
            if (appData != null) {
                pathParts.add("$appData\\npm")
            }
            if (localAppData != null) {
                pathParts.add("$localAppData\\Programs\\nodejs")
            }

            val newPath = (pathParts + currentPath).joinToString(sep)

            // Remove all case variants, then set both PATH and Path
            env.keys.filter { it.equals("PATH", ignoreCase = true) }.forEach { env.remove(it) }
            env["PATH"] = newPath
            env["Path"] = newPath
        } else {
            val currentPath = env["PATH"] ?: ""
            env["PATH"] = if (currentPath.isEmpty()) nodeDir else "$nodeDir$sep$currentPath"
        }

        // Ensure HOME is set — npm needs it for cache/config
        if (env["HOME"] == null) {
            env["HOME"] = System.getProperty("user.home")
        }

        return this
    }
}