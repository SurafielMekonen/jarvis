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

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
