package com.gustavo.agendavoz

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** Arma los textos que se leen en voz alta y configura la voz. */
object Speech {
    val ES = Locale("es", "AR")

    fun hourPhrase(t: LocalTime): String {
        val h = t.hour
        val base = if (h == 1 || h == 13) "a la $h" else "a las $h"
        return when (t.minute) {
            0 -> base
            15 -> "$base y cuarto"
            30 -> "$base y media"
            else -> "$base y ${t.minute}"
        }
    }

    fun minutesPhrase(m: Int): String = when {
        m == 60 -> "una hora"
        m == 120 -> "dos horas"
        m % 60 == 0 -> "${m / 60} horas"
        else -> "$m minutos"
    }

    fun dayName(d: LocalDate): String =
        d.dayOfWeek.getDisplayName(TextStyle.FULL, ES).replaceFirstChar { it.uppercase() } +
            " " + d.dayOfMonth + " de " + d.month.getDisplayName(TextStyle.FULL, ES)

    fun daySummary(ctx: Context, d: LocalDate = LocalDate.now()): String {
        val all = TaskStore.tasksOn(ctx, d)
        val pending = all.filter { !it.isDone(d) }
        val hour = LocalTime.now().hour
        val hello = when {
            hour < 13 -> "Buen día."
            hour < 20 -> "Buenas tardes."
            else -> "Buenas noches."
        }
        if (all.isEmpty()) return "$hello No tenés tareas agendadas para hoy."
        if (pending.isEmpty()) return "$hello Ya terminaste todas las tareas de hoy. ¡Bien ahí!"
        val sb = StringBuilder(hello)
        sb.append(if (pending.size == 1) " Hoy tenés una tarea pendiente. " else " Hoy tenés ${pending.size} tareas pendientes. ")
        pending.forEachIndexed { i, t ->
            val lead = when {
                pending.size == 1 -> ""
                i == 0 -> "Primero, "
                i == pending.size - 1 -> "Y por último, "
                else -> "Después, "
            }
            sb.append(lead).append(hourPhrase(t.time)).append(", ").append(t.title).append(". ")
        }
        return sb.toString()
    }

    /** Lo que se dice después de tachar una tarea. */
    fun afterDone(ctx: Context, d: LocalDate = LocalDate.now()): String {
        val next = TaskStore.tasksOn(ctx, d).firstOrNull { !it.isDone(d) }
            ?: return "Listo. ¡Terminaste todas las tareas del día!"
        return "Listo. Lo próximo es: ${next.title}, ${hourPhrase(next.time)}."
    }

    /** Texto corto para el widget. */
    fun nextShort(ctx: Context): String {
        val today = LocalDate.now()
        val pending = TaskStore.tasksOn(ctx, today).filter { !it.isDone(today) }
        if (pending.isEmpty()) return "Sin tareas pendientes hoy"
        val now = LocalDateTime.now().toLocalTime()
        val t = pending.firstOrNull { !it.time.isBefore(now) } ?: pending.first()
        return "%02d:%02d %s".format(t.time.hour, t.time.minute, t.title)
    }

    // ---------- Voz ----------

    fun prefs(ctx: Context) = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    fun spanishVoices(tts: TextToSpeech): List<Voice> = try {
        (tts.voices ?: emptySet()).filter { it.locale.language == "es" }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.locale.country != "US" }, { it.name }))
    } catch (e: Exception) {
        emptyList()
    }

    private fun looksMale(v: Voice): Boolean {
        val txt = (v.name + " " + v.features.joinToString(" ")).lowercase()
        return txt.contains("male") && !txt.contains("female")
    }

    fun configure(ctx: Context, tts: TextToSpeech) {
        val p = prefs(ctx)
        val saved = p.getString("voz", null)
        val voices = spanishVoices(tts)
        val chosen = voices.firstOrNull { it.name == saved }
            ?: voices.firstOrNull { looksMale(it) && !it.isNetworkConnectionRequired }
            ?: voices.firstOrNull { !it.isNetworkConnectionRequired }
        var ok = false
        if (chosen != null) {
            ok = try { tts.setVoice(chosen) == TextToSpeech.SUCCESS } catch (e: Exception) { false }
        }
        if (!ok) {
            for (loc in listOf(ES, Locale("es", "US"), Locale("es", "ES"), Locale("es"))) {
                val r = tts.isLanguageAvailable(loc)
                if (r >= TextToSpeech.LANG_AVAILABLE) { tts.language = loc; break }
            }
        }
        tts.setSpeechRate(p.getInt("velocidad", 100) / 100f)
        tts.setPitch(p.getInt("tono", 95) / 100f)
    }
}
