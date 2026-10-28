package nl.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime

/**
 * Het brein: praat met Claude en voert tool-aanroepen uit (agent-loop).
 * Het gesprek wordt na elke beurt compact bewaard (alleen tekst), zodat oude denkblokken
 * of zoekresultaten nooit een fout veroorzaken.
 */
object Brain {
    private val history = JSONArray()

    @Volatile
    private var webSearch = true

    private fun systemPrompt(ctx: Context): String =
        "Je bent Jarvis, de persoonlijke spraakassistent van de gebruiker op zijn Android-telefoon. " +
            "Je praat als een warm, slim mens en niet als een robot: gebruik natuurlijke Nederlandse spreektaal, " +
            "met af en toe een kort tussenwoord zoals 'Ja hoor', 'Oké', 'Mm, goed idee' of 'Even kijken'. " +
            "Je antwoord wordt hardop voorgelezen: gebruik dus geen opmaak, lijsten, sterretjes, emoji's of webadressen. " +
            "Houd het kort, meestal een tot drie zinnen, maar denk goed na en geef slimme, correcte antwoorden. " +
            "Bij onduidelijkheid stel je één korte vraag. " +
            "\n\nBERICHTEN EN POSTS: Verstuur of plaats nooit meteen. Lees eerst voor wat je hebt getypt, bijvoorbeeld: " +
            "'Ik heb dit getypt: ... Zeg verstuur als het goed is, of zeg wat ik moet aanpassen of toevoegen.' " +
            "Roep de tool voor sms, WhatsApp, e-mail, post of privébericht pas aan nadat de gebruiker duidelijk " +
            "bevestigt, bijvoorbeeld met 'verstuur', 'ja' of 'stuur maar', en gebruik dan precies de goedgekeurde tekst. " +
            "Wil de gebruiker iets toevoegen of verbeteren, pas de tekst aan en lees de nieuwe versie voor. " +
            "Bevestig na het versturen kort. " +
            "\n\nAGENDA EN TELEFOON: Agenda-afspraken en herinneringen maak je direct aan met add_calendar_event, zonder eerst voor te lezen, " +
            "en je bevestigt daarna kort, bijvoorbeeld 'Oké, ik heb hem gemaakt.' " +
            "Vraagt de gebruiker om de telefoon te vergrendelen of uit te zetten, gebruik dan lock_screen en zeg kort dat het scherm op slot is. " +
            "\n\nZOEKEN: Wil de gebruiker iets weten of laten opzoeken, gebruik dan web_search en vertel wat je vond, " +
            "bijvoorbeeld 'Ik heb dit gevonden: ...' met de belangrijkste twee of drie punten en eventueel de bron bij naam. " +
            "Gebruik google_search alleen als de gebruiker uitdrukkelijk wil dat Google op het scherm opent. " +
            "\n\nPROFIELEN: Vraagt de gebruiker om een persoon of profiel op een social-app te zoeken, gebruik dan find_profiles " +
            "en noem daarna precies welke profielen of namen op het scherm stonden, bijvoorbeeld 'Ik vond drie profielen: ...'. " +
            "Verzin nooit namen die niet in het resultaat staan. " +
            "\n\nAPPS BEDIENEN: Je kunt in apps tikken, typen en scrollen. Werkwijze: open_app, dan read_screen, kies het juiste nummer " +
            "met click_item (of click_text), typ met type_text en lees het scherm opnieuw na elke stap. Zoek je iets in een app (zoals een profiel op TikTok), " +
            "tik dan op het zoekicoon, typ de naam met submit=true, kies indien nodig het tabblad Gebruikers of Accounts, lees het scherm en noem precies " +
            "de profielen die je ziet, bijvoorbeeld 'Ik vond drie profielen: ...'. Open nooit de browser voor iets dat in een app kan. " +
            "Klik je op een video, chat of profiel, doe dat gewoon en meld het kort. Berichten typen en versturen in een app doe je pas na bevestiging van de gebruiker. " +
            "\n\nVERSTAAN: De spraakherkenning maakt fouten, vooral bij namen en vreemde woorden. Je krijgt soms alternatieve versies van wat er gehoord is. " +
            "Kies wat het meest logisch is op basis van klank en context (Sophy, Sofie en Sophie zijn mogelijk dezelfde naam). Is het echt onduidelijk, " +
            "vraag dan kort 'Bedoel je ...?'. " +
            "\n\nLEREN: Je wordt steeds beter. Gebruik remember om blijvend te onthouden: voorkeuren van de gebruiker, hoe namen worden bedoeld " +
            "(bijvoorbeeld 'Sophy = contact Sofie'), correcties wanneer de gebruiker zegt dat iets fout was, en wat in een app werkte " +
            "(bijvoorbeeld 'TikTok: zoekicoon heet Zoeken'). Doe dat stil, zonder erover te praten. Zeg na een afgeronde taak niets extra's en stel geen vraag, tenzij je echt iets nodig hebt. " +
            "\n\nWacht je op een antwoord van de gebruiker, eindig dan met een vraag (met een vraagteken). " +
            "Het is nu " + ZonedDateTime.now().toString() + "." + Memory.asPrompt(ctx)

    private fun post(key: String, body: String): Pair<Int, String> {
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 90000
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

    private fun toolList(): JSONArray {
        val list = JSONArray(Tools.schema().toString())
        if (webSearch) {
            list.put(
                JSONObject()
                    .put("type", "web_search_20250305")
                    .put("name", "web_search")
                    .put("max_uses", 3)
            )
        }
        return list
    }

    private fun remember(userText: String, answer: String) {
        history.put(JSONObject().put("role", "user").put("content", userText))
        history.put(JSONObject().put("role", "assistant").put("content", answer))
        while (history.length() > 30) {
            history.remove(0)
            history.remove(0)
        }
    }

    @Synchronized
    fun ask(ctx: Context, userText: String, alternatives: List<String> = emptyList()): String {
        val prefs = Prefs(ctx)
        if (prefs.claudeKey.isBlank()) return "Er is nog geen Claude API sleutel ingesteld."

        // Werkkopie van het gesprek voor alleen deze beurt
        val msgs = JSONArray(history.toString())
        val heard = if (alternatives.isEmpty()) userText else
            userText + "\n(Spraakherkenning twijfelde, andere mogelijke versies: " + alternatives.joinToString(" | ") + ")"
        msgs.put(JSONObject().put("role", "user").put("content", heard))
        val system = systemPrompt(ctx)

        try {
            var attempts = 0
            var round = 0
            while (round < 16) {
                val body = JSONObject()
                    .put("model", prefs.model)
                    .put("max_tokens", 2048)
                    .put("system", system)
                    .put("tools", toolList())
                    .put("messages", msgs)

                val result = post(prefs.claudeKey, body.toString())
                val code = result.first
                val raw = result.second
                if (code !in 200..299) {
                    val msg = errorMessage(raw)
                    if (code == 400 && webSearch && msg.contains("web_search", ignoreCase = true)) {
                        webSearch = false
                        JLog.add("Webzoeken staat niet aan voor deze sleutel, ik ga zonder: $msg".take(120))
                        continue
                    }
                    if ((code == 429 || code == 500 || code == 529) && attempts < 2) {
                        attempts++
                        Thread.sleep(1500)
                        continue
                    }
                    JLog.add("AI-fout $code: $msg")
                    return when (code) {
                        401 -> "Mijn API sleutel wordt niet geaccepteerd. Controleer hem in de app."
                        429 -> "Ik krijg te veel verzoeken tegelijk. Probeer het zo nog eens."
                        else -> "Sorry, daar ging iets mis aan mijn kant. Probeer het nog eens."
                    }
                }

                round++
                val json = JSONObject(raw)
                val content = json.getJSONArray("content")
                val stop = json.optString("stop_reason")

                if (stop == "pause_turn") {
                    msgs.put(JSONObject().put("role", "assistant").put("content", content))
                    continue
                }

                if (stop != "tool_use") {
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.getString("type") == "text") sb.append(block.getString("text")).append(' ')
                    }
                    val answer = sb.toString().trim().ifEmpty { "Klaar." }
                    remember(userText, answer)
                    return answer
                }

                msgs.put(JSONObject().put("role", "assistant").put("content", content))
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
                msgs.put(JSONObject().put("role", "user").put("content", results))
            }
        } catch (e: Exception) {
            JLog.add("AI onbereikbaar: " + e.message)
            return "Ik kan de AI nu niet bereiken. Controleer je internet."
        }
        return "Klaar."
    }
}
