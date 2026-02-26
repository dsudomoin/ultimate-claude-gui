package ru.dsudomoin.claudecodegui.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Reads and manages OAuth credentials from Claude Code CLI session.
 *
 * Token sources (in priority order):
 * 1. macOS Keychain: service "Claude Code-credentials"
 * 2. File fallback: ~/.claude/.credentials.json
 *
 * Handles automatic token refresh when expired.
 */
@Service(Service.Level.APP)
class OAuthCredentialService {

    private val log = Logger.getInstance(OAuthCredentialService::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    @Volatile
    private var cachedCredentials: OAuthCredentials? = null

    // --- Public API ---

    fun getAccessToken(): String? {
        val creds = loadCredentials() ?: return null

        // Check if token is expired (with 5-minute buffer)
        if (creds.claudeAiOauth.expiresAt < System.currentTimeMillis() + 300_000) {
            log.info("OAuth token expired or expiring soon, needs refresh")
            return null // Caller should handle refresh or re-read
        }

        return creds.claudeAiOauth.accessToken
    }

    suspend fun getAccessTokenWithRefresh(): String? {
        val creds = loadCredentials() ?: return null
        val oauth = creds.claudeAiOauth

        // If token is still valid (with 5-minute buffer), return it
        if (oauth.expiresAt > System.currentTimeMillis() + 300_000) {
            return oauth.accessToken
        }

        // Token expired — try refresh
        log.info("OAuth token expired, attempting refresh...")
        val refreshToken = oauth.refreshToken
        if (refreshToken.isBlank()) {
            log.warn("No refresh token available")
            return null
        }

        return try {
            val newTokens = refreshAccessToken(refreshToken)
            if (newTokens != null) {
                // Update cached credentials with new tokens
                val updated = creds.copy(
                    claudeAiOauth = oauth.copy(
                        accessToken = newTokens.accessToken,
                        refreshToken = newTokens.refreshToken ?: refreshToken,
                        expiresAt = System.currentTimeMillis() + (newTokens.expiresIn * 1000L)
                    )
                )
                cachedCredentials = updated
                saveCredentials(updated)
                log.info("OAuth token refreshed successfully")
                newTokens.accessToken
            } else {
                log.warn("Token refresh returned null")
                null
            }
        } catch (e: Exception) {
            log.error("Token refresh failed", e)
            null
        }
    }

    fun isLoggedIn(): Boolean {
        val creds = loadCredentials()
        return creds?.claudeAiOauth?.accessToken?.isNotBlank() == true
    }

    fun getLoginInfo(): LoginInfo {
        val creds = loadCredentials() ?: return LoginInfo(loggedIn = false, source = "none")
        val oauth = creds.claudeAiOauth
        val expired = oauth.expiresAt < System.currentTimeMillis()
        val source = if (isMacOS()) "macOS Keychain" else "~/.claude/.credentials.json"
        return LoginInfo(
            loggedIn = oauth.accessToken.isNotBlank(),
            expired = expired,
            source = source,
            scopes = oauth.scopes
        )
    }

    fun clearCache() {
        cachedCredentials = null
    }

    // --- Credential Loading ---

    private fun loadCredentials(): OAuthCredentials? {
        cachedCredentials?.let { return it }

        val creds = if (isMacOS()) {
            readFromKeychain() ?: readFromFile()
        } else {
            readFromFile()
        }

        cachedCredentials = creds
        return creds
    }

    private fun readFromKeychain(): OAuthCredentials? {
        return try {
            val serviceNames = listOf("Claude Code-credentials", "Claude Code")
            for (serviceName in serviceNames) {
                val process = ProcessBuilder(
                    "security", "find-generic-password",
                    "-s", serviceName, "-w"
                ).redirectErrorStream(false).start()

                val output = process.inputStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()

                if (exitCode == 0 && output.isNotBlank()) {
                    val creds = json.decodeFromString<OAuthCredentials>(output)
                    if (creds.claudeAiOauth.accessToken.isNotBlank()) {
                        log.info("OAuth credentials loaded from macOS Keychain (service: $serviceName)")
                        return creds
                    }
                }
            }
            null
        } catch (e: Exception) {
            log.warn("Failed to read from macOS Keychain", e)
            null
        }
    }

    private fun readFromFile(): OAuthCredentials? {
        return try {
            val credFile = File(Paths.get(System.getProperty("user.home"), ".claude", ".credentials.json").toString())
            if (!credFile.exists()) {
                log.info("Credentials file not found: ${credFile.absolutePath}")
                return null
            }
            val content = credFile.readText()
            val creds = json.decodeFromString<OAuthCredentials>(content)
            if (creds.claudeAiOauth.accessToken.isNotBlank()) {
                log.info("OAuth credentials loaded from file: ${credFile.absolutePath}")
                return creds
            }
            null
        } catch (e: Exception) {
            log.warn("Failed to read credentials file", e)
            null
        }
    }

    // --- Token Refresh ---

    private suspend fun refreshAccessToken(refreshToken: String): TokenResponse? {
        val formBody = listOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to OAUTH_CLIENT_ID
        ).joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build()

        val response = httpClient
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .orTimeout(20, TimeUnit.SECONDS)
            .await()

        if (response.statusCode() != 200) {
            log.warn("Token refresh failed: status=${response.statusCode()}")
            return null
        }

        return json.decodeFromString<TokenResponse>(response.body())
    }

    // --- Credential Saving ---

    private fun saveCredentials(creds: OAuthCredentials) {
        if (isMacOS()) {
            saveToKeychain(creds)
        } else {
            saveToFile(creds)
        }
    }

    private fun saveToKeychain(creds: OAuthCredentials) {
        try {
            val jsonStr = json.encodeToString(OAuthCredentials.serializer(), creds)
            val serviceNames = listOf("Claude Code-credentials")
            for (serviceName in serviceNames) {
                // Delete old entry (ignore errors if not found)
                ProcessBuilder(
                    "security", "delete-generic-password",
                    "-s", serviceName
                ).start().waitFor()

                // Add new entry
                val process = ProcessBuilder(
                    "security", "add-generic-password",
                    "-s", serviceName,
                    "-a", System.getProperty("user.name"),
                    "-w", jsonStr,
                    "-U" // update if exists
                ).start()
                process.waitFor()
            }
            log.info("Updated OAuth credentials in macOS Keychain")
        } catch (e: Exception) {
            log.warn("Failed to save to macOS Keychain, falling back to file", e)
            saveToFile(creds)
        }
    }

    private fun saveToFile(creds: OAuthCredentials) {
        try {
            val credFile = File(Paths.get(System.getProperty("user.home"), ".claude", ".credentials.json").toString())
            credFile.parentFile.mkdirs()
            val jsonStr = json.encodeToString(OAuthCredentials.serializer(), creds)
            credFile.writeText(jsonStr)
            // Set permissions to owner-only
            credFile.setReadable(false, false)
            credFile.setReadable(true, true)
            credFile.setWritable(false, false)
            credFile.setWritable(true, true)
            log.info("Saved OAuth credentials to file")
        } catch (e: Exception) {
            log.error("Failed to save credentials file", e)
        }
    }

    // --- Helpers ---

    private fun isMacOS(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

    companion object {
        private const val OAUTH_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        private const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
        const val OAUTH_BETA_HEADER = "oauth-2025-04-20"

        fun getInstance(): OAuthCredentialService =
            ApplicationManager.getApplication().getService(OAuthCredentialService::class.java)
    }
}

// --- Data Models ---

@Serializable
data class OAuthCredentials(
    val claudeAiOauth: ClaudeAiOauth = ClaudeAiOauth()
)

@Serializable
data class ClaudeAiOauth(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val scopes: List<String> = emptyList(),
    val subscriptionType: String? = null,
    val rateLimitTier: String? = null
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Int = 3600,
    @SerialName("token_type") val tokenType: String = "bearer"
)

data class LoginInfo(
    val loggedIn: Boolean,
    val expired: Boolean = false,
    val source: String = "",
    val scopes: List<String> = emptyList()
)
