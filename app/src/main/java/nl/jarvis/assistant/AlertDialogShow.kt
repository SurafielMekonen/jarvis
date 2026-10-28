package nl.jarvis.assistant

import android.app.AlertDialog
import android.content.Context

object AlertDialogShow {
    fun show(ctx: Context, text: String) {
        AlertDialog.Builder(ctx)
            .setTitle("Jarvis heeft geleerd")
            .setMessage(text)
            .setPositiveButton("Sluiten", null)
            .setNegativeButton("Alles vergeten") { _, _ -> Prefs(ctx).memory = "" }
            .show()
    }
}
