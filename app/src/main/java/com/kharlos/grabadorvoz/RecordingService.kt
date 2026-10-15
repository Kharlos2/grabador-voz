package com.kharlos.grabadorvoz

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File

/** Graba el audio. Corre como servicio en primer plano para seguir grabando con la app cerrada o la pantalla apagada. */
class RecordingService : Service() {

    enum class State { IDLE, RECORDING, PAUSED }

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    private val binder = LocalBinder()
    private var recorder: MediaRecorder? = null
    private var accumulated = 0L
    private var segmentStart = 0L

    var state = State.IDLE
        private set
    var file: File? = null
        private set

    /** Avisa a la pantalla cuando cambia el estado (también si se cambia desde la notificación). */
    var onStateChanged: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stop()
        }
        if (state == State.IDLE) stopSelf() else goForeground()
        return START_NOT_STICKY
    }

    fun start(target: File): Boolean {
        if (state != State.IDLE) return false
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            target.delete()
            return false
        }
        recorder = r
        file = target
        accumulated = 0L
        segmentStart = SystemClock.elapsedRealtime()
        state = State.RECORDING

        // Se marca como "iniciado" para que siga vivo aunque la pantalla se cierre.
        try {
            startService(Intent(this, RecordingService::class.java))
        } catch (e: Exception) {
        }
        goForeground()
        changed()
        return true
    }

    fun pause() {
        if (state != State.RECORDING) return
        try {
            recorder?.pause()
        } catch (e: Exception) {
            return
        }
        accumulated += SystemClock.elapsedRealtime() - segmentStart
        state = State.PAUSED
        refreshNotification()
        changed()
    }

    fun resume() {
        if (state != State.PAUSED) return
        try {
            recorder?.resume()
        } catch (e: Exception) {
            return
        }
        segmentStart = SystemClock.elapsedRealtime()
        state = State.RECORDING
        refreshNotification()
        changed()
    }

    /** Detiene y guarda. Devuelve el archivo si quedó válido. */
    fun stop(): File? {
        val r = recorder ?: return null
        val f = file
        var ok = true
        try {
            r.stop()
        } catch (e: Exception) {
            ok = false // grabación demasiado corta
        }
        r.release()
        recorder = null
        file = null
        accumulated = 0L
        state = State.IDLE
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        changed()
        if (!ok) {
            f?.delete()
            return null
        }
        return f
    }

    fun elapsed(): Long =
        accumulated + if (state == State.RECORDING) SystemClock.elapsedRealtime() - segmentStart else 0L

    /** Volumen máximo desde la última llamada (0..32767). */
    fun amplitude(): Int =
        if (state != State.RECORDING) 0 else try {
            recorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }

    override fun onDestroy() {
        if (recorder != null) stop()
        super.onDestroy()
    }

    private fun changed() {
        onStateChanged?.invoke()
    }

    // ---------- Notificación ----------

    private fun goForeground() {
        if (state == State.IDLE) return
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            // Si el sistema no lo permite, sigue grabando mientras la app esté abierta.
        }
    }

    private fun refreshNotification() {
        if (state == State.IDLE) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Grabación", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val recording = state == State.RECORDING
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (recording) "Grabando…" else "Grabación en pausa")
            .setContentText(file?.nameWithoutExtension ?: "")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (recording) {
            builder.setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsed()).setShowWhen(true)
            builder.addAction(0, "Pausar", action(ACTION_PAUSE, 1))
        } else {
            builder.setUsesChronometer(false).setShowWhen(false)
            builder.setContentText("Pausada en ${formatTime(elapsed())}")
            builder.addAction(0, "Retomar", action(ACTION_RESUME, 2))
        }
        builder.addAction(0, "Guardar", action(ACTION_STOP, 3))
        return builder.build()
    }

    private fun action(name: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, RecordingService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    companion object {
        private const val CHANNEL = "grabacion"
        private const val NOTIF_ID = 1
        private const val ACTION_PAUSE = "pausar"
        private const val ACTION_RESUME = "retomar"
        private const val ACTION_STOP = "guardar"
    }
}
