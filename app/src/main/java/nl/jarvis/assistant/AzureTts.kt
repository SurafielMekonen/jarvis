package nl.jarvis.assistant

import java.net.HttpURLConnection
import java.net.URL

/** Haalt natuurlijke, menselijke spraak op bij Microsoft Azure Speech (kant-en-klare stem, geen nabootsing van iemand). */
object AzureTts {
    private fun voiceFor(lang: String): String =
        if (lang == "en") "en-US-GuyNeural" else "nl-NL-MaartenNeural"

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Geeft de audio (mp3-bytes) terug, of null als het niet lukte (dan valt de app terug op de ingebouwde stem). */
    fun synthesize(key: String, region: String, text: String, lang: String): ByteArray? {
        if (key.isBlank() || region.isBlank() || text.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            val voice = voiceFor(lang)
            val xmlLang = if (lang == "en") "en-US" else "nl-NL"
            val ssml = "<speak version='1.0' xml:lang='$xmlLang'>" +
                "<voice xml:lang='$xmlLang' xml:gender='Male' name='$voice'>" +
                "<prosody rate='-6%' pitch='-18%'>" + escape(text) + "</prosody></voice></speak>"

            val url = URL("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
            val c = url.openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Ocp-Apim-Subscription-Key", key)
            c.setRequestProperty("Content-Type", "application/ssml+xml")
            c.setRequestProperty("X-Microsoft-OutputFormat", "audio-16khz-128kbitrate-mono-mp3")
            c.setRequestProperty("User-Agent", "JarvisAssistant")
            c.outputStream.use { it.write(ssml.toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                JLog.add("Azure-stem fout $code: " + err.take(150))
                null
            } else {
                c.inputStream.use { it.readBytes() }
            }
        } catch (e: Exception) {
            JLog.add("Azure-stem onbereikbaar: " + e.message)
            null
        } finally {
            conn?.disconnect()
        }
    }
}
