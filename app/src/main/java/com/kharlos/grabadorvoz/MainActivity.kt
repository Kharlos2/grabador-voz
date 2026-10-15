package com.kharlos.grabadorvoz

import android.Manifest
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
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
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    // Vistas de inicio
    private lateinit var tvTimer: TextView
    private lateinit var tvStatus: TextView
    private lateinit var liveWave: WaveformView
    private lateinit var qualityGroup: MaterialButtonToggleGroup
    private lateinit var btnRecord: ImageButton
    private lateinit var tvRecordLabel: TextView
    private lateinit var btnSave: ImageButton
    private lateinit var btnDiscard: ImageButton
    private lateinit var colSave: View
    private lateinit var colDiscard: View
    private lateinit var tvEmpty: TextView
    private lateinit var homePage: View
    private lateinit var timerDefaultColor: ColorStateList

    // Vistas de biblioteca
    private lateinit var libraryPage: View
    private lateinit var tvLibTitle: TextView
    private lateinit var tvLibSubtitle: TextView
    private lateinit var tvLibHint: TextView
    private lateinit var tvLibEmpty: TextView
    private lateinit var btnLibAction: ImageButton
    private lateinit var rvLibrary: RecyclerView

    // Vistas del reproductor
    private lateinit var playerPanel: View
    private lateinit var tvPlayerTitle: TextView
    private lateinit var playWave: WaveformView
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSpeed: MaterialButton

    private lateinit var homeAdapter: RecordingsAdapter
    private lateinit var libAdapter: RecordingsAdapter
    private lateinit var foldersAdapter: FoldersAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { RecordingStore(File(filesDir, "recordings")) }
    private val prefs by lazy { getSharedPreferences("ajustes", Context.MODE_PRIVATE) }

    // Grabación (vive en RecordingService para poder seguir en segundo plano)
    private var service: RecordingService? = null
    private var lastState = RecordingService.State.IDLE
    private val recState get() = service?.state ?: RecordingService.State.IDLE
    private var quality = Quality.VOICE
    private var notifAsked = false

    // Biblioteca: null = lista de carpetas; si no, la carpeta abierta
    private var libFolder: File? = null

    // Reproducción
    private var player: MediaPlayer? = null
    private var playing: Recording? = null
    private var userSeeking = false
    private val speeds = floatArrayOf(1.0f, 1.5f, 2.0f)
    private var speedIndex = 0
    private var anchorPos = 0L
    private var anchorTime = 0L

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMic()) startRecording() else toast("Se necesita permiso de micrófono para grabar")
    }

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshAll()
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

    // Panel de grabaciones deslizable: 0 = normal, 1 = arriba del todo (tapa el grabador)
    private lateinit var topSection: View
    private lateinit var listSheet: View
    private lateinit var rvRecordings: RecyclerView
    private var sheetExpanded = 0f
    private var sheetAnim: ValueAnimator? = null

    private val collapseSheet = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            animateSheet(0f)
        }
    }

    private val backFromLibrary = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (libFolder != null) showFolders() else closeLibrary()
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

        homePage = findViewById(R.id.homePage)
        tvTimer = findViewById(R.id.tvTimer)
        tvStatus = findViewById(R.id.tvStatus)
        liveWave = findViewById(R.id.liveWave)
        qualityGroup = findViewById(R.id.qualityGroup)
        btnRecord = findViewById(R.id.btnRecord)
        tvRecordLabel = findViewById(R.id.tvRecordLabel)
        btnSave = findViewById(R.id.btnSave)
        btnDiscard = findViewById(R.id.btnDiscard)
        colSave = findViewById(R.id.colSave)
        colDiscard = findViewById(R.id.colDiscard)
        tvEmpty = findViewById(R.id.tvEmpty)
        libraryPage = findViewById(R.id.libraryPage)
        tvLibTitle = findViewById(R.id.tvLibTitle)
        tvLibSubtitle = findViewById(R.id.tvLibSubtitle)
        tvLibHint = findViewById(R.id.tvLibHint)
        tvLibEmpty = findViewById(R.id.tvLibEmpty)
        btnLibAction = findViewById(R.id.btnLibAction)
        rvLibrary = findViewById(R.id.rvLibrary)
        playerPanel = findViewById(R.id.playerPanel)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        playWave = findViewById(R.id.playWave)
        tvPosition = findViewById(R.id.tvPosition)
        tvDuration = findViewById(R.id.tvDuration)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnSpeed = findViewById(R.id.btnSpeed)
        timerDefaultColor = tvTimer.textColors

        topSection = findViewById(R.id.topSection)
        listSheet = findViewById(R.id.listSheet)
        rvRecordings = findViewById(R.id.rvRecordings)
        setupSheet()

        // El último registrado tiene prioridad: primero se cierra la biblioteca, luego se baja el panel.
        onBackPressedDispatcher.addCallback(this, collapseSheet)
        onBackPressedDispatcher.addCallback(this, backFromLibrary)

        // Calidad
        quality = try {
            Quality.valueOf(prefs.getString("calidad", Quality.VOICE.name)!!)
        } catch (e: Exception) {
            Quality.VOICE
        }
        qualityGroup.check(
            when (quality) {
                Quality.VOICE -> R.id.qVoice
                Quality.HIGH -> R.id.qHigh
                Quality.MUSIC -> R.id.qMusic
            }
        )
        qualityGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            quality = when (id) {
                R.id.qHigh -> Quality.HIGH
                R.id.qMusic -> Quality.MUSIC
                else -> Quality.VOICE
            }
            prefs.edit().putString("calidad", quality.name).apply()
            updateRecUi()
        }

        liveWave.startLive()
        liveWave.setLiveActive(false)

        // Listas
        homeAdapter = RecordingsAdapter(true, { play(it) }, { rec, anchor -> showRecordingMenu(rec, anchor) })
        libAdapter = RecordingsAdapter(false, { play(it) }, { rec, anchor -> showRecordingMenu(rec, anchor) })
        foldersAdapter = FoldersAdapter({ openFolder(it.dir) }, { item, anchor -> showFolderMenu(item.dir, anchor) })
        rvRecordings.layoutManager = LinearLayoutManager(this)
        rvRecordings.adapter = homeAdapter
        rvLibrary.layoutManager = LinearLayoutManager(this)

        // Botones de grabación
        btnRecord.setOnClickListener {
            when (recState) {
                RecordingService.State.IDLE -> checkPermissionsAndRecord()
                RecordingService.State.RECORDING -> service?.pause()
                RecordingService.State.PAUSED -> service?.resume()
            }
        }
        btnSave.setOnClickListener { stopAndSave() }
        btnDiscard.setOnClickListener { confirmDiscard() }

        // Biblioteca
        findViewById<MaterialButton>(R.id.btnLibrary).setOnClickListener { openLibrary() }
        findViewById<ImageButton>(R.id.btnLibBack).setOnClickListener { backFromLibrary.handleOnBackPressed() }
        btnLibAction.setOnClickListener { v ->
            val folder = libFolder
            if (folder == null) promptNewFolder { refreshAll() } else showFolderMenu(folder, v)
        }

        // Reproductor
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
        refreshAll()
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

    // ================= Panel deslizable =================

    private fun setupSheet() {
        listSheet.isClickable = true // que los toques no pasen al grabador de abajo
        topSection.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) applySheet(sheetExpanded)
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val header = findViewById<View>(R.id.sheetHeader)
        header.setOnTouchListener(object : View.OnTouchListener {
            var startY = 0f
            var startT = 0f
            var lastY = 0f
            var lastTime = 0L
            var velocity = 0f
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sheetAnim?.cancel()
                        startY = e.rawY
                        startT = listSheet.translationY
                        lastY = e.rawY
                        lastTime = e.eventTime
                        velocity = 0f
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - startY
                        if (abs(dy) > slop) moved = true
                        val dt = e.eventTime - lastTime
                        if (dt > 0) velocity = (e.rawY - lastY) / dt * 1000f
                        lastY = e.rawY
                        lastTime = e.eventTime
                        val max = sheetMax()
                        if (moved && max > 0f) applySheet(1f - (startT + dy).coerceIn(0f, max) / max)
                    }
                    MotionEvent.ACTION_UP -> {
                        val target = when {
                            !moved -> if (sheetExpanded > 0.5f) 0f else 1f // un toque alterna
                            abs(velocity) > 800f -> if (velocity < 0) 1f else 0f
                            else -> if (sheetExpanded > 0.5f) 1f else 0f
                        }
                        animateSheet(target)
                        v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> animateSheet(if (sheetExpanded > 0.5f) 1f else 0f)
                }
                return true
            }
        })
    }

    private fun sheetMax() = topSection.height.toFloat()

    private fun applySheet(fraction: Float) {
        sheetExpanded = fraction.coerceIn(0f, 1f)
        val t = sheetMax() * (1f - sheetExpanded)
        listSheet.translationY = t
        topSection.alpha = 1f - 0.7f * sheetExpanded
        // El panel se corre hacia abajo; el espacio extra al final permite ver el último audio.
        val extra = t.toInt() + (8 * resources.displayMetrics.density).toInt()
        if (rvRecordings.paddingBottom != extra) rvRecordings.setPadding(0, 0, 0, extra)
        collapseSheet.isEnabled = sheetExpanded > 0.5f
    }

    private fun animateSheet(target: Float) {
        sheetAnim?.cancel()
        sheetAnim = ValueAnimator.ofFloat(sheetExpanded, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { applySheet(it.animatedValue as Float) }
            start()
        }
    }

    // ================= Grabación =================

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

    private fun startRecording() {
        val s = service ?: run {
            toast("Intenta de nuevo en un momento")
            return
        }
        pausePlayback()
        val name = "Grabación " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.getDefault()).format(Date())
        liveWave.startLive()
        if (!s.start(File(store.root, "$name.m4a"), quality)) toast("No se pudo iniciar la grabación")
    }

    private fun stopAndSave() {
        val s = service ?: return
        val file = s.stop()
        if (file == null) {
            toast("La grabación fue muy corta y no se guardó")
            return
        }
        promptSave(file)
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
            handler.post { refreshAll() } // después de que se guarde o borre el archivo
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
                tvStatus.text = quality.hint
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
        // La calidad solo se cambia antes de empezar.
        for (i in 0 until qualityGroup.childCount) qualityGroup.getChildAt(i).isEnabled = !active
        updateRecTimer()
    }

    // ================= Listas =================

    private fun refreshAll() {
        loadInto(homeAdapter, store.allAudios()) { empty -> tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE }
        if (libraryPage.visibility == View.VISIBLE) refreshLibrary()
    }

    private fun loadInto(adapter: RecordingsAdapter, files: List<File>, onDone: (Boolean) -> Unit) {
        val inProgress = service?.file
        val sorted = files.filter { it != inProgress }.sortedByDescending { it.lastModified() }
        val gen = ++adapter.generation
        thread {
            val list = sorted.map {
                Recording(it, it.nameWithoutExtension, store.folderOf(it), it.lastModified(), readDuration(it), it.length())
            }
            runOnUiThread {
                if (!isDestroyed && gen == adapter.generation) {
                    adapter.submit(list)
                    onDone(list.isEmpty())
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

    private fun showRecordingMenu(rec: Recording, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 5, 0, "Editar")
            menu.add(0, 1, 1, "Compartir")
            menu.add(0, 2, 2, "Renombrar")
            menu.add(0, 3, 3, "Mover a carpeta")
            menu.add(0, 4, 4, "Eliminar")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    5 -> openEditor(rec.file)
                    1 -> share(rec.file)
                    2 -> renameRecording(rec.file)
                    3 -> moveRecording(rec.file)
                    4 -> confirmDelete(rec)
                }
                true
            }
            show()
        }
    }

    private fun openEditor(file: File) {
        if (recState != RecordingService.State.IDLE) {
            toast("Guarda o descarta la grabación antes de editar")
            return
        }
        if (playing?.file == file) closePlayer() // el editor usa su propio reproductor
        else pausePlayback()
        editLauncher.launch(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_PATH, file.absolutePath))
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

    private fun renameRecording(file: File) {
        askText("Renombrar", file.nameWithoutExtension, "Renombrar") { name ->
            val target = store.rename(file, name)
            if (target == null) {
                toast("Ya existe una grabación con ese nombre")
            } else {
                onFileMoved(file, target)
                refreshAll()
            }
        }
    }

    private fun moveRecording(file: File) {
        chooseFolder(file.parentFile) { dir ->
            val target = store.move(file, dir)
            if (target == null) {
                toast("No se pudo mover")
            } else {
                onFileMoved(file, target)
                refreshAll()
                toast("Movida a ${folderLabel(dir)}")
            }
        }
    }

    private fun confirmDelete(rec: Recording) {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar grabación?")
            .setMessage("\"${rec.name}\" se borrará del teléfono.")
            .setPositiveButton("Eliminar") { _, _ ->
                if (playing?.file == rec.file) closePlayer()
                rec.file.delete()
                refreshAll()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Diálogo al guardar: nombre + carpeta en un solo paso. */
    private fun promptSave(file: File) {
        var chosenDir = store.root
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(file.nameWithoutExtension)
            setSelectAllOnFocus(true)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val folderButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Carpeta: ${folderLabel(chosenDir)}"
            setIconResource(R.drawable.ic_folder)
            setOnClickListener {
                chooseFolder(chosenDir) { dir ->
                    chosenDir = dir
                    text = "Carpeta: ${folderLabel(dir)}"
                }
            }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(folderButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = pad / 2
            })
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Guardar grabación")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Guardar") { _, _ ->
                var current = file
                store.rename(current, input.text.toString())?.let { current = it }
                    ?: toast("Ese nombre ya existe; se dejó el automático")
                store.move(current, chosenDir)?.let { current = it }
                refreshAll()
            }
        showWithKeyboard(builder, input)
    }

    /** Muestra el diálogo con el cursor en el campo de texto y el teclado abierto. */
    private fun showWithKeyboard(builder: AlertDialog.Builder, input: EditText) {
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.setOnShowListener {
            input.requestFocus()
            input.selectAll()
            input.postDelayed({
                getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }
        dialog.show()
    }

    // ================= Carpetas =================

    private fun folderLabel(dir: File?): String =
        if (dir == null || store.isRoot(dir)) "Sin carpeta" else dir.name

    /** Lista para elegir carpeta, con opción de crear una nueva. */
    private fun chooseFolder(current: File?, onPick: (File) -> Unit) {
        val folders = store.folders()
        val names = arrayOf("Sin carpeta") + folders.map { it.name } + "＋ Nueva carpeta…"
        val checked = when {
            current == null || store.isRoot(current) -> 0
            else -> folders.indexOfFirst { it.absolutePath == current.absolutePath } + 1
        }
        AlertDialog.Builder(this)
            .setTitle("Elegir carpeta")
            .setSingleChoiceItems(names, checked) { d, which ->
                d.dismiss()
                when {
                    which == 0 -> onPick(store.root)
                    which <= folders.size -> onPick(folders[which - 1])
                    else -> promptNewFolder(onPick)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptNewFolder(onCreated: (File) -> Unit) {
        askText("Nueva carpeta", "", "Crear", hint = "Ej: Clases, Música, Ideas") { name ->
            val dir = store.createFolder(name)
            if (dir == null) toast("Ya existe una carpeta con ese nombre") else onCreated(dir)
        }
    }

    private fun showFolderMenu(dir: File, anchor: View) {
        if (store.isRoot(dir)) return
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Renombrar carpeta")
            menu.add(0, 2, 1, "Eliminar carpeta")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> renameFolder(dir)
                    2 -> confirmDeleteFolder(dir)
                }
                true
            }
            show()
        }
    }

    private fun renameFolder(dir: File) {
        askText("Renombrar carpeta", dir.name, "Renombrar") { name ->
            val target = store.renameFolder(dir, name)
            if (target == null) {
                toast("Ya existe una carpeta con ese nombre")
                return@askText
            }
            playing?.let { p ->
                if (p.file.parentFile?.absolutePath == dir.absolutePath) onFileMoved(p.file, File(target, p.file.name))
            }
            if (libFolder?.absolutePath == dir.absolutePath) libFolder = target
            refreshAll()
        }
    }

    private fun confirmDeleteFolder(dir: File) {
        val count = store.audiosIn(dir).size
        val msg = if (count == 0) "La carpeta está vacía."
        else "Sus $count audio(s) no se borran: pasan a \"Sin carpeta\"."
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar \"${dir.name}\"?")
            .setMessage(msg)
            .setPositiveButton("Eliminar") { _, _ ->
                val moved = store.deleteFolder(dir)
                moved.forEach { (old, new) -> onFileMoved(old, new) }
                if (libFolder?.absolutePath == dir.absolutePath) libFolder = null
                refreshAll()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ================= Biblioteca =================

    private fun openLibrary() {
        libFolder = null
        libraryPage.alpha = 0f
        libraryPage.visibility = View.VISIBLE
        libraryPage.animate().alpha(1f).setDuration(150).start()
        backFromLibrary.isEnabled = true
        refreshLibrary()
    }

    private fun closeLibrary() {
        backFromLibrary.isEnabled = false
        libraryPage.animate().alpha(0f).setDuration(150).withEndAction {
            libraryPage.visibility = View.GONE
        }.start()
    }

    private fun openFolder(dir: File) {
        libFolder = dir
        refreshLibrary()
    }

    private fun showFolders() {
        libFolder = null
        refreshLibrary()
    }

    private fun refreshLibrary() {
        val folder = libFolder
        if (folder == null || (!folder.exists())) {
            libFolder = null
            val folders = store.folders()
            val rootCount = store.audiosIn(store.root).size
            val items = listOf(FolderItem(store.root, "Sin carpeta", rootCount, true)) +
                folders.map { FolderItem(it, it.name, store.audiosIn(it).size, false) }
            val total = rootCount + items.drop(1).sumOf { it.count }
            tvLibTitle.text = "Biblioteca"
            tvLibSubtitle.text = "${folders.size} carpeta(s) · $total audio(s)"
            tvLibHint.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
            tvLibEmpty.visibility = View.GONE
            btnLibAction.visibility = View.VISIBLE
            btnLibAction.setImageResource(R.drawable.ic_add)
            btnLibAction.contentDescription = "Nueva carpeta"
            rvLibrary.adapter = foldersAdapter
            foldersAdapter.submit(items)
        } else {
            val files = store.audiosIn(folder)
            tvLibTitle.text = folderLabel(folder)
            tvLibSubtitle.text = if (files.size == 1) "1 audio" else "${files.size} audios"
            tvLibHint.visibility = View.GONE
            btnLibAction.visibility = if (store.isRoot(folder)) View.GONE else View.VISIBLE
            btnLibAction.setImageResource(R.drawable.ic_more_vert)
            btnLibAction.contentDescription = "Opciones de carpeta"
            rvLibrary.adapter = libAdapter
            libAdapter.setPlaying(playing?.file)
            loadInto(libAdapter, files) { empty -> tvLibEmpty.visibility = if (empty) View.VISIBLE else View.GONE }
        }
    }

    // ================= Reproducción =================

    /** Mantiene el reproductor al día si se renombra o mueve el audio que suena. */
    private fun onFileMoved(old: File, new: File) {
        val current = playing ?: return
        if (current.file.absolutePath != old.absolutePath) return
        playing = current.copy(file = new, name = new.nameWithoutExtension, folder = store.folderOf(new))
        tvPlayerTitle.text = new.nameWithoutExtension
        homeAdapter.setPlaying(new)
        libAdapter.setPlaying(new)
    }

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
        homeAdapter.setPlaying(rec.file)
        libAdapter.setPlaying(rec.file)
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
        homeAdapter.setPlaying(null)
        libAdapter.setPlaying(null)
    }

    private fun closePlayer() {
        releasePlayer()
        playerPanel.visibility = View.GONE
    }

    // ================= Utilidades =================

    private fun askText(title: String, initial: String, positive: String, hint: String? = null, onOk: (String) -> Unit) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(initial)
            setSelectAllOnFocus(true)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            if (hint != null) this.hint = hint
        }
        val container = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(positive) { _, _ ->
                val text = input.text.toString()
                if (text.isNotBlank()) onOk(text)
            }
            .setNegativeButton("Cancelar", null)
        showWithKeyboard(builder, input)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
