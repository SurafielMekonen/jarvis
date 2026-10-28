package nl.jarvis.assistant

import java.net.HttpURLConnection
import java.net.URL

/** Haalt natuurlijke, menselijke spraak op bij Microsoft Azure Speech (kant-en-klare stem, geen nabootsing van iemand). */
object AzureTts {
    // Kant-en-klare, diepe mannenstemmen van Azure per taal. Alleen nl-NL en en-US zijn hier echt uitgeprobeerd;
    // voor andere talen wordt een gangbare mannenstem geprobeerd, met terugval op de ingebouwde telefoonstem als dat niet bestaat.
    private val knownVoices = mapOf(
        "nl-NL" to "nl-NL-MaartenNeural",
        "en-US" to "en-US-GuyNeural",
        "en-GB" to "en-GB-RyanNeural",
        "de-DE" to "de-DE-ConradNeural",
        "fr-FR" to "fr-FR-HenriNeural",
        "es-ES" to "es-ES-AlvaroNeural",
        "it-IT" to "it-IT-DiegoNeural",
        "pt-PT" to "pt-PT-DuarteNeural",
        "ar-SA" to "ar-SA-HamedNeural",
        "tr-TR" to "tr-TR-AhmetNeural",
        "ru-RU" to "ru-RU-DmitryNeural",
        "zh-CN" to "zh-CN-YunxiNeural",
        "ja-JP" to "ja-JP-KeitaNeural",
        "ko-KR" to "ko-KR-InJoonNeural",
        "hi-IN" to "hi-IN-MadhurNeural",
        "pl-PL" to "pl-PL-MarekNeural",
        "sv-SE" to "sv-SE-MattiasNeural",
        "nl-BE" to "nl-BE-ArnaudNeural"
    )

    private fun voiceFor(locale: String): String = knownVoices[locale] ?: (locale + "-Neural")

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Geeft de audio (mp3-bytes) terug, of null als het niet lukte (dan valt de app terug op de ingebouwde stem). */
    fun synthesize(key: String, region: String, text: String, locale: String): ByteArray? {
        if (key.isBlank() || region.isBlank() || text.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            val xmlLang = locale.ifBlank { "nl-NL" }
            val voice = voiceFor(xmlLang)
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
