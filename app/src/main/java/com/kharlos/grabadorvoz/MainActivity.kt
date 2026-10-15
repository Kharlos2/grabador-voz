package com.kharlos.grabadorvoz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.SeekBar
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

class MainActivity : AppCompatActivity() {

    private enum class RecState { IDLE, RECORDING, PAUSED }

    // Vistas
    private lateinit var tvTimer: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnRecord: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnDiscard: MaterialButton
    private lateinit var tvEmpty: TextView
    private lateinit var playerPanel: View
    private lateinit var tvPlayerTitle: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSpeed: MaterialButton
    private lateinit var adapter: RecordingsAdapter
    private lateinit var timerDefaultColor: ColorStateList

    private val handler = Handler(Looper.getMainLooper())

    // Grabación
    private var recorder: MediaRecorder? = null
    private var recState = RecState.IDLE
    private var recFile: File? = null
    private var recAccumulated = 0L
    private var recSegmentStart = 0L

    // Reproducción
    private var player: MediaPlayer? = null
    private var playing: Recording? = null
    private var userSeeking = false
    private val speeds = floatArrayOf(1.0f, 1.5f, 2.0f)
    private var speedIndex = 0

    private val recordingsDir by lazy { File(filesDir, "recordings").apply { mkdirs() } }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast("Se necesita permiso de micrófono para grabar")
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateRecTimer()
            updatePlayerProgress()
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTimer = findViewById(R.id.tvTimer)
        tvStatus = findViewById(R.id.tvStatus)
        btnRecord = findViewById(R.id.btnRecord)
        btnSave = findViewById(R.id.btnSave)
        btnDiscard = findViewById(R.id.btnDiscard)
        tvEmpty = findViewById(R.id.tvEmpty)
        playerPanel = findViewById(R.id.playerPanel)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        seekBar = findViewById(R.id.seekBar)
        tvPosition = findViewById(R.id.tvPosition)
        tvDuration = findViewById(R.id.tvDuration)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnSpeed = findViewById(R.id.btnSpeed)
        timerDefaultColor = tvTimer.textColors

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
                RecState.IDLE -> checkPermissionAndRecord()
                RecState.RECORDING -> pauseRecording()
                RecState.PAUSED -> resumeRecording()
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

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvPosition.text = formatTime(progress.toLong())
                    player?.seekTo(progress)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                player?.seekTo(sb.progress)
            }
        })

        updateRecUi()
        loadRecordings()
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        // Sin segundo plano: al salir de la app se pausa la grabación y la reproducción.
        if (recState == RecState.RECORDING) pauseRecording()
        pausePlayback()
    }

    override fun onDestroy() {
        // Si la app se cierra con una grabación abierta, se cierra el archivo para no perderla.
        if (recorder != null) finishRecorder()
        player?.release()
        player = null
        super.onDestroy()
    }

    // ---------- Grabación ----------

    private fun checkPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        pausePlayback()
        val name = "Grabación " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.getDefault()).format(Date())
        val file = File(recordingsDir, "$name.m4a")

        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            toast("No se pudo iniciar la grabación")
            return
        }

        recorder = r
        recFile = file
        recAccumulated = 0L
        recSegmentStart = SystemClock.elapsedRealtime()
        recState = RecState.RECORDING
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateRecUi()
    }

    private fun pauseRecording() {
        if (recState != RecState.RECORDING) return
        try {
            recorder?.pause()
        } catch (e: Exception) {
            return
        }
        recAccumulated += SystemClock.elapsedRealtime() - recSegmentStart
        recState = RecState.PAUSED
        updateRecUi()
    }

    private fun resumeRecording() {
        if (recState != RecState.PAUSED) return
        try {
            recorder?.resume()
        } catch (e: Exception) {
            toast("No se pudo retomar la grabación")
            return
        }
        recSegmentStart = SystemClock.elapsedRealtime()
        recState = RecState.RECORDING
        updateRecUi()
    }

    /** Detiene y libera el grabador. Devuelve el archivo si quedó válido. */
    private fun finishRecorder(): File? {
        val r = recorder ?: return null
        val file = recFile
        var ok = true
        try {
            r.stop()
        } catch (e: Exception) {
            ok = false // pasa cuando la grabación es demasiado corta
        }
        r.release()
        recorder = null
        recFile = null
        recState = RecState.IDLE
        recAccumulated = 0L
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateRecUi()
        if (!ok) {
            file?.delete()
            return null
        }
        return file
    }

    private fun stopAndSave() {
        val file = finishRecorder()
        if (file == null) {
            toast("La grabación fue muy corta y no se guardó")
            return
        }
        loadRecordings()
        promptRename(file, isNew = true)
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this)
            .setTitle("¿Descartar grabación?")
            .setMessage("Se perderá lo grabado hasta ahora.")
            .setPositiveButton("Descartar") { _, _ ->
                finishRecorder()?.delete()
                toast("Grabación descartada")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun elapsedRecording(): Long =
        recAccumulated + if (recState == RecState.RECORDING) SystemClock.elapsedRealtime() - recSegmentStart else 0L

    private fun updateRecTimer() {
        tvTimer.text = formatTime(elapsedRecording())
    }

    private fun updateRecUi() {
        when (recState) {
            RecState.IDLE -> {
                btnRecord.text = "Grabar"
                btnRecord.setIconResource(R.drawable.ic_mic)
                tvStatus.text = "Toca Grabar para empezar"
                tvTimer.setTextColor(timerDefaultColor)
            }
            RecState.RECORDING -> {
                btnRecord.text = "Pausar"
                btnRecord.setIconResource(R.drawable.ic_pause)
                tvStatus.text = "Grabando…"
                tvTimer.setTextColor(ContextCompat.getColor(this, R.color.record_red))
            }
            RecState.PAUSED -> {
                btnRecord.text = "Retomar"
                btnRecord.setIconResource(R.drawable.ic_mic)
                tvStatus.text = "En pausa"
                tvTimer.setTextColor(timerDefaultColor)
            }
        }
        val active = recState != RecState.IDLE
        btnSave.isEnabled = active
        btnDiscard.isEnabled = active
        updateRecTimer()
    }

    // ---------- Lista ----------

    private fun loadRecordings() {
        val inProgress = recFile
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
        if (recState != RecState.IDLE) {
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
            updatePlayButton()
            updatePlayerProgress()
        }

        player = mp
        playing = rec
        playerPanel.visibility = View.VISIBLE
        tvPlayerTitle.text = rec.name
        seekBar.max = mp.duration
        seekBar.progress = 0
        tvDuration.text = formatTime(mp.duration.toLong())
        mp.start()
        applySpeed()
        adapter.setPlaying(rec.file)
        updatePlayButton()
        updatePlayerProgress()
    }

    private fun togglePlay() {
        val mp = player ?: return
        if (mp.isPlaying) mp.pause() else mp.start()
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
        seekBar.progress = pos
        tvPosition.text = formatTime(pos.toLong())
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
    }

    private fun updatePlayButton() {
        val isPlaying = player?.isPlaying == true
        btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updatePlayerProgress() {
        val mp = player ?: return
        if (userSeeking) return
        val pos = mp.currentPosition
        seekBar.progress = pos
        tvPosition.text = formatTime(pos.toLong())
    }

    private fun releasePlayer() {
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
