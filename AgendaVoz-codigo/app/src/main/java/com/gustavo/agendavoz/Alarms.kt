package com.gustavo.agendavoz

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Programa los avisos (previo y a la hora) de cada tarea. */
object Scheduler {
    private const val ACTION_ALARM = "com.gustavo.agendavoz.AVISO"

    private fun alarmIntent(ctx: Context) =
        Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALARM)

    fun nextOccurrence(t: Task, now: LocalDateTime): LocalDateTime? {
        var d = now.toLocalDate()
        repeat(400) {
            if (t.occursOn(d) && !t.isDone(d)) {
                val dt = d.atTime(t.time)
                if (dt.isAfter(now)) return dt
            }
            d = d.plusDays(1)
        }
        return null
    }

    fun rescheduleAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val prefs = ctx.getSharedPreferences("alarmas", Context.MODE_PRIVATE)
        for (c in prefs.getStringSet("codigos", emptySet()) ?: emptySet()) {
            val code = c.toIntOrNull() ?: continue
            PendingIntent.getBroadcast(
                ctx, code, alarmIntent(ctx),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let { am.cancel(it); it.cancel() }
        }
        val codes = mutableSetOf<String>()
        val now = LocalDateTime.now()
        for (t in TaskStore.load(ctx)) {
            val occ = nextOccurrence(t, now) ?: continue
            if (t.reminderMin > 0) {
                val pre = occ.minusMinutes(t.reminderMin.toLong())
                if (pre.isAfter(now)) {
                    set(ctx, am, t.id * 2, pre, t.id, "previo", occ.toLocalDate())
                    codes.add((t.id * 2).toString())
                }
            }
            set(ctx, am, t.id * 2 + 1, occ, t.id, "hora", occ.toLocalDate())
            codes.add((t.id * 2 + 1).toString())
        }
        prefs.edit().putStringSet("codigos", codes).apply()
    }

    private fun set(ctx: Context, am: AlarmManager, code: Int, at: LocalDateTime, id: Int, kind: String, date: LocalDate) {
        val i = alarmIntent(ctx).putExtra("id", id).putExtra("tipo", kind).putExtra("fecha", date.toString())
        val pi = PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }
}

object Notifs {
    const val CH_ALERTS = "avisos"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH_ALERTS, "Avisos de tareas", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Avisos previos y a la hora de cada tarea"
        ch.enableVibration(true)
        nm.createNotificationChannel(ch)
    }

    fun show(ctx: Context, t: Task, date: LocalDate, title: String, text: String) {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val done = PendingIntent.getBroadcast(
            ctx, 100000 + t.id,
            Intent(ctx, DoneReceiver::class.java).putExtra("id", t.id).putExtra("fecha", date.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CH_ALERTS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(Notification.Action.Builder(null, "✔ Hecha", done).build())
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(t.id, n)
        } catch (e: SecurityException) { }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val kind = intent.getStringExtra("tipo") ?: "hora"
        val date = try { LocalDate.parse(intent.getStringExtra("fecha")) } catch (e: Exception) { LocalDate.now() }
        val t = TaskStore.load(ctx).find { it.id == id }
        if (t != null && !t.isDone(date)) {
            val hora = "%02d:%02d".format(t.time.hour, t.time.minute)
            if (kind == "previo") {
                Notifs.show(ctx, t, date, "En ${Speech.minutesPhrase(t.reminderMin)}: ${t.title}", "A las $hora")
                SpeakService.say(ctx, "Recordatorio. En ${Speech.minutesPhrase(t.reminderMin)}: ${t.title}, ${Speech.hourPhrase(t.time)}.")
            } else {
                Notifs.show(ctx, t, date, "Es la hora: ${t.title}", "Tarea de las $hora. Tocá Hecha cuando la termines.")
                SpeakService.say(ctx, "Es la hora. ${t.title}.")
            }
        }
        Scheduler.rescheduleAll(ctx)
        WidgetProvider.updateAll(ctx)
    }
}

class DoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val date = try { LocalDate.parse(intent.getStringExtra("fecha")) } catch (e: Exception) { LocalDate.now() }
        TaskStore.setDone(ctx, id, date, true)
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
        SpeakService.say(ctx, Speech.afterDone(ctx, date))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.rescheduleAll(ctx)
        WidgetProvider.updateAll(ctx)
    }
}

class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(ctx, mgr, it) }
    }

    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
            ids.forEach { update(ctx, mgr, it) }
        }

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            rv.setTextViewText(R.id.w_next, Speech.nextShort(ctx))
            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val speak = PendingIntent.getForegroundService(
                ctx, 2, Intent(ctx, SpeakService::class.java).setAction(SpeakService.ACTION_DAY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.w_open, open)
            rv.setOnClickPendingIntent(R.id.w_next, open)
            rv.setOnClickPendingIntent(R.id.w_speak, speak)
            mgr.updateAppWidget(id, rv)
        }
    }
}
