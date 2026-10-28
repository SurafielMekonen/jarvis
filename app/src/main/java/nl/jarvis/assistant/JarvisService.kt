package nl.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Draait continu op de achtergrond (ook bij vergrendeld scherm).
 * Luistert met de ingebouwde Android-spraakherkenning naar het woord "Jarvis".
 * Zeg je "Jarvis, stuur Anna een sms ..." in één zin, dan wordt het direct uitgevoerd.
 * Zeg je alleen "Jarvis", dan luistert hij daarna naar je opdracht.
 */
class JarvisService : Service() {

    companion object {
        const val ACTION_INTERRUPT = "nl.jarvis.assistant.INTERRUPT"
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    private var busy = false
    private var stopped = false
    private var woke = false
    private var muted = false
    private var followUp = false

    private val acks = listOf("Ja hoor.", "Oké.", "Momentje.", "Mm-hm, even kijken.", "Ja, komt eraan.", "Tuurlijk.", "Goed.")

    private val savedVolumes = HashMap<Int, Int>()

    /** Dempt de piepjes van de spraakherkenning terwijl Jarvis luistert, en zet alles terug als hij praat. */
    private fun setMuted(on: Boolean) {
        if (on == muted) return
        if (on && !Prefs(this).muteBeeps) return
        muted = on
        val am = getSystemService(AudioManager::class.java)
        val streams = intArrayOf(
            AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC, 10
        )
        for (stream in streams) {
            try {
                if (on) {
                    savedVolumes[stream] = am.getStreamVolume(stream)
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                    am.setStreamVolume(stream, 0, 0)
                } else {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                    val v = savedVolumes[stream]
                    if (v != null && v > 0) am.setStreamVolume(stream, v, 0)
                }
            } catch (e: Exception) {
                // sommige telefoons staan dit niet toe
            }
        }
    }

    /** Eerst het geluid terugzetten en dan pas praten, anders blijft de stem stil. */
    private fun whenAudible(block: () -> Unit) {
        if (muted) {
            setMuted(false)
            main.postDelayed({ if (!stopped) block() }, 350)
        } else {
            block()
        }
    }

    private fun startFollowUp() {
        followUp = false
        main.removeCallbacks(speakWatchdog)
        woke = true
        JarvisUi.show(this, OrbState.LISTENING, "Ik luister…")
        listen(false)
    }

    private val wakeWords = listOf("jarvis", "jarvies", "jervis", "harvis", "jarvist", "jarvus")

    private val restartRunnable = Runnable {
        if (!stopped && !busy) listen(true)
    }

    private val speakWatchdog = Runnable {
        if (busy) {
            JLog.add("Praten duurde te lang, ik ga weer luisteren")
            finishTurn(0)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis luistert")
            .setContentText("Zeg \"Jarvis\" om te beginnen")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }
        JLog.add("Jarvis gestart")

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                setupVoice()
            } else {
                JLog.add("Stem starten mislukt, code $status")
            }
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            JLog.add("Spraakherkenning niet beschikbaar op dit toestel")
            JarvisUi.show(this, OrbState.LISTENING, "Spraakherkenning is niet beschikbaar op dit toestel.")
            JarvisUi.hide(4000)
        } else {
            main.post { listen(true) }
        }
    }

    private fun setupVoice() {
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val wantLocale = if (Prefs(this).language == "en") Locale("en", "US") else Locale("nl", "NL")
        var r = engine.setLanguage(wantLocale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            JLog.add("Stem voor deze taal ontbreekt, ik probeer de standaardtaal van je telefoon")
            r = engine.setLanguage(Locale.getDefault())
        }
        try {
            val wanted = Prefs(this).voiceName
            val lang = Prefs(this).language
            val nl = engine.voices?.filter { it.locale.language == lang } ?: emptyList()
            val pick = (if (wanted.isNotEmpty()) nl.firstOrNull { it.name == wanted } else null)
                ?: nl.firstOrNull { Regex("(?<!fe)male", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            if (pick != null) {
                engine.voice = pick
                JLog.add("Stem: " + pick.name)
            }
        } catch (e: Exception) {
            JLog.add("Stem kiezen mislukt")
        }
        engine.setPitch(0.82f)
        engine.setSpeechRate(0.96f)
        ttsReady = !(r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
        JLog.add(if (ttsReady) "Stem klaar" else "Geen bruikbare stem gevonden")

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                JLog.add("Spreekt: " + utteranceId)
            }

            override fun onDone(utteranceId: String?) {
                main.post {
                    when (utteranceId) {
                        "reply" -> if (followUp) startFollowUp() else finishTurn(250)
                        "listen" -> {
                            main.removeCallbacks(speakWatchdog)
                            listen(false)
                        }
                        else -> {}
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post {
                    JLog.add("Fout tijdens praten")
                    when (utteranceId) {
                        "reply" -> finishTurn(1200)
                        "listen" -> listen(false)
                        else -> {}
                    }
                }
            }
        })
    }

    /** Zoekt het wake word en geeft de tekst erna terug (kan leeg zijn). Null als het woord ontbreekt. */
    private fun afterWakeWord(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        for (w in wakeWords) {
            val i = lower.indexOf(w)
            if (i < 0) continue
            // Het woord moet vooraan staan (hooguit twee woorden ervoor, zoals "hé" of "hallo")
            val before = lower.substring(0, i).trim().split(" ").filter { it.isNotEmpty() }
            if (before.size > 2) continue
            return text.substring(i + w.length).trim(' ', ',', '.', '!', '?')
        }
        return null
    }

    private fun errorName(code: Int): String = when (code) {
        1 -> "netwerk-timeout"
        2 -> "netwerkfout"
        3 -> "audiofout"
        4 -> "serverfout"
        5 -> "clientfout"
        6 -> "geen spraak"
        7 -> "niets herkend"
        8 -> "herkenner bezig"
        9 -> "geen toestemming"
        10 -> "te veel verzoeken"
        11 -> "server verbroken"
        12 -> "taal niet ondersteund"
        13 -> "taal niet beschikbaar"
        else -> "onbekend"
    }

    private var wakeModeNow = true
    private var useOffline = true
    private var errorStreak = 0

    private val startRunnable = Runnable { startNow() }

    private fun destroyRecognizer() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // al weg
        }
        recognizer = null
    }

    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                if (!wakeModeNow || woke) return
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                for (candidate in list) {
                    if (afterWakeWord(candidate) != null) {
                        woke = true
                        JarvisUi.show(this@JarvisService, OrbState.LISTENING, "Ik luister…")
                        break
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                errorStreak = 0
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                if (wakeModeNow) {
                    if (list.isNotEmpty()) JLog.add("Gehoord: " + list[0])
                    var command: String? = null
                    val alts = ArrayList<String>()
                    for (candidate in list) {
                        val c = afterWakeWord(candidate)
                        if (c == null) continue
                        if (command == null) command = c else if (c.isNotBlank() && c != command && c !in alts) alts.add(c)
                    }
                    if (command != null) JLog.add("Wakker door: " + list.firstOrNull())
                    if (command == null) {
                        if (woke) {
                            woke = false
                            JarvisUi.hide(0)
                        }
                        restartWake(600)
                    } else if (command.isBlank()) {
                        busy = true
                        woke = true
                        JarvisUi.show(this@JarvisService, OrbState.LISTENING, "Ja?")
                        if (ttsReady) {
                            main.removeCallbacks(speakWatchdog)
                            main.postDelayed(speakWatchdog, 12_000L)
                            whenAudible { tts?.speak("Ja?", TextToSpeech.QUEUE_FLUSH, null, "listen") }
                        } else {
                            listen(false)
                        }
                    } else {
                        process(command, alts)
                    }
                } else {
                    val text = list.firstOrNull()
                    if (text.isNullOrBlank()) {
                        JLog.add("Geen opdracht gehoord")
                        JarvisUi.show(this@JarvisService, OrbState.LISTENING, "Ik hoorde niets")
                        finishTurn(1500)
                    } else {
                        process(text, list.drop(1).distinct())
                    }
                }
            }

            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    JLog.add("Geen microfoontoestemming")
                    JarvisUi.show(this@JarvisService, OrbState.LISTENING, "Geen microfoontoestemming voor Jarvis.")
                    JarvisUi.hide(4000)
                    return
                }
                if ((error == 12 || error == 13) && useOffline) {
                    useOffline = false
                    JLog.add("Offline herkenning niet beschikbaar, ik gebruik de online versie")
                }
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (!quiet) JLog.add("Spraakfout: " + errorName(error))
                // Bij echte fouten (o.a. 'server verbroken') de herkenner vers opbouwen, met oplopende wachttijd
                var delay = 600L
                if (!quiet) {
                    errorStreak++
                    destroyRecognizer()
                    delay = minOf(1500L * errorStreak, 8000L)
                }
                if (wakeModeNow) {
                    if (woke) {
                        woke = false
                        JarvisUi.hide(0)
                    }
                    restartWake(delay)
                } else {
                    if (quiet) {
                        JarvisUi.show(this@JarvisService, OrbState.LISTENING, "Ik hoorde niets")
                        finishTurn(1500)
                    } else {
                        finishTurn(0)
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        return r
    }

    private fun listen(wakeMode: Boolean) {
        if (stopped) return
        wakeModeNow = wakeMode
        main.removeCallbacks(startRunnable)
        // Even wachten: een direct nieuwe sessie na de vorige veroorzaakt 'server verbroken'
        main.postDelayed(startRunnable, 500)
    }

    private fun startNow() {
        if (stopped) return
        val wakeMode = wakeModeNow
        val r = ensureRecognizer()
        try {
            r.cancel()
        } catch (e: Exception) {
            // negeren
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (Prefs(this).language == "en") "en-US" else "nl-NL")
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        if (!wakeMode) {
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        if (wakeMode && useOffline) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        setMuted(true)
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            JLog.add("Luisteren starten mislukt: " + e.message)
            destroyRecognizer()
            if (!wakeMode) busy = false
            restartWake(1500)
        }
    }

    private fun restartWake(delayMs: Long) {
        main.removeCallbacks(restartRunnable)
        main.postDelayed(restartRunnable, delayMs)
    }

    private fun finishTurn(hideDelayMs: Long) {
        main.removeCallbacks(speakWatchdog)
        busy = false
        woke = false
        JarvisUi.hide(hideDelayMs)
        restartWake(400)
    }

    private fun process(text: String, alts: List<String> = emptyList()) {
        busy = true
        JLog.add("Opdracht: $text")
        JarvisUi.show(this, OrbState.THINKING, text)
        main.removeCallbacks(speakWatchdog)
        main.postDelayed(speakWatchdog, 90_000L)
        if (ttsReady) whenAudible { tts?.speak(acks.random(), TextToSpeech.QUEUE_FLUSH, null, "ack") }
        Thread {
            val reply = try {
                Brain.ask(applicationContext, text, alts)
            } catch (e: Exception) {
                "Er ging iets mis: " + e.message
            }
            main.post { speak(reply) }
        }.start()
    }

    /** Onderbreekt Jarvis meteen: stopt met praten en gaat weer luisteren. Gekoppeld aan een tik op de ring. */
    fun interrupt() {
        if (!busy) return
        JLog.add("Onderbroken door tik op de ring")
        tts?.stop()
        main.removeCallbacks(speakWatchdog)
        followUp = false
        finishTurn(0)
    }

    private fun speak(reply: String) {
        JLog.add("Antwoord: $reply")
        JarvisUi.show(this, OrbState.SPEAKING, reply)
        val engine = tts
        if (engine == null || !ttsReady) {
            JLog.add("Kan niet praten: stem niet gereed")
            finishTurn(3500)
            return
        }
        followUp = reply.trimEnd().endsWith("?")
        main.removeCallbacks(speakWatchdog)
        main.postDelayed(speakWatchdog, minOf(60_000L, 5_000L + reply.length * 110L))
        whenAudible {
            val res = engine.speak(reply, TextToSpeech.QUEUE_ADD, null, "reply")
            if (res != TextToSpeech.SUCCESS) {
                JLog.add("Praten mislukt")
                finishTurn(3500)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_INTERRUPT) interrupt()
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        JarvisUi.hide(0)
        destroyRecognizer()
        setMuted(false)
        tts?.shutdown()
        JLog.add("Jarvis gestopt")
        super.onDestroy()
    }
}
