package nl.jarvis.assistant

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var claudeKey: String
        get() = sp.getString("claude_key", "") ?: ""
        set(v) {
            sp.edit().putString("claude_key", v.trim()).apply()
        }

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) {
            sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()
        }

    var muteBeeps: Boolean
        get() = sp.getBoolean("mute_beeps", true)
        set(v) {
            sp.edit().putBoolean("mute_beeps", v).apply()
        }

    var voiceName: String
        get() = sp.getString("voice_name", "") ?: ""
        set(v) {
            sp.edit().putString("voice_name", v).apply()
        }

    /** Wat Jarvis over jou en je telefoon heeft geleerd, één notitie per regel. */
    var memory: String
        get() = sp.getString("memory", "") ?: ""
        set(v) {
            sp.edit().putString("memory", v).apply()
        }

    /** Taalcode voor spraak, bijvoorbeeld nl-NL, en-US, ar-SA, es-ES, fr-FR, de-DE, zh-CN, ja-JP. */
    var language: String
        get() = sp.getString("language", "nl-NL") ?: "nl-NL"
        set(v) {
            sp.edit().putString("language", v.trim().ifEmpty { "nl-NL" }).apply()
        }

    var azureKey: String
        get() = sp.getString("azure_key", "") ?: ""
        set(v) {
            sp.edit().putString("azure_key", v.trim()).apply()
        }

    var azureRegion: String
        get() = sp.getString("azure_region", "westeurope") ?: "westeurope"
        set(v) {
            sp.edit().putString("azure_region", v.trim().ifEmpty { "westeurope" }).apply()
        }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
