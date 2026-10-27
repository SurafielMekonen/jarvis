package nl.jarvis.assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Eenvoudig logboek zodat je in de app kunt zien wat Jarvis hoort en doet. */
object JLog {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(msg: String) {
        lines.add(fmt.format(Date()) + "  " + msg)
        while (lines.size > 40) lines.removeAt(0)
    }

    @Synchronized
    fun text(): String {
        if (lines.isEmpty()) return "(nog niets gebeurd)"
        return lines.reversed().joinToString("\n")
    }
}
