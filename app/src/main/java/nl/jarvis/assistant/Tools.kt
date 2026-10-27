package nl.jarvis.assistant

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Alle acties die Jarvis voor je kan uitvoeren. */
object Tools {

    private fun prop(type: String, desc: String): JSONObject =
        JSONObject().put("type", type).put("description", desc)

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", desc)
            .put(
                "input_schema",
                JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", JSONArray(required))
            )

    fun schema(): JSONArray {
        val tools = JSONArray()
        tools.put(
            tool(
                "send_sms", "Stuur een sms naar een contactpersoon of telefoonnummer.",
                JSONObject()
                    .put("to", prop("string", "Naam in contacten of telefoonnummer"))
                    .put("message", prop("string", "Tekst")),
                listOf("to", "message")
            )
        )
        tools.put(
            tool(
                "send_whatsapp", "Stuur een WhatsApp-bericht naar een contactpersoon of nummer.",
                JSONObject()
                    .put("to", prop("string", "Naam in contacten of telefoonnummer"))
                    .put("message", prop("string", "Tekst")),
                listOf("to", "message")
            )
        )
        tools.put(
            tool(
                "compose_email", "Maak een e-mail klaar in de e-mailapp en verstuur die.",
                JSONObject()
                    .put("to", prop("string", "E-mailadres"))
                    .put("subject", prop("string", "Onderwerp"))
                    .put("body", prop("string", "Inhoud")),
                listOf("to", "subject", "body")
            )
        )
        tools.put(
            tool(
                "google_search", "Zoek iets op Google in de browser.",
                JSONObject().put("query", prop("string", "Zoekopdracht")),
                listOf("query")
            )
        )
        tools.put(
            tool(
                "add_calendar_event", "Zet een afspraak in de agenda.",
                JSONObject()
                    .put("title", prop("string", "Titel"))
                    .put("start", prop("string", "Starttijd in lokale tijd, formaat 2026-10-16T14:30"))
                    .put("duration_minutes", prop("integer", "Duur in minuten, standaard 60")),
                listOf("title", "start")
            )
        )
        tools.put(
            tool(
                "read_calendar", "Lees de komende afspraken uit de agenda.",
                JSONObject().put("days", prop("integer", "Aantal dagen vooruit, standaard 1")),
                listOf()
            )
        )
        tools.put(
            tool(
                "open_app", "Open een app op de telefoon, bijvoorbeeld Instagram, YouTube of Maps.",
                JSONObject().put("name", prop("string", "Naam van de app")),
                listOf("name")
            )
        )
        tools.put(
            tool(
                "post_social", "Plaats een tekstpost namens de gebruiker. Platforms: x, linkedin, facebook, threads.",
                JSONObject()
                    .put("platform", prop("string", "x, linkedin, facebook of threads"))
                    .put("text", prop("string", "Tekst van de post")),
                listOf("platform", "text")
            )
        )
        tools.put(
            tool(
                "send_social_message",
                "Stuur een privébericht via een social-app. Platforms: instagram, telegram, messenger, snapchat, signal.",
                JSONObject()
                    .put("platform", prop("string", "instagram, telegram, messenger, snapchat of signal"))
                    .put("to", prop("string", "Naam van de persoon zoals die in de app staat"))
                    .put("message", prop("string", "Tekst")),
                listOf("platform", "to", "message")
            )
        )
        tools.put(
            tool(
                "call_contact", "Bel een contactpersoon of nummer.",
                JSONObject().put("to", prop("string", "Naam of nummer")),
                listOf("to")
            )
        )
        tools.put(
            tool(
                "set_timer", "Zet een timer.",
                JSONObject()
                    .put("seconds", prop("integer", "Aantal seconden"))
                    .put("label", prop("string", "Naam")),
                listOf("seconds")
            )
        )
        return tools
    }

    fun run(ctx: Context, name: String, a: JSONObject): String {
        return when (name) {
            "send_sms" -> sendSms(ctx, a.getString("to"), a.getString("message"))
            "send_whatsapp" -> sendWhatsapp(ctx, a.getString("to"), a.getString("message"))
            "compose_email" -> email(ctx, a.getString("to"), a.getString("subject"), a.getString("body"))
            "google_search" -> launch(
                ctx,
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + enc(a.getString("query")))),
                "Zoeken gestart."
            )
            "add_calendar_event" -> addEvent(ctx, a.getString("title"), a.getString("start"), a.optInt("duration_minutes", 60))
            "read_calendar" -> readCalendar(ctx, a.optInt("days", 1))
            "open_app" -> openApp(ctx, a.getString("name"))
            "post_social" -> postSocial(ctx, a.getString("platform"), a.getString("text"))
            "send_social_message" -> sendSocialMessage(ctx, a.getString("platform"), a.getString("to"), a.getString("message"))
            "call_contact" -> call(ctx, a.getString("to"))
            "set_timer" -> {
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                launch(ctx, i, "Timer gezet.", needsUnlock = false)
            }
            else -> "Onbekende tool: $name"
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Start een scherm. Vraagt eerst ontgrendeling als de telefoon op slot zit. */
    private fun launch(ctx: Context, intent: Intent, ok: String, needsUnlock: Boolean = true): String {
        if (needsUnlock && !AssistActivity.awaitUnlock(ctx)) {
            return "De telefoon bleef vergrendeld, actie geannuleerd."
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
            return ok
        } catch (e: Exception) {
            if (intent.`package` != null && intent.action == Intent.ACTION_VIEW) {
                try {
                    intent.setPackage(null)
                    ctx.startActivity(intent)
                    return "$ok (geopend in de browser)"
                } catch (e2: Exception) {
                    return "Dat lukte niet: " + e2.message
                }
            }
            return "Die app is niet geïnstalleerd of kan dit niet openen."
        }
    }

    private fun resolveNumber(ctx: Context, who: String): String? {
        if (who.any { it.isDigit() } && who.none { it.isLetter() }) {
            return who.filter { it.isDigit() || it == '+' }
        }
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$who%"),
            null
        )
        var found: String? = null
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    found = cursor.getString(0).filter { it.isDigit() || it == '+' }
                }
            } finally {
                cursor.close()
            }
        }
        return found
    }

    private fun sendSms(ctx: Context, to: String, msg: String): String {
        val number = resolveNumber(ctx, to) ?: return "Ik vind geen nummer voor $to."
        val sms = ctx.getSystemService(SmsManager::class.java)
        sms.sendMultipartTextMessage(number, null, sms.divideMessage(msg), null, null)
        return "Sms verstuurd naar $to."
    }

    private fun whatsappNumber(raw: String): String {
        var n = raw.filter { it.isDigit() || it == '+' }
        n = n.removePrefix("+")
        if (n.startsWith("00")) n = n.drop(2)
        if (n.startsWith("0")) n = "31" + n.drop(1)
        return n
    }

    private val sendLabels = listOf("Verzenden", "Versturen", "Send")
    private val postLabels = listOf("Posten", "Post", "Plaatsen", "Delen", "Share")

    private fun sendWhatsapp(ctx: Context, to: String, msg: String): String {
        val number = resolveNumber(ctx, to) ?: return "Ik vind geen nummer voor $to."
        JarvisBridge.expectSteps(listOf(Step.Click(sendLabels)))
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + whatsappNumber(number) + "?text=" + enc(msg)))
        i.setPackage("com.whatsapp")
        return launch(ctx, i, "WhatsApp-bericht aan $to wordt verstuurd.")
    }

    private fun email(ctx: Context, to: String, subject: String, body: String): String {
        JarvisBridge.expectSteps(listOf(Step.Click(sendLabels)))
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        return launch(ctx, i, "E-mail aan $to wordt verstuurd.")
    }

    private val socialPackages = mapOf(
        "x" to "com.twitter.android",
        "twitter" to "com.twitter.android",
        "linkedin" to "com.linkedin.android",
        "facebook" to "com.facebook.katana",
        "threads" to "com.instagram.barcelona",
        "instagram" to "com.instagram.android",
        "telegram" to "org.telegram.messenger",
        "messenger" to "com.facebook.orca",
        "snapchat" to "com.snapchat.android",
        "signal" to "org.thoughtcrime.securesms"
    )

    private fun shareIntent(pkg: String, text: String): Intent {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, text)
        i.setPackage(pkg)
        return i
    }

    /** Plaatst een tekstpost: deelscherm openen, tekst invullen waar nodig, op Posten tikken. */
    private fun postSocial(ctx: Context, platform: String, text: String): String {
        val p = platform.lowercase(Locale.ROOT)
        val pkg = socialPackages[p] ?: return "Posten op $platform wordt niet ondersteund."
        return when (p) {
            "x", "twitter" -> {
                JarvisBridge.expectSteps(listOf(Step.Click(postLabels)))
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://twitter.com/intent/tweet?text=" + enc(text)))
                i.setPackage(pkg)
                launch(ctx, i, "Post wordt geplaatst op X.")
            }
            "facebook" -> {
                JarvisBridge.expectSteps(listOf(Step.Type(text), Step.Click(postLabels)))
                launch(ctx, shareIntent(pkg, text), "Post wordt geplaatst op Facebook.")
            }
            "linkedin", "threads" -> {
                JarvisBridge.expectSteps(listOf(Step.Click(postLabels)))
                launch(ctx, shareIntent(pkg, text), "Post wordt geplaatst op $platform.")
            }
            else -> "Voor $platform kan ik geen tekstpost plaatsen, want die app vereist een foto of video."
        }
    }

    /** Privébericht: deelscherm van de app, op de naam tikken, op Verzenden tikken. */
    private fun sendSocialMessage(ctx: Context, platform: String, to: String, msg: String): String {
        val p = platform.lowercase(Locale.ROOT)
        val pkg = socialPackages[p] ?: return "Berichten via $platform worden niet ondersteund."
        JarvisBridge.expectSteps(listOf(Step.Click(listOf(to)), Step.Click(sendLabels)), 30_000)
        return launch(ctx, shareIntent(pkg, msg), "Bericht aan $to wordt verstuurd via $platform.")
    }

    private fun call(ctx: Context, to: String): String {
        val number = resolveNumber(ctx, to) ?: return "Ik vind geen nummer voor $to."
        return launch(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")), "Ik bel $to.")
    }

    private fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(main, 0).firstOrNull {
            it.loadLabel(pm).toString().contains(name, ignoreCase = true)
        } ?: return "Ik vind de app $name niet."
        val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return "Kan $name niet openen."
        return launch(ctx, i, "$name is geopend.")
    }

    private fun addEvent(ctx: Context, title: String, start: String, minutes: Int): String {
        var calId: Long = -1L
        val cursor = ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null
        )
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    calId = cursor.getLong(0)
                }
            } finally {
                cursor.close()
            }
        }
        if (calId < 0) return "Ik vind geen schrijfbare agenda."
        val zone = ZoneId.systemDefault()
        val startMs = LocalDateTime.parse(start).atZone(zone).toInstant().toEpochMilli()
        val values = ContentValues()
        values.put(CalendarContract.Events.CALENDAR_ID, calId)
        values.put(CalendarContract.Events.TITLE, title)
        values.put(CalendarContract.Events.DTSTART, startMs)
        values.put(CalendarContract.Events.DTEND, startMs + minutes * 60_000L)
        values.put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
        ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return "Afspraak '$title' staat in je agenda."
    }

    private fun readCalendar(ctx: Context, days: Int): String {
        val now = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now)
        ContentUris.appendId(builder, now + days * 86_400_000L)
        val fmt = SimpleDateFormat("EEEE d MMM HH:mm", Locale("nl", "NL"))
        val out = StringBuilder()
        val cursor = ctx.contentResolver.query(
            builder.build(),
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
            null,
            null,
            CalendarContract.Instances.BEGIN + " ASC"
        )
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    out.append(fmt.format(Date(cursor.getLong(1)))).append(": ").append(cursor.getString(0)).append("\n")
                }
            } finally {
                cursor.close()
            }
        }
        val text = out.toString()
        return if (text.isBlank()) "Geen afspraken gevonden." else text
    }
}
