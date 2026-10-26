package com.gustavo.agendavoz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/** Habla en segundo plano: sirve para el widget, los avisos y al tachar tareas. */
class SpeakService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val queue = mutableListOf<String>()
    private var outstanding = 0
    private var counter = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val text = when (intent?.action) {
            ACTION_DAY -> Speech.daySummary(this)
            else -> intent?.getStringExtra(EXTRA_TEXT)
        }
        if (text.isNullOrBlank()) {
            if (outstanding == 0 && queue.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DAY) tts?.stop()
        if (tts == null) tts = TextToSpeech(this, this)
        if (ready) speak(text) else queue.add(text)
        // por seguridad, nunca queda colgado más de 3 minutos
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, 180_000)
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) { stopSelf(); return }
        Speech.configure(this, engine)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finishedOne() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { finishedOne() }
            override fun onError(utteranceId: String?, errorCode: Int) { finishedOne() }
        })
        ready = true
        val pending = queue.toList()
        queue.clear()
        pending.forEach { speak(it) }
    }

    private fun speak(text: String) {
        outstanding++
        val r = tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "u${counter++}")
        if (r != TextToSpeech.SUCCESS) finishedOne()
    }

    private fun finishedOne() {
        handler.post {
            outstanding--
            if (outstanding <= 0 && queue.isEmpty()) stopSelf()
        }
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_VOICE, "Lectura en voz", NotificationManager.IMPORTANCE_MIN)
        )
        val n: Notification = Notification.Builder(this, CH_VOICE)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Agenda Voz")
            .setContentText("Leyendo tus tareas…")
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_DAY = "com.gustavo.agendavoz.LEER_DIA"
        const val ACTION_SAY = "com.gustavo.agendavoz.DECIR"
        const val EXTRA_TEXT = "texto"
        const val CH_VOICE = "voz"

        fun say(ctx: Context, text: String) {
            val i = Intent(ctx, SpeakService::class.java).setAction(ACTION_SAY).putExtra(EXTRA_TEXT, text)
            try { ctx.startForegroundService(i) } catch (e: Exception) { }
        }

        fun readDay(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, SpeakService::class.java).setAction(ACTION_DAY))
            } catch (e: Exception) { }
        }
    }
}
