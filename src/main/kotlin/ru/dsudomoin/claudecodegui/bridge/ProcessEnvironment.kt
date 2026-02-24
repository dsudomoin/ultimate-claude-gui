package ru.dsudomoin.claudecodegui.bridge

import java.io.File

/**
 * Configures the environment for spawned Node.js processes.
 *
 * IntelliJ on macOS launches as a GUI app with a minimal PATH
 * (/usr/bin:/bin:/usr/sbin:/sbin). Tools installed via Homebrew, nvm, fnm,
 * or Volta are not visible. This object ensures the detected node directory
 * is prepended to PATH so that:
 *   - npm's `#!/usr/bin/env node` shebang resolves correctly
 *   - Child processes spawned by the bridge can find node
 */
object ProcessEnvironment {

    /**
     * Configures a [ProcessBuilder] with the correct PATH for Node.js.
     * Prepends the directory containing the detected node binary so that
     * npm, npx, and any child processes can resolve `node` via PATH.
     *
     * Also ensures HOME is set (some npm operations require it).
     */
    fun ProcessBuilder.withNodeEnvironment(nodePath: String): ProcessBuilder {
        val env = environment()
        val nodeDir = File(nodePath).parentFile.absolutePath

        // Prepend node directory to PATH
        val sep = File.pathSeparator
        val currentPath = env["PATH"] ?: ""
        env["PATH"] = if (currentPath.isEmpty()) nodeDir else "$nodeDir$sep$currentPath"

        // Ensure HOME is set — npm needs it for cache/config
        if (env["HOME"] == null) {
            env["HOME"] = System.getProperty("user.home")
        }

        return this
    }
}