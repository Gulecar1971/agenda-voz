package com.gustavo.agendavoz

import java.time.LocalDate
import java.time.LocalTime

/**
 * Entiende frases dictadas como:
 *  "mañana a las 10 llamar al distribuidor"
 *  "el viernes a las 3 y media ir al banco"
 *  "hoy a las 18:30 cerrar caja"
 *  "el 15 de noviembre a las 9 pagar proveedor"
 */
object VoiceParser {
    data class Result(val title: String, val date: LocalDate, val time: LocalTime?)

    private val numberWords = mapOf(
        "una" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12
    )
    private val weekdays = mapOf(
        "lunes" to 1, "martes" to 2, "miércoles" to 3, "miercoles" to 3, "jueves" to 4,
        "viernes" to 5, "sábado" to 6, "sabado" to 6, "domingo" to 7
    )
    private val months = mapOf(
        "enero" to 1, "febrero" to 2, "marzo" to 3, "abril" to 4, "mayo" to 5, "junio" to 6,
        "julio" to 7, "agosto" to 8, "septiembre" to 9, "setiembre" to 9, "octubre" to 10,
        "noviembre" to 11, "diciembre" to 12
    )

    fun parse(input: String, today: LocalDate = LocalDate.now()): Result {
        var s = " " + input.lowercase().replace(Regex("\\s+"), " ").trim() + " "
        var date = today
        var time: LocalTime? = null
        var hourHint = 0 // 1 = mañana (am), 2 = tarde/noche (pm)

        fun cut(r: Regex): MatchResult? {
            val m = r.find(s) ?: return null
            s = s.removeRange(m.range).let { " " + it.trim() + " " }
            return m
        }

        // momento del día (antes de buscar "mañana" como día)
        if (cut(Regex(" (de|por) la (mañana|manana) ")) != null) hourHint = 1
        if (cut(Regex(" (de|por) la (tarde|noche) | p\\.? ?m\\.? ")) != null) hourHint = 2
        if (cut(Regex(" a\\.? ?m\\.? ")) != null) hourHint = 1

        // hora
        val num = "(\\d{1,2}|" + numberWords.keys.joinToString("|") + ")"
        val timeRe = Regex(" (?:a|para) (?:las?|eso de las) $num(?:(?::|\\.)(\\d{2})| y (media|cuarto|\\d{1,2})| menos cuarto)?(?: ?(?:hs|horas|h)\\b\\.?)? ")
        val tm = cut(timeRe)
        if (tm != null) {
            var h = tm.groupValues[1].let { it.toIntOrNull() ?: numberWords[it] ?: 0 }
            var min = 0
            val g2 = tm.groupValues[2]
            val g3 = tm.groupValues[3]
            when {
                g2.isNotEmpty() -> min = g2.toInt()
                g3 == "media" -> min = 30
                g3 == "cuarto" -> min = 15
                g3.isNotEmpty() -> min = g3.toIntOrNull() ?: 0
            }
            if (tm.value.contains("menos cuarto")) { h -= 1; min = 45 }
            if (hourHint == 2 && h < 12) h += 12
            if (hourHint == 0 && h in 1..6) h += 12 // "a las 3" casi siempre es de la tarde
            if (h in 0..23 && min in 0..59) time = LocalTime.of(h, min)
        } else if (cut(Regex(" (al|a el) (mediodía|mediodia) ")) != null) {
            time = LocalTime.NOON
        } else if (cut(Regex(" a la medianoche ")) != null) {
            time = LocalTime.of(23, 59)
        }

        // día
        when {
            cut(Regex(" pasado (mañana|manana) ")) != null -> date = today.plusDays(2)
            cut(Regex(" (mañana|manana) ")) != null -> date = today.plusDays(1)
            cut(Regex(" hoy ")) != null -> date = today
            else -> {
                val wd = cut(Regex(" (?:el |este |próximo |proximo )?(" + weekdays.keys.joinToString("|") + ")(?: que viene| próximo| proximo)? "))
                if (wd != null) {
                    val target = weekdays[wd.groupValues[1]] ?: 1
                    var diff = target - today.dayOfWeek.value
                    if (diff <= 0) diff += 7
                    date = today.plusDays(diff.toLong())
                } else {
                    val dm = cut(Regex(" (?:el (?:día |dia )?|día |dia )(\\d{1,2})(?: de (" + months.keys.joinToString("|") + "))? "))
                    if (dm != null) {
                        val day = dm.groupValues[1].toInt()
                        val month = months[dm.groupValues[2]]
                        date = buildDate(today, day, month) ?: today
                    }
                }
            }
        }

        // limpiar palabras de comando
        s = s.replace(Regex("^ *(recordame|recordarme|recuérdame|recuerdame|agendame|agendá|agenda|agendar|anotá|anota|anotame|poné|pone|que) "), " ")
        var title = s.trim().trim(',', '.', ' ')
        title = title.replace(Regex("^(que|para|de|y|a) "), "").replace(Regex(" (para|de|y|a)$"), "").trim()
        if (title.isEmpty()) title = input.trim()
        title = title.replaceFirstChar { it.uppercase() }
        return Result(title, date, time)
    }

    private fun buildDate(today: LocalDate, day: Int, month: Int?): LocalDate? {
        if (day !in 1..31) return null
        return try {
            if (month != null) {
                var d = LocalDate.of(today.year, month, day)
                if (d.isBefore(today)) d = d.plusYears(1)
                d
            } else {
                var d = today.withDayOfMonth(minOf(day, today.lengthOfMonth()))
                if (d.isBefore(today)) {
                    val nm = today.plusMonths(1)
                    d = nm.withDayOfMonth(minOf(day, nm.lengthOfMonth()))
                }
                d
            }
        } catch (e: Exception) { null }
    }
}
