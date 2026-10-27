package nl.jarvis.assistant

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView

/** Kleine doorzichtige "Jarvis"-laag die ook op het vergrendelscherm verschijnt. */
class AssistActivity : Activity() {
    private lateinit var label: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val closer = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        label = TextView(this)
        label.textSize = 20f
        label.setTextColor(Color.WHITE)
        label.setTypeface(label.typeface, Typeface.BOLD)
        label.gravity = Gravity.CENTER
        label.setPadding(48, 48, 48, 48)
        label.setBackgroundColor(0xCC0A1A2F.toInt())
        setContentView(label)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.TOP)
        instance = this
        update(intent.getStringExtra("text") ?: "Ik luister…")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val text = intent.getStringExtra("text")
        if (text != null) update(text)
    }

    private fun update(text: String) {
        label.text = "Jarvis\n\n" + text
        handler.removeCallbacks(closer)
        handler.postDelayed(closer, 7000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(closer)
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: AssistActivity? = null

        fun show(ctx: Context, text: String) {
            val i = Intent(ctx, AssistActivity::class.java)
            i.putExtra("text", text)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try {
                ctx.startActivity(i)
            } catch (e: Exception) {
                // Geen overlay-toestemming of app op de achtergrond: stilletjes doorgaan.
            }
        }

        /** Vraagt de gebruiker te ontgrendelen en wacht maximaal 25 seconden. Niet op de hoofdthread aanroepen. */
        fun awaitUnlock(ctx: Context): Boolean {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isKeyguardLocked) return true
            show(ctx, "Ontgrendel je telefoon zodat ik dit kan doen")
            Handler(Looper.getMainLooper()).postDelayed({
                val a = instance
                if (a != null) km.requestDismissKeyguard(a, null)
            }, 600)
            for (i in 0 until 50) {
                if (!km.isKeyguardLocked) return true
                Thread.sleep(500)
            }
            return !km.isKeyguardLocked
        }
    }
}
