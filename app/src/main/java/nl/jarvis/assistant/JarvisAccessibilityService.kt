package nl.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentLinkedQueue

/** Eén handeling die Jarvis in een andere app moet doen. */
sealed class Step {
    class Click(val labels: List<String>) : Step()
    class Type(val text: String) : Step()
}

/** Wachtrij met handelingen die de toegankelijkheidsservice één voor één uitvoert. */
object JarvisBridge {
    val queue = ConcurrentLinkedQueue<Step>()

    @Volatile
    var deadline: Long = 0L

    fun expectSteps(steps: List<Step>, withinMs: Long = 25_000L) {
        queue.clear()
        queue.addAll(steps)
        deadline = System.currentTimeMillis() + withinMs
    }
}

class JarvisAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /** Leest alle zichtbare tekst van het scherm dat nu open staat. */
    fun readScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val seen = LinkedHashSet<String>()
        collect(root, seen, 0)
        return seen.joinToString("\n").take(2500)
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableSet<String>, depth: Int) {
        if (node == null || depth > 40 || out.size > 200) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        if (!t.isNullOrEmpty()) out.add(t)
        else if (!d.isNullOrEmpty()) out.add(d)
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    class Item(val label: String, val rect: Rect, val clickable: Boolean, val editable: Boolean)

    @Volatile
    private var items: List<Item> = emptyList()

    private fun scan(node: AccessibilityNodeInfo?, out: MutableList<Item>, depth: Int, clickableAbove: Boolean) {
        if (node == null || depth > 45 || out.size >= 70) return
        if (!node.isVisibleToUser) return
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        val label = if (t.isNotEmpty()) t else d
        val click = node.isClickable || clickableAbove
        if (label.isNotEmpty() || node.isEditable) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                out.add(Item(label.take(110), r, click, node.isEditable))
            }
        }
        for (i in 0 until node.childCount) scan(node.getChild(i), out, depth + 1, click)
    }

    /** Geeft een genummerde lijst van wat er op het scherm staat. */
    fun describeScreen(): String {
        val root = rootInActiveWindow ?: return "Ik kan het scherm niet lezen."
        val list = ArrayList<Item>()
        scan(root, list, 0, false)
        val seen = HashSet<String>()
        val unique = list.filter { seen.add(it.label + it.rect.toShortString()) }
        items = unique
        if (unique.isEmpty()) return "Ik zie geen tekst op het scherm."
        val sb = StringBuilder("Scherm (nummer: tekst, K = klikbaar, T = tekstveld):\n")
        unique.forEachIndexed { i, it ->
            sb.append(i + 1).append(": ").append(it.label)
            if (it.editable) sb.append(" [T]") else if (it.clickable) sb.append(" [K]")
            sb.append("\n")
        }
        return sb.toString().take(3500)
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 70)).build()
        return dispatchGesture(g, null, null)
    }

    fun clickItem(n: Int): String {
        val it = items.getOrNull(n - 1) ?: return "Dat nummer bestaat niet. Lees eerst het scherm."
        return if (tapAt(it.rect.exactCenterX(), it.rect.exactCenterY())) "Geklikt op: " + it.label else "Klikken lukte niet."
    }

    fun clickText(text: String): String {
        val root = rootInActiveWindow ?: return "Ik kan het scherm niet lezen."
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && node.isVisibleToUser) {
                return if (tapAt(r.exactCenterX(), r.exactCenterY())) "Geklikt op: $text" else "Klikken lukte niet."
            }
        }
        return "Ik vind '$text' niet op het scherm."
    }

    fun typeInto(text: String, submit: Boolean): String {
        val root = rootInActiveWindow ?: return "Ik kan het scherm niet lezen."
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
            ?: return "Er is geen tekstveld. Tik eerst op het zoek- of typveld."
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "Typen lukte niet."
        if (submit) {
            Thread.sleep(500)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
        return "Getypt: $text" + if (submit) " en bevestigd." else "."
    }

    fun scroll(down: Boolean): String {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val p = Path()
        if (down) {
            p.moveTo(x, m.heightPixels * 0.75f); p.lineTo(x, m.heightPixels * 0.30f)
        } else {
            p.moveTo(x, m.heightPixels * 0.30f); p.lineTo(x, m.heightPixels * 0.75f)
        }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 320)).build()
        return if (dispatchGesture(g, null, null)) "Gescrold." else "Scrollen lukte niet."
    }

    fun press(button: String): String {
        val action = when (button.lowercase()) {
            "back", "terug" -> GLOBAL_ACTION_BACK
            "home", "thuis" -> GLOBAL_ACTION_HOME
            "recents", "recent" -> GLOBAL_ACTION_RECENTS
            else -> return "Onbekende knop."
        }
        return if (performGlobalAction(action)) "Gedrukt: $button" else "Drukken lukte niet."
    }

    private val tick = object : Runnable {
        override fun run() {
            process()
            if (JarvisBridge.queue.isNotEmpty() && System.currentTimeMillis() < JarvisBridge.deadline) {
                handler.postDelayed(this, 700)
            } else {
                JarvisBridge.queue.clear()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (JarvisBridge.queue.isEmpty()) return
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 400)
    }

    private fun process() {
        val step = JarvisBridge.queue.peek() ?: return
        val root = rootInActiveWindow ?: return
        val done = when (step) {
            is Step.Click -> clickAny(root, step.labels)
            is Step.Type -> typeText(root, step.text)
        }
        if (done) JarvisBridge.queue.poll()
    }

    private fun clickAny(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            for (node in nodes) {
                var n: AccessibilityNodeInfo? = node
                while (n != null && !n.isClickable) n = n.parent
                if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun typeText(root: AccessibilityNodeInfo, text: String): Boolean {
        val field = findEditable(root) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
    }
}
