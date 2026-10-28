package nl.jarvis.assistant

import android.accessibilityservice.AccessibilityService
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
