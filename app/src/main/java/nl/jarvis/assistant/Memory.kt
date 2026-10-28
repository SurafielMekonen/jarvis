package nl.jarvis.assistant

import android.content.Context

/** Blijvend geheugen: Jarvis onthoudt wat hij leert en gebruikt dat bij elk gesprek. */
object Memory {
    fun notes(ctx: Context): List<String> =
        Prefs(ctx).memory.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    fun add(ctx: Context, note: String): String {
        val list = notes(ctx).toMutableList()
        val clean = note.replace("\n", " ").trim().take(220)
        if (clean.isEmpty()) return "Niets om te onthouden."
        if (list.none { it.equals(clean, ignoreCase = true) }) list.add(clean)
        while (list.size > 60) list.removeAt(0)
        Prefs(ctx).memory = list.joinToString("\n")
        JLog.add("Geleerd: $clean")
        return "Onthouden."
    }

    fun forget(ctx: Context, contains: String): String {
        val list = notes(ctx).filter { !it.contains(contains, ignoreCase = true) }
        Prefs(ctx).memory = list.joinToString("\n")
        return "Vergeten."
    }

    fun asPrompt(ctx: Context): String {
        val n = notes(ctx)
        return if (n.isEmpty()) "" else "\n\nDit heb je eerder geleerd over de gebruiker en zijn telefoon:\n- " + n.joinToString("\n- ")
    }
}
