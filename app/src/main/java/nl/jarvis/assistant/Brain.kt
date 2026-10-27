package nl.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime

/** Het brein: praat met Claude en voert tool-aanroepen uit (agent-loop). Geen externe bibliotheken nodig. */
object Brain {
    private val history = JSONArray()

    private fun systemPrompt(): String =
        "Je bent Jarvis, een persoonlijke spraakassistent op de Android-telefoon van de gebruiker. " +
            "Antwoord altijd in het Nederlands, kort en natuurlijk. Je antwoord wordt hardop voorgelezen, " +
            "dus gebruik geen opmaak, lijsten of emoji's. Voer opdrachten direct uit met je tools zonder " +
            "eerst te vragen, behalve als essentiële informatie ontbreekt, bijvoorbeeld welke contactpersoon. " +
            "Houd je antwoord onder de twee zinnen. " +
            "Het is nu " + ZonedDateTime.now().toString() + "."

    private fun clearHistory() {
        while (history.length() > 0) history.remove(0)
    }

    private fun post(key: String, body: String): Pair<Int, String> {
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(raw: String): String {
        return try {
            JSONObject(raw).getJSONObject("error").getString("message")
        } catch (e: Exception) {
            raw.take(200)
        }
    }

    @Synchronized
    fun ask(ctx: Context, userText: String): String {
        val prefs = Prefs(ctx)
        if (prefs.claudeKey.isBlank()) return "Er is nog geen Claude API sleutel ingesteld."

        if (history.length() > 40) clearHistory()
        history.put(JSONObject().put("role", "user").put("content", userText))

        try {
            for (round in 0 until 6) {
                val body = JSONObject()
                    .put("model", prefs.model)
                    .put("max_tokens", 1024)
                    .put("system", systemPrompt())
                    .put("tools", Tools.schema())
                    .put("messages", history)

                val result = post(prefs.claudeKey, body.toString())
                val code = result.first
                val raw = result.second
                if (code !in 200..299) {
                    clearHistory()
                    JLog.add("AI-fout $code: " + errorMessage(raw))
                    return "De AI gaf een fout terug, code $code: " + errorMessage(raw)
                }

                val json = JSONObject(raw)
                val content = json.getJSONArray("content")
                history.put(JSONObject().put("role", "assistant").put("content", content))

                if (json.optString("stop_reason") != "tool_use") {
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.getString("type") == "text") sb.append(block.getString("text")).append(' ')
                    }
                    val answer = sb.toString().trim()
                    return if (answer.isEmpty()) "Klaar." else answer
                }

                val results = JSONArray()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.getString("type") != "tool_use") continue
                    val output = try {
                        Tools.run(ctx, block.getString("name"), block.getJSONObject("input"))
                    } catch (e: Exception) {
                        "Fout: " + e.message
                    }
                    JLog.add("Actie " + block.getString("name") + ": " + output.take(90))
                    results.put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", block.getString("id"))
                            .put("content", output.ifEmpty { "Gedaan." })
                    )
                }
                history.put(JSONObject().put("role", "user").put("content", results))
            }
        } catch (e: Exception) {
            clearHistory()
            JLog.add("AI onbereikbaar: " + e.message)
            return "Ik kan de AI nu niet bereiken: " + e.message
        }
        return "Klaar."
    }
}
