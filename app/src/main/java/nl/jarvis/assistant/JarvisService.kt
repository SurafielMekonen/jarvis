package nl.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
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

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    private var busy = false
    private var stopped = false
    private var woke = false

    private val wakeWords = listOf(
        "jarvis", "jarvies", "harvis", "jervis", "jarvist", "tarvis", "jarvus",
        "charvis", "jarvas", "jarfis", "garvis", "jarves"
    )

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
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        var r = engine.setLanguage(Locale("nl", "NL"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            JLog.add("Nederlandse stem ontbreekt, ik probeer de standaardtaal van je telefoon")
            r = engine.setLanguage(Locale.getDefault())
        }
        ttsReady = !(r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
        JLog.add(if (ttsReady) "Stem klaar" else "Geen bruikbare stem gevonden")

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                main.post { finishTurn(1200) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post {
                    JLog.add("Fout tijdens praten")
                    finishTurn(1200)
                }
            }
        })
    }

    /** Zoekt het wake word en geeft de tekst erna terug (kan leeg zijn). Null als het woord ontbreekt. */
    private fun afterWakeWord(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        for (w in wakeWords) {
            val i = lower.indexOf(w)
            if (i >= 0) return text.substring(i + w.length).trim(' ', ',', '.', '!', '?')
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

    private fun listen(wakeMode: Boolean) {
        if (stopped) return
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r

        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                if (!wakeMode || woke) return
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
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                if (wakeMode) {
                    if (list.isNotEmpty()) JLog.add("Gehoord: " + list[0])
                    var command: String? = null
                    for (candidate in list) {
                        command = afterWakeWord(candidate)
                        if (command != null) break
                    }
                    if (command == null) {
                        if (woke) {
                            woke = false
                            JarvisUi.hide(0)
                        }
                        restartWake(150)
                    } else if (command.isBlank()) {
                        busy = true
                        woke = true
                        JarvisUi.show(this@JarvisService, OrbState.LISTENING, "Ik luister…")
                        listen(false)
                    } else {
                        process(command)
                    }
                } else {
                    val text = list.firstOrNull()
                    if (text.isNullOrBlank()) {
                        JLog.add("Geen opdracht gehoord")
                        JarvisUi.show(this@JarvisService, OrbState.LISTENING, "Ik hoorde niets")
                        finishTurn(1500)
                    } else {
                        process(text)
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
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (!quiet) JLog.add("Spraakfout: " + errorName(error))
                if (wakeMode) {
                    if (woke) {
                        woke = false
                        JarvisUi.hide(0)
                    }
                    restartWake(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000L else 250L)
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

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "nl-NL")
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        if (!wakeMode) {
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            JLog.add("Luisteren starten mislukt: " + e.message)
            if (!wakeMode) busy = false
            restartWake(1000)
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

    private fun process(text: String) {
        busy = true
        JLog.add("Opdracht: $text")
        JarvisUi.show(this, OrbState.THINKING, text)
        Thread {
            val reply = try {
                Brain.ask(applicationContext, text)
            } catch (e: Exception) {
                "Er ging iets mis: " + e.message
            }
            main.post { speak(reply) }
        }.start()
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
        main.removeCallbacks(speakWatchdog)
        main.postDelayed(speakWatchdog, 30_000L)
        val res = engine.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "reply")
        if (res != TextToSpeech.SUCCESS) {
            JLog.add("Praten mislukt")
            finishTurn(3500)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        JarvisUi.hide(0)
        recognizer?.destroy()
        tts?.shutdown()
        JLog.add("Jarvis gestopt")
        super.onDestroy()
    }
}
