package nl.jarvis.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.sin

enum class OrbState { LISTENING, THINKING, SPEAKING }

/** Draaiende HUD-ring met gloeiende kern. Eigen ontwerp, getekend met Canvas. */
class OrbView(context: Context) : View(context) {

    var state: OrbState = OrbState.LISTENING
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f * 0.92f
        val t = SystemClock.uptimeMillis() / 1000f

        val c: Int
        val speed: Float
        val pulseAmount: Float
        val pulseRate: Float
        when (state) {
            OrbState.LISTENING -> { c = 0xFF00E5FF.toInt(); speed = 1f; pulseAmount = 0.08f; pulseRate = 4f }
            OrbState.THINKING -> { c = 0xFFFFD54F.toInt(); speed = 2.6f; pulseAmount = 0.05f; pulseRate = 8f }
            OrbState.SPEAKING -> { c = 0xFF69F0AE.toInt(); speed = 1.4f; pulseAmount = 0.2f; pulseRate = 10f }
        }
        val pulse = 1f + pulseAmount * sin(t * pulseRate)

        // Zachte gloed
        paint.style = Paint.Style.FILL
        paint.pathEffect = null
        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(c, 110), withAlpha(c, 0)),
            floatArrayOf(0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // Buitenste ring met streepjes, draait rechtsom
        paint.style = Paint.Style.STROKE
        paint.color = c
        paint.strokeWidth = r * 0.05f
        paint.pathEffect = DashPathEffect(floatArrayOf(r * 0.13f, r * 0.07f), 0f)
        canvas.save()
        canvas.rotate(t * 40f * speed, cx, cy)
        canvas.drawCircle(cx, cy, r * 0.92f, paint)
        canvas.restore()

        // Middelste ring met drie bogen, draait linksom
        paint.pathEffect = null
        paint.strokeWidth = r * 0.07f
        paint.strokeCap = Paint.Cap.ROUND
        oval.set(cx - r * 0.74f, cy - r * 0.74f, cx + r * 0.74f, cy + r * 0.74f)
        canvas.save()
        canvas.rotate(-t * 75f * speed, cx, cy)
        for (i in 0 until 3) {
            canvas.drawArc(oval, i * 120f, 70f, false, paint)
        }
        canvas.restore()

        // Dunne binnenste ring
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = withAlpha(c, 170)
        paint.strokeWidth = r * 0.02f
        canvas.drawCircle(cx, cy, r * 0.58f, paint)

        // Kern
        val cr = r * 0.34f * pulse
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, cr,
            intArrayOf(Color.WHITE, c, withAlpha(c, 40)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, cr, paint)
        paint.shader = null

        postInvalidateOnAnimation()
    }
}

/**
 * Zwevende Jarvis-laag onderaan het scherm, over elke app heen (zoals een assistent).
 * Op het vergrendelscherm, of zonder "over andere apps heen tonen", valt hij terug op AssistActivity.
 */
object JarvisUi {
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var orb: OrbView? = null
    private var caption: TextView? = null
    private var density = 1f
    private val hider = Runnable { hideOnMain() }

    fun show(ctx: Context, state: OrbState, text: String) {
        val app = ctx.applicationContext
        main.post { showOnMain(app, state, text) }
    }

    fun hide(delayMs: Long) {
        main.removeCallbacks(hider)
        main.postDelayed(hider, delayMs)
    }

    private fun showOnMain(ctx: Context, state: OrbState, text: String) {
        main.removeCallbacks(hider)
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked || !Settings.canDrawOverlays(ctx)) {
            removeNow()
            AssistActivity.show(ctx, text)
            return
        }
        if (root == null) {
            if (!create(ctx)) {
                AssistActivity.show(ctx, text)
                return
            }
        } else {
            root?.animate()?.cancel()
            root?.translationY = 0f
            root?.alpha = 1f
        }
        orb?.state = state
        caption?.text = text
        main.postDelayed(hider, 40_000L)
    }

    private fun create(ctx: Context): Boolean {
        density = ctx.resources.displayMetrics.density
        val d = density

        val layout = LinearLayout(ctx)
        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER_HORIZONTAL
        layout.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xF2061428.toInt(), 0xCC061428.toInt(), 0x00061428)
        )
        layout.setPadding((24 * d).toInt(), (40 * d).toInt(), (24 * d).toInt(), (16 * d).toInt())
        layout.setOnClickListener {
            ctx.startService(Intent(ctx, JarvisService::class.java).setAction(JarvisService.ACTION_INTERRUPT))
        }

        val tv = TextView(ctx)
        tv.setTextColor(Color.WHITE)
        tv.textSize = 18f
        tv.gravity = Gravity.CENTER
        tv.maxLines = 4
        tv.setPadding(0, 0, 0, (8 * d).toInt())
        layout.addView(
            tv,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val ov = OrbView(ctx)
        val size = (170 * d).toInt()
        val olp = LinearLayout.LayoutParams(size, size)
        olp.gravity = Gravity.CENTER_HORIZONTAL
        layout.addView(ov, olp)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM

        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            manager.addView(layout, lp)
        } catch (e: Exception) {
            JLog.add("Zwevend scherm mislukt: " + e.message)
            return false
        }
        wm = manager
        root = layout
        orb = ov
        caption = tv

        layout.translationY = 300f * d
        layout.alpha = 0f
        layout.animate().translationY(0f).alpha(1f).setDuration(250).start()
        return true
    }

    private fun hideOnMain() {
        main.removeCallbacks(hider)
        val v = root ?: return
        v.animate().translationY(300f * density).alpha(0f).setDuration(220).withEndAction {
            if (root === v) removeNow()
        }.start()
    }

    private fun removeNow() {
        val v = root
        if (v != null) {
            try {
                wm?.removeView(v)
            } catch (e: Exception) {
                // al weg
            }
        }
        root = null
        orb = null
        caption = null
    }
}
