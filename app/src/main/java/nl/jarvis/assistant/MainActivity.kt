package nl.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private var logView: TextView? = null

    private val perms = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(48, 96, 48, 48)

        fun label(t: String) {
            val tv = TextView(this)
            tv.text = t
            tv.setPadding(0, 32, 0, 8)
            col.addView(tv)
        }

        fun button(t: String, onClick: () -> Unit) {
            val b = Button(this)
            b.text = t
            b.setOnClickListener { onClick() }
            col.addView(b)
        }

        val title = TextView(this)
        title.text = "Jarvis"
        title.textSize = 28f
        col.addView(title)

        label("Claude API sleutel (console.anthropic.com)")
        val claude = EditText(this)
        claude.setText(prefs.claudeKey)
        claude.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        col.addView(claude)

        label("Model (laat staan zoals het is)")
        val model = EditText(this)
        model.setText(prefs.model)
        col.addView(model)

        label("Stap 1: toestemmingen")
        button("Geef toestemmingen") { requestPermissions(perms, 1) }
        button("Toestaan: over andere apps heen tonen") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("Aan: Jarvis-toegankelijkheid (voor Verzenden-knop)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("Batterij: niet beperken") {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        }

        val muteBtn = Button(this)
        fun muteText() {
            muteBtn.text = if (prefs.muteBeeps) "Piepjes dempen: AAN (tik om uit te zetten)" else "Piepjes dempen: UIT (tik om aan te zetten)"
        }
        muteText()
        muteBtn.setOnClickListener {
            prefs.muteBeeps = !prefs.muteBeeps
            muteText()
            Toast.makeText(this, "Stop en start Jarvis opnieuw om dit toe te passen.", Toast.LENGTH_LONG).show()
        }
        col.addView(muteBtn)

        val langBtn = Button(this)
        fun langText() { langBtn.text = "Taal: " + (if (prefs.language == "en") "Engels (tik voor Nederlands)" else "Nederlands (tik voor Engels)") }
        langText()
        langBtn.setOnClickListener {
            prefs.language = if (prefs.language == "en") "nl" else "en"
            langText()
            Toast.makeText(this, "Stop en start Jarvis opnieuw om dit toe te passen.", Toast.LENGTH_LONG).show()
        }
        col.addView(langBtn)

        button("Andere stem proberen (kies een mannelijke)") {
            var eng: TextToSpeech? = null
            eng = TextToSpeech(applicationContext) { st ->
                runOnUiThread {
                    val e = eng
                    if (st != TextToSpeech.SUCCESS || e == null) {
                        Toast.makeText(this, "Stem starten mislukt.", Toast.LENGTH_LONG).show()
                    } else {
                        val nl = e.voices?.filter { it.locale.language == "nl" }?.sortedBy { it.name } ?: emptyList()
                        if (nl.isEmpty()) {
                            Toast.makeText(this, "Geen Nederlandse stemmen gevonden. Gebruik Stem-instellingen.", Toast.LENGTH_LONG).show()
                        } else {
                            val idx = (nl.indexOfFirst { it.name == prefs.voiceName } + 1) % nl.size
                            val v = nl[idx]
                            e.voice = v
                            e.setPitch(0.82f)
                            e.setSpeechRate(0.96f)
                            prefs.voiceName = v.name
                            Toast.makeText(this, "Stem " + (idx + 1) + " van " + nl.size + ": " + v.name + ". Tik nog eens voor de volgende. Stop en start Jarvis om te gebruiken.", Toast.LENGTH_LONG).show()
                            e.speak("Goedenavond. Ik ben Jarvis, tot uw dienst.", TextToSpeech.QUEUE_FLUSH, null, "voice")
                        }
                    }
                }
            }
        }
        button("Wat heeft Jarvis geleerd?") {
            val n = Memory.notes(this)
            AlertDialogShow.show(this, if (n.isEmpty()) "Nog niets geleerd." else n.joinToString("\n- ", "- "))
        }

        label("Stap 2: testen")
        val testInput = EditText(this)
        testInput.hint = "Typ een opdracht, bijvoorbeeld: wat is 12 keer 12"
        col.addView(testInput)
        val result = TextView(this)
        result.setPadding(0, 16, 0, 16)

        button("Test Claude (zonder stem)") {
            prefs.claudeKey = claude.text.toString()
            prefs.model = model.text.toString()
            val question = testInput.text.toString()
            result.text = "Bezig…"
            Thread {
                val reply = Brain.ask(applicationContext, question)
                runOnUiThread { result.text = reply }
            }.start()
        }
        col.addView(result)

        button("Test Jarvis-scherm (ring onderaan)") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Zet eerst 'over andere apps heen tonen' aan (stap 1).", Toast.LENGTH_LONG).show()
            } else {
                val h = Handler(Looper.getMainLooper())
                JarvisUi.show(this, OrbState.LISTENING, "Ik luister…")
                h.postDelayed({ JarvisUi.show(this, OrbState.THINKING, "Even denken…") }, 2000)
                h.postDelayed({ JarvisUi.show(this, OrbState.SPEAKING, "Hallo, ik ben Jarvis.") }, 4000)
                h.postDelayed({ JarvisUi.hide(0) }, 6500)
                Toast.makeText(this, "Ga naar je beginscherm om de ring te zien.", Toast.LENGTH_LONG).show()
            }
        }

        button("Test stem (hoor je me?)") {
            var engine: TextToSpeech? = null
            engine = TextToSpeech(applicationContext) { status ->
                runOnUiThread {
                    if (status != TextToSpeech.SUCCESS) {
                        Toast.makeText(this, "Stem starten mislukt, code $status", Toast.LENGTH_LONG).show()
                    } else {
                        val lang = engine?.setLanguage(Locale("nl", "NL")) ?: TextToSpeech.ERROR
                        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Toast.makeText(
                                this,
                                "Nederlandse stem ontbreekt. Tik op 'Stem-instellingen' en installeer Nederlands.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(this, "Stem werkt. Zet je mediavolume omhoog als je niets hoort.", Toast.LENGTH_LONG).show()
                            engine?.speak("Hallo, ik ben Jarvis. Kun je me horen?", TextToSpeech.QUEUE_FLUSH, null, "test")
                        }
                    }
                }
            }
        }
        button("Stem-instellingen (Nederlands installeren)") {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        label("Stap 3: starten")
        button("Opslaan en Jarvis starten") {
            prefs.claudeKey = claude.text.toString()
            prefs.model = model.text.toString()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Geef eerst de toestemmingen (stap 1).", Toast.LENGTH_LONG).show()
            } else {
                startForegroundService(Intent(this, JarvisService::class.java))
                Toast.makeText(this, "Jarvis luistert. Zeg \"Jarvis\".", Toast.LENGTH_LONG).show()
            }
        }
        button("Jarvis stoppen") {
            stopService(Intent(this, JarvisService::class.java))
        }

        label("Logboek (nieuwste bovenaan): wat Jarvis hoort en doet")
        val log = TextView(this)
        log.textSize = 12f
        log.setTextIsSelectable(true)
        log.text = JLog.text()
        logView = log
        col.addView(log)
        button("Ververs logboek") { log.text = JLog.text() }

        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        logView?.text = JLog.text()
    }
}
