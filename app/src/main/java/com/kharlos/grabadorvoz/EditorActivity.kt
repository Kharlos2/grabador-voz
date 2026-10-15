package com.kharlos.grabadorvoz

import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.log10

/** Editor: recortar, cambiar velocidad y volumen, escuchar el resultado y guardar. */
class EditorActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var trimView: TrimView
    private lateinit var tvStart: TextView
    private lateinit var tvEnd: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvVolume: TextView
    private lateinit var tvResult: TextView
    private lateinit var sliderSpeed: Slider
    private lateinit var sliderVolume: Slider
    private lateinit var btnPreview: MaterialButton

    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var durationMs = 0L
    private var busy = false
    private val handler = Handler(Looper.getMainLooper())

    private val speed get() = sliderSpeed.value / 100f
    private val gain get() = sliderVolume.value / 100f
    private val startMs get() = (trimView.start * durationMs).toLong()
    private val endMs get() = (trimView.end * durationMs).toLong()

    private val ticker = object : Runnable {
        override fun run() {
            val mp = player ?: return
            if (mp.isPlaying) {
                val pos = mp.currentPosition.toLong()
                trimView.cursor = if (durationMs > 0) pos.toFloat() / durationMs else -1f
                if (pos >= endMs) stopPreview()
            }
            handler.postDelayed(this, 16)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null || !File(path).exists()) {
            finish()
            return
        }
        file = File(path)

        trimView = findViewById(R.id.trimView)
        tvStart = findViewById(R.id.tvStart)
        tvEnd = findViewById(R.id.tvEnd)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvVolume = findViewById(R.id.tvVolume)
        tvResult = findViewById(R.id.tvResult)
        sliderSpeed = findViewById(R.id.sliderSpeed)
        sliderVolume = findViewById(R.id.sliderVolume)
        btnPreview = findViewById(R.id.btnPreview)
        findViewById<TextView>(R.id.tvFileName).text = file.nameWithoutExtension

        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            toast("No se pudo abrir el audio")
            finish()
            return
        }
        player = mp
        durationMs = mp.duration.toLong()
        mp.setOnCompletionListener { stopPreview() }
        enhancer = try {
            LoudnessEnhancer(mp.audioSessionId)
        } catch (e: Exception) {
            null
        }

        trimView.minGap = if (durationMs > 0) (500f / durationMs).coerceAtMost(0.5f) else 0.02f
        trimView.onChange = { _, _, _ ->
            updateLabels()
            val p = player
            if (p != null && p.isPlaying) {
                val pos = p.currentPosition.toLong()
                if (pos < startMs || pos > endMs) p.seekTo(startMs.toInt())
            }
        }
        thread {
            val peaks = WaveformLoader.load(file)
            runOnUiThread { if (!isDestroyed && peaks != null) trimView.setPeaks(peaks) }
        }

        sliderSpeed.setLabelFormatter { formatSpeed(it / 100f) }
        sliderVolume.setLabelFormatter { "${it.toInt()}%" }
        sliderSpeed.addOnChangeListener { _, _, _ ->
            updateLabels()
            applyPreviewSettings()
        }
        sliderVolume.addOnChangeListener { _, _, _ ->
            updateLabels()
            applyPreviewSettings()
        }

        findViewById<MaterialButton>(R.id.btnResetTrim).setOnClickListener {
            trimView.setRange(0f, 1f)
            updateLabels()
        }
        findViewById<MaterialButton>(R.id.btnResetSpeed).setOnClickListener { sliderSpeed.value = 100f }
        findViewById<MaterialButton>(R.id.btnResetVolume).setOnClickListener { sliderVolume.value = 100f }
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        btnPreview.setOnClickListener { togglePreview() }
        findViewById<MaterialButton>(R.id.btnSaveCopy).setOnClickListener { export(replace = false) }
        findViewById<MaterialButton>(R.id.btnReplace).setOnClickListener {
            if (!hasChanges()) {
                toast("No hay cambios para guardar")
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("¿Reemplazar el original?")
                .setMessage("El audio original se cambiará por la versión editada. No se puede deshacer.")
                .setPositiveButton("Reemplazar") { _, _ -> export(replace = true) }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        updateLabels()
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        stopPreview()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    // ---------- Etiquetas ----------

    private fun updateLabels() {
        tvStart.text = "Inicio  ${formatPrecise(startMs)}"
        tvEnd.text = "Fin  ${formatPrecise(endMs)}"
        tvSpeed.text = formatSpeed(speed)
        tvVolume.text = "${sliderVolume.value.toInt()}%"
        val finalMs = ((endMs - startMs) / speed).toLong()
        tvResult.text = "Duración final: ${formatPrecise(finalMs)}  (original ${formatPrecise(durationMs)})"
    }

    private fun formatSpeed(s: Float): String =
        String.format(Locale.US, "%.2fx", s).replace(".00x", "x")

    private fun formatPrecise(ms: Long): String {
        val tenths = (ms % 1000) / 100
        return "${formatTime(ms)}.$tenths"
    }

    private fun hasChanges(): Boolean =
        trimView.start > 0.0005f || trimView.end < 0.9995f || sliderSpeed.value != 100f || sliderVolume.value != 100f

    // ---------- Vista previa ----------

    private fun togglePreview() {
        val mp = player ?: return
        if (mp.isPlaying) {
            stopPreview()
            return
        }
        val pos = mp.currentPosition.toLong()
        if (pos < startMs || pos >= endMs - 100) mp.seekTo(startMs.toInt())
        mp.start()
        applyPreviewSettings()
        btnPreview.text = "Detener"
        btnPreview.setIconResource(R.drawable.ic_pause)
    }

    private fun stopPreview() {
        val mp = player ?: return
        if (mp.isPlaying) mp.pause()
        try {
            mp.seekTo(startMs.toInt())
        } catch (e: Exception) {
        }
        trimView.cursor = -1f
        btnPreview.text = "Escuchar resultado"
        btnPreview.setIconResource(R.drawable.ic_play)
    }

    /** Aplica velocidad y volumen a la vista previa (sin modificar el archivo). */
    private fun applyPreviewSettings() {
        val mp = player ?: return
        val g = gain
        try {
            if (g <= 1f) {
                mp.setVolume(g, g)
                enhancer?.setEnabled(false)
            } else {
                mp.setVolume(1f, 1f)
                enhancer?.setTargetGain((2000 * log10(g)).toInt())
                enhancer?.setEnabled(true)
            }
        } catch (e: Exception) {
        }
        if (mp.isPlaying) {
            try {
                mp.playbackParams = mp.playbackParams.setSpeed(speed)
            } catch (e: Exception) {
            }
        }
    }

    private fun releasePlayer() {
        try { enhancer?.release() } catch (e: Exception) { }
        enhancer = null
        player?.release()
        player = null
    }

    // ---------- Guardar ----------

    private fun export(replace: Boolean) {
        if (busy) return
        if (!hasChanges()) {
            toast("No hay cambios para guardar")
            return
        }
        stopPreview()
        busy = true

        val s = startMs
        val e = endMs
        val sp = speed
        val g = gain
        val tmp = File(cacheDir, "edicion_${System.currentTimeMillis()}.m4a")

        val pad = (24 * resources.displayMetrics.density).toInt()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(this).apply { text = "Procesando… 0%" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label)
            addView(bar)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (replace) "Reemplazando audio" else "Guardando copia")
            .setView(box)
            .setCancelable(false)
            .show()

        thread {
            var lastPct = -1
            val ok = AudioProcessor.export(file, tmp, s, e, sp, g) { p ->
                val pct = (p * 100).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    runOnUiThread {
                        bar.progress = pct
                        label.text = "Procesando… $pct%"
                    }
                }
            }
            runOnUiThread {
                dialog.dismiss()
                busy = false
                if (!ok) {
                    tmp.delete()
                    toast("No se pudo procesar el audio")
                    return@runOnUiThread
                }
                val result = if (replace) replaceOriginal(tmp) else saveCopy(tmp)
                if (result == null) {
                    tmp.delete()
                    toast("No se pudo guardar")
                } else {
                    toast(if (replace) "Audio reemplazado" else "Copia guardada: ${result.nameWithoutExtension}")
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun moveFile(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        return try {
            from.copyTo(to, overwrite = true)
            from.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun replaceOriginal(tmp: File): File? {
        releasePlayer()
        val date = file.lastModified()
        val backup = File(cacheDir, "respaldo_${System.currentTimeMillis()}.m4a")
        if (!moveFile(file, backup)) return null
        return if (moveFile(tmp, file)) {
            file.setLastModified(date) // conserva su lugar en la lista por fecha
            backup.delete()
            file
        } else {
            moveFile(backup, file) // si algo falla, se devuelve el original
            null
        }
    }

    private fun saveCopy(tmp: File): File? {
        val dir = file.parentFile ?: return null
        val base = "${file.nameWithoutExtension} (editado)"
        var target = File(dir, "$base.m4a")
        var i = 2
        while (target.exists()) {
            target = File(dir, "$base $i.m4a")
            i++
        }
        return if (moveFile(tmp, target)) target else null
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PATH = "ruta"
    }
}
