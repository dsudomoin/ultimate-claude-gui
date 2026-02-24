package ru.dsudomoin.claudecodegui

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import ru.dsudomoin.claudecodegui.service.SettingsService
import java.text.MessageFormat
import java.util.*

private const val BUNDLE = "messages.MyMessageBundle"

internal object MyMessageBundle {

    /** DynamicBundle respects IDE locale (not system locale). Used for "IDE Default" mode. */
    private val dynamicBundle = DynamicBundle(MyMessageBundle::class.java, BUNDLE)

    /**
     * Returns an explicit-locale ResourceBundle when user chose "en" or "ru",
     * or null to fall back to DynamicBundle (IDE default).
     */
    private fun getOverrideBundle(): ResourceBundle? {
        val lang = try {
            SettingsService.getInstance().state.language
        } catch (_: Exception) {
            ""
        }
        if (lang.isBlank()) return null
        val locale = when (lang) {
            "en" -> Locale.ENGLISH
            "ru" -> Locale.of("ru")
            else -> return null
        }
        return ResourceBundle.getBundle(BUNDLE, locale)
    }

    @JvmStatic
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any?): String {
        val override = getOverrideBundle()
        if (override != null) {
            val pattern = override.getString(key)
            return if (params.isEmpty()) pattern
            else MessageFormat.format(pattern, *params)
        }
        return dynamicBundle.getMessage(key, *params)
    }

    /** Call after language setting changes to pick up the new locale. */
    @JvmStatic
    fun clearCache() {
        ResourceBundle.clearCache(MyMessageBundle::class.java.classLoader)
    }
}
