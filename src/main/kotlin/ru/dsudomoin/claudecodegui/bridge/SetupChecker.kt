package ru.dsudomoin.claudecodegui.bridge

import ru.dsudomoin.claudecodegui.service.OAuthCredentialService
import java.io.File

/**
 * Aggregates all setup checks into a single status structure.
 * Used by ChatController to show a setup screen when not ready.
 */
object SetupChecker {

    /**
     * Performs all checks (blocking — call from Dispatchers.IO).
     */
    fun check(): SetupStatus {
        val nodeStatus = checkNode()
        val sdkStatus = checkSdk()
        val authStatus = checkAuth()
        val claudeCliPath = findClaudeCli()
        return SetupStatus(nodeStatus, sdkStatus, authStatus, claudeCliPath)
    }

    private fun checkNode(): NodeStatus {
        val nvmAvailable = NodeDetector.isNvmAvailable()
        val fnmAvailable = NodeDetector.isFnmAvailable()
        val brewAvailable = NodeDetector.isBrewAvailable()

        val path = NodeDetector.detect()
            ?: return NodeStatus(
                state = NodeState.NOT_FOUND,
                nvmAvailable = nvmAvailable,
                fnmAvailable = fnmAvailable,
                brewAvailable = brewAvailable,
            )

        val version = NodeDetector.detectVersion(path)
            ?: return NodeStatus(
                state = NodeState.NOT_FOUND,
                path = path,
                nvmAvailable = nvmAvailable,
                fnmAvailable = fnmAvailable,
                brewAvailable = brewAvailable,
            )

        return if (NodeDetector.isCompatibleVersion(version)) {
            NodeStatus(state = NodeState.OK, path = path, version = version,
                nvmAvailable = nvmAvailable, fnmAvailable = fnmAvailable, brewAvailable = brewAvailable)
        } else {
            NodeStatus(state = NodeState.WRONG_VERSION, path = path, version = version,
                nvmAvailable = nvmAvailable, fnmAvailable = fnmAvailable, brewAvailable = brewAvailable)
        }
    }

    private fun checkSdk(): SdkStatus {
        val version = BridgeManager.detectSdkVersion()
        return SdkStatus(
            installed = version != null,
            version = version,
        )
    }

    private fun checkAuth(): AuthStatus {
        return try {
            val info = OAuthCredentialService.getInstance().getLoginInfo()
            when {
                !info.loggedIn -> AuthStatus(state = AuthState.NOT_LOGGED_IN)
                info.expired -> AuthStatus(state = AuthState.EXPIRED)
                else -> AuthStatus(state = AuthState.OK, source = info.source)
            }
        } catch (_: Exception) {
            AuthStatus(state = AuthState.NOT_LOGGED_IN)
        }
    }

    private fun findClaudeCli(): String? {
        // 1. Bridge node_modules/.bin/claude
        val bridgeClaude = File(BridgeManager.bridgeDir, "node_modules/.bin/claude")
        if (bridgeClaude.canExecute()) return bridgeClaude.absolutePath

        // 2. System-wide claude
        return NodeDetector.detectClaude()
    }
}

data class SetupStatus(
    val node: NodeStatus,
    val sdk: SdkStatus,
    val auth: AuthStatus,
    val claudeCliPath: String? = null,
) {
    val allReady: Boolean
        get() = node.state == NodeState.OK && sdk.installed && auth.state == AuthState.OK

    val canInstallSdk: Boolean
        get() = node.state == NodeState.OK && !sdk.installed

    val canLogin: Boolean
        get() = auth.state != AuthState.OK && claudeCliPath != null

    val canInstallNode: Boolean
        get() = node.state != NodeState.OK && (node.nvmAvailable || node.fnmAvailable || node.brewAvailable)
}

data class NodeStatus(
    val state: NodeState = NodeState.NOT_FOUND,
    val path: String? = null,
    val version: String? = null,
    val nvmAvailable: Boolean = false,
    val fnmAvailable: Boolean = false,
    val brewAvailable: Boolean = false,
)

enum class NodeState { NOT_FOUND, WRONG_VERSION, OK }

data class SdkStatus(
    val installed: Boolean = false,
    val version: String? = null,
)

data class AuthStatus(
    val state: AuthState = AuthState.NOT_LOGGED_IN,
    val source: String? = null,
)

enum class AuthState { NOT_LOGGED_IN, EXPIRED, OK }
