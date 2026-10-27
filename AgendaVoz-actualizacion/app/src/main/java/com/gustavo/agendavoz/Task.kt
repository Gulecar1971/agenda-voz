package com.gustavo.agendavoz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

enum class Repeat { NONE, DAILY, WEEKLY, MONTHLY }

class Task(
    var id: Int,
    var title: String,
    var date: LocalDate,          // fecha de la tarea (o desde cuándo se repite)
    var time: LocalTime,
    var reminderMin: Int,         // minutos de aviso previo (0 = sin aviso previo)
    var repeat: Repeat,
    var weekDays: MutableSet<Int>, // 1 = lunes ... 7 = domingo
    val doneDates: MutableSet<String> = mutableSetOf(),
    var nagEvery: Int = 10,       // si no está hecha, volver a avisar cada X minutos (0 = no)
    var nagFor: Int = 30          // ...durante Y minutos después de la hora
) {
    fun occursOn(d: LocalDate): Boolean = when (repeat) {
        Repeat.NONE -> d == date
        Repeat.DAILY -> !d.isBefore(date)
        Repeat.WEEKLY -> !d.isBefore(date) && weekDays.contains(d.dayOfWeek.value)
        Repeat.MONTHLY -> !d.isBefore(date) && d.dayOfMonth == date.dayOfMonth
    }

    fun isDone(d: LocalDate) = doneDates.contains(d.toString())

    fun setDone(d: LocalDate, done: Boolean) {
        if (done) doneDates.add(d.toString()) else doneDates.remove(d.toString())
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("date", date.toString())
        put("time", time.toString())
        put("reminder", reminderMin)
        put("repeat", repeat.name)
        put("days", JSONArray(weekDays.toList()))
        put("done", JSONArray(doneDates.toList()))
        put("nagEvery", nagEvery)
        put("nagFor", nagFor)
    }

    companion object {
        fun fromJson(o: JSONObject): Task {
            val days = mutableSetOf<Int>()
            o.optJSONArray("days")?.let { a -> for (i in 0 until a.length()) days.add(a.getInt(i)) }
            val done = mutableSetOf<String>()
            o.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) done.add(a.getString(i)) }
            return Task(
                o.getInt("id"),
                o.getString("title"),
                LocalDate.parse(o.getString("date")),
                LocalTime.parse(o.getString("time")),
                o.optInt("reminder", 0),
                try { Repeat.valueOf(o.optString("repeat", "NONE")) } catch (e: Exception) { Repeat.NONE },
                days,
                done,
                o.optInt("nagEvery", 10),
                o.optInt("nagFor", 30)
            )
        }
    }
}

object TaskStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "tareas.json")

    fun load(ctx: Context): MutableList<Task> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { Task.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** Guarda, reprograma los avisos y actualiza el widget. */
    fun save(ctx: Context, tasks: List<Task>) {
        val limit = LocalDate.now().minusDays(60)
        val arr = JSONArray()
        for (t in tasks) {
            // limpiamos marcas viejas de "hecho" para que el archivo no crezca
            t.doneDates.removeAll { s -> try { LocalDate.parse(s).isBefore(limit) } catch (e: Exception) { true } }
            arr.put(t.toJson())
        }
        val f = file(ctx)
        val tmp = File(f.parentFile, "tareas.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
        Scheduler.rescheduleAll(ctx)
        WidgetProvider.updateAll(ctx)
    }

    fun tasksOn(ctx: Context, d: LocalDate, all: List<Task> = load(ctx)): List<Task> =
        all.filter { it.occursOn(d) }.sortedBy { it.time }

    fun nextId(tasks: List<Task>): Int = (tasks.maxOfOrNull { it.id } ?: 0) + 1

    fun setDone(ctx: Context, id: Int, d: LocalDate, done: Boolean) {
        val all = load(ctx)
        all.find { it.id == id }?.setDone(d, done)
        save(ctx, all)
    }
}
