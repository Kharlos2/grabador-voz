package com.kharlos.grabadorvoz

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    // Vistas
    private lateinit var tvTimer: TextView
    private lateinit var tvStatus: TextView
    private lateinit var liveWave: WaveformView
    private lateinit var btnRecord: ImageButton
    private lateinit var tvRecordLabel: TextView
    private lateinit var btnSave: ImageButton
    private lateinit var btnDiscard: ImageButton
    private lateinit var colSave: View
    private lateinit var colDiscard: View
    private lateinit var tvEmpty: TextView
    private lateinit var playerPanel: View
    private lateinit var tvPlayerTitle: TextView
    private lateinit var playWave: WaveformView
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSpeed: MaterialButton
    private lateinit var adapter: RecordingsAdapter
    private lateinit var timerDefaultColor: ColorStateList

    private val handler = Handler(Looper.getMainLooper())

    // Grabación (vive en RecordingService para poder seguir en segundo plano)
    private var service: RecordingService? = null
    private var lastState = RecordingService.State.IDLE
    private val recState get() = service?.state ?: RecordingService.State.IDLE

    // Reproducción
    private var player: MediaPlayer? = null
    private var playing: Recording? = null
    private var userSeeking = false
    private val speeds = floatArrayOf(1.0f, 1.5f, 2.0f)
    private var speedIndex = 0
    private var anchorPos = 0L
    private var anchorTime = 0L

    private val recordingsDir by lazy { File(filesDir, "recordings").apply { mkdirs() } }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMic()) startRecording() else toast("Se necesita permiso de micrófono para grabar")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as RecordingService.LocalBinder).service
            service = s
            s.onStateChanged = { runOnUiThread { onRecStateChanged() } }
            if (s.state != RecordingService.State.IDLE && lastState == RecordingService.State.IDLE) liveWave.startLive()
            onRecStateChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val s = service
            if (s != null && s.state == RecordingService.State.RECORDING) {
                liveWave.addLiveLevel(WaveformView.level(s.amplitude() / 32767f))
            }
            updateRecTimer()
            updatePositionLabel()
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTimer = findViewById(R.id.tvTimer)
        tvStatus = findViewById(R.id.tvStatus)
        liveWave = findViewById(R.id.liveWave)
        btnRecord = findViewById(R.id.btnRecord)
        tvRecordLabel = findViewById(R.id.tvRecordLabel)
        btnSave = findViewById(R.id.btnSave)
        btnDiscard = findViewById(R.id.btnDiscard)
        colSave = findViewById(R.id.colSave)
        colDiscard = findViewById(R.id.colDiscard)
        tvEmpty = findViewById(R.id.tvEmpty)
        playerPanel = findViewById(R.id.playerPanel)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        playWave = findViewById(R.id.playWave)
        tvPosition = findViewById(R.id.tvPosition)
        tvDuration = findViewById(R.id.tvDuration)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnSpeed = findViewById(R.id.btnSpeed)
        timerDefaultColor = tvTimer.textColors

        liveWave.startLive()
        liveWave.setLiveActive(false)

        adapter = RecordingsAdapter(
            onClick = { play(it) },
            onMore = { rec, anchor -> showMenu(rec, anchor) }
        )
        findViewById<RecyclerView>(R.id.rvRecordings).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        btnRecord.setOnClickListener {
            when (recState) {
                RecordingService.State.IDLE -> checkPermissionsAndRecord()
                RecordingService.State.RECORDING -> service?.pause()
                RecordingService.State.PAUSED -> service?.resume()
            }
        }
        btnSave.setOnClickListener { stopAndSave() }
        btnDiscard.setOnClickListener { confirmDiscard() }

        btnPlayPause.setOnClickListener { togglePlay() }
        findViewById<MaterialButton>(R.id.btnBack10).setOnClickListener { seekBy(-10_000) }
        findViewById<MaterialButton>(R.id.btnFwd10).setOnClickListener { seekBy(10_000) }
        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            applySpeed()
        }
        findViewById<ImageButton>(R.id.btnClosePlayer).setOnClickListener { closePlayer() }

        playWave.showKnob = true
        playWave.positionProvider = { currentProgress() }
        playWave.onSeek = { p, finished ->
            player?.let { mp ->
                val pos = (p * mp.duration).toInt()
                userSeeking = !finished
                tvPosition.text = formatTime(pos.toLong())
                mp.seekTo(pos)
                resetAnchor(pos.toLong())
            }
        }

        updateRecUi()
        loadRecordings()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        // La grabación sigue en segundo plano; la reproducción se pausa.
        pausePlayback()
        service?.onStateChanged = null
        service = null
        unbindService(connection)
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    // ---------- Grabación ----------

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissionsAndRecord() {
        val needed = mutableListOf<String>()
        if (!hasMic()) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notifAsked
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
            notifAsked = true
        }
        if (needed.isEmpty()) startRecording() else askPermissions.launch(needed.toTypedArray())
    }

    private var notifAsked = false

    private fun startRecording() {
        val s = service ?: run {
            toast("Intenta de nuevo en un momento")
            return
        }
        pausePlayback()
        val name = "Grabación " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.getDefault()).format(Date())
        liveWave.startLive()
        if (!s.start(File(recordingsDir, "$name.m4a"))) toast("No se pudo iniciar la grabación")
    }

    private fun stopAndSave() {
        val s = service ?: return
        val file = s.stop()
        if (file == null) {
            toast("La grabación fue muy corta y no se guardó")
            return
        }
        promptRename(file, isNew = true)
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this)
            .setTitle("¿Descartar grabación?")
            .setMessage("Se perderá lo grabado hasta ahora.")
            .setPositiveButton("Descartar") { _, _ ->
                service?.stop()?.delete()
                toast("Grabación descartada")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Se llama cuando cambia el estado, desde la app o desde la notificación. */
    private fun onRecStateChanged() {
        val now = recState
        if (now == RecordingService.State.IDLE && lastState != RecordingService.State.IDLE) {
            liveWave.startLive()
            handler.post { loadRecordings() } // después de que se guarde o borre el archivo
        }
        lastState = now
        updateRecUi()
    }

    private fun updateRecTimer() {
        tvTimer.text = formatTime(service?.elapsed() ?: 0L)
    }

    private fun updateRecUi() {
        when (recState) {
            RecordingService.State.IDLE -> {
                btnRecord.setImageResource(R.drawable.ic_mic)
                tvRecordLabel.text = "Grabar"
                tvStatus.text = "Toca Grabar para empezar"
                tvTimer.setTextColor(timerDefaultColor)
                liveWave.setLiveActive(false)
            }
            RecordingService.State.RECORDING -> {
                btnRecord.setImageResource(R.drawable.ic_pause)
                tvRecordLabel.text = "Pausar"
                tvStatus.text = "Grabando…"
                tvTimer.setTextColor(ContextCompat.getColor(this, R.color.record_red))
                liveWave.setLiveActive(true)
            }
            RecordingService.State.PAUSED -> {
                btnRecord.setImageResource(R.drawable.ic_mic)
                tvRecordLabel.text = "Retomar"
                tvStatus.text = "En pausa"
                tvTimer.setTextColor(timerDefaultColor)
                liveWave.setLiveActive(false)
            }
        }
        val active = recState != RecordingService.State.IDLE
        btnSave.isEnabled = active
        btnDiscard.isEnabled = active
        colSave.alpha = if (active) 1f else 0.35f
        colDiscard.alpha = if (active) 1f else 0.35f
        updateRecTimer()
    }

    // ---------- Lista ----------

    private fun loadRecordings() {
        val inProgress = service?.file
        val files = recordingsDir.listFiles()
            ?.filter { it.isFile && it.extension == "m4a" && it != inProgress }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        thread {
            val list = files.map { Recording(it, it.nameWithoutExtension, it.lastModified(), readDuration(it), it.length()) }
            runOnUiThread {
                if (!isDestroyed) {
                    adapter.submit(list)
                    tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun readDuration(file: File): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                mmr.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun showMenu(rec: Recording, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Compartir")
            menu.add(0, 2, 1, "Renombrar")
            menu.add(0, 3, 2, "Eliminar")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> share(rec.file)
                    2 -> promptRename(rec.file, isNew = false)
                    3 -> confirmDelete(rec)
                }
                true
            }
            show()
        }
    }

    private fun share(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartir grabación"))
        } catch (e: Exception) {
            toast("No se pudo compartir")
        }
    }

    private fun promptRename(file: File, isNew: Boolean) {
        val input = EditText(this).apply {
            setText(file.nameWithoutExtension)
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(if (isNew) "Grabación guardada" else "Renombrar")
            .setMessage(if (isNew) "Puedes cambiarle el nombre:" else null)
            .setView(container)
            .setPositiveButton(if (isNew) "Aceptar" else "Renombrar") { _, _ -> renameFile(file, input.text.toString()) }
            .setNegativeButton(if (isNew) "Dejar así" else "Cancelar", null)
            .show()
        input.requestFocus()
    }

    private fun renameFile(file: File, newName: String) {
        val clean = newName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (clean.isEmpty() || clean == file.nameWithoutExtension) return
        val target = File(recordingsDir, "$clean.${file.extension}")
        if (target.exists()) {
            toast("Ya existe una grabación con ese nombre")
            return
        }
        if (!file.renameTo(target)) {
            toast("No se pudo renombrar")
            return
        }
        val current = playing
        if (current != null && current.file == file) {
            playing = current.copy(file = target, name = clean)
            tvPlayerTitle.text = clean
            adapter.setPlaying(target)
        }
        loadRecordings()
    }

    private fun confirmDelete(rec: Recording) {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar grabación?")
            .setMessage("\"${rec.name}\" se borrará del teléfono.")
            .setPositiveButton("Eliminar") { _, _ ->
                if (playing?.file == rec.file) closePlayer()
                rec.file.delete()
                loadRecordings()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Reproducción ----------

    private fun play(rec: Recording) {
        if (recState != RecordingService.State.IDLE) {
            toast("Guarda o descarta la grabación antes de reproducir")
            return
        }
        if (player != null && playing?.file == rec.file) {
            togglePlay()
            return
        }
        releasePlayer()

        val mp = MediaPlayer()
        try {
            mp.setDataSource(rec.file.absolutePath)
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            toast("No se pudo abrir el audio")
            return
        }
        mp.setOnCompletionListener {
            it.seekTo(0)
            resetAnchor(0)
            updatePlayButton()
        }

        player = mp
        playing = rec
        playerPanel.visibility = View.VISIBLE
        tvPlayerTitle.text = rec.name
        tvDuration.text = formatTime(mp.duration.toLong())
        tvPosition.text = formatTime(0)

        // Ondas: primero planas, luego las reales cuando termine de analizar el audio.
        playWave.setPeaks(null)
        playWave.progress = 0f
        val file = rec.file
        thread {
            val peaks = WaveformLoader.load(file)
            runOnUiThread { if (playing?.file == file && peaks != null) playWave.setPeaks(peaks) }
        }

        mp.start()
        applySpeed()
        resetAnchor(0)
        adapter.setPlaying(rec.file)
        updatePlayButton()
    }

    private fun togglePlay() {
        val mp = player ?: return
        if (mp.isPlaying) mp.pause() else mp.start()
        resetAnchor(mp.currentPosition.toLong())
        updatePlayButton()
    }

    private fun pausePlayback() {
        val mp = player ?: return
        if (mp.isPlaying) mp.pause()
        updatePlayButton()
    }

    private fun seekBy(deltaMs: Int) {
        val mp = player ?: return
        val pos = (mp.currentPosition + deltaMs).coerceIn(0, mp.duration)
        mp.seekTo(pos)
        resetAnchor(pos.toLong())
        tvPosition.text = formatTime(pos.toLong())
        if (!mp.isPlaying && mp.duration > 0) playWave.progress = pos.toFloat() / mp.duration
    }

    private fun applySpeed() {
        val speed = speeds[speedIndex]
        btnSpeed.text = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
        val mp = player ?: return
        val wasPlaying = mp.isPlaying
        try {
            mp.playbackParams = mp.playbackParams.setSpeed(speed)
        } catch (e: Exception) {
        }
        // Cambiar la velocidad arranca el audio en algunos teléfonos; se respeta la pausa.
        if (!wasPlaying && mp.isPlaying) mp.pause()
        resetAnchor(mp.currentPosition.toLong())
    }

    private fun resetAnchor(pos: Long) {
        anchorPos = pos
        anchorTime = SystemClock.uptimeMillis()
    }

    /**
     * Posición suavizada (0..1). El reproductor reporta la posición a saltos,
     * así que se calcula con el reloj y solo se corrige si se desfasa.
     */
    private fun currentProgress(): Float {
        val mp = player ?: return 0f
        val dur = mp.duration
        if (dur <= 0) return 0f
        val real = mp.currentPosition.toLong()
        val now = SystemClock.uptimeMillis()
        var est = if (mp.isPlaying) anchorPos + ((now - anchorTime) * speeds[speedIndex]).toLong() else real
        if (!mp.isPlaying || abs(est - real) > 300) {
            resetAnchor(real)
            est = real
        }
        return est.coerceIn(0L, dur.toLong()).toFloat() / dur
    }

    private fun updatePositionLabel() {
        val mp = player ?: return
        if (userSeeking || !mp.isPlaying) return
        tvPosition.text = formatTime(mp.currentPosition.toLong())
    }

    private fun updatePlayButton() {
        val mp = player
        val isPlaying = mp?.isPlaying == true
        btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        playWave.animating = isPlaying
        if (mp != null && !isPlaying && mp.duration > 0) {
            playWave.progress = mp.currentPosition.toFloat() / mp.duration
            tvPosition.text = formatTime(mp.currentPosition.toLong())
        }
    }

    private fun releasePlayer() {
        playWave.animating = false
        player?.release()
        player = null
        playing = null
        adapter.setPlaying(null)
    }

    private fun closePlayer() {
        releasePlayer()
        playerPanel.visibility = View.GONE
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
