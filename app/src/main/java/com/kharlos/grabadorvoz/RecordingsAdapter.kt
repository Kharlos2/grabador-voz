package com.kharlos.grabadorvoz

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recording(
    val file: File,
    val name: String,
    val folder: String?,
    val date: Long,
    val durationMs: Long,
    val sizeBytes: Long
)

class RecordingsAdapter(
    private val showFolder: Boolean,
    private val onClick: (Recording) -> Unit,
    private val onMore: (Recording, View) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.VH>() {

    /** Evita que una carga vieja pise una más nueva. */
    var generation = 0

    private var items: List<Recording> = emptyList()
    private var playingFile: File? = null
    private val dateFormat = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivIcon)
        val name: TextView = view.findViewById(R.id.tvName)
        val details: TextView = view.findViewById(R.id.tvDetails)
        val more: ImageButton = view.findViewById(R.id.btnMore)
    }

    fun submit(list: List<Recording>) {
        items = list
        notifyDataSetChanged()
    }

    fun setPlaying(file: File?) {
        playingFile = file
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rec = items[position]
        val isCurrent = rec.file == playingFile
        holder.name.text = rec.name
        holder.name.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
        val folder = if (showFolder && rec.folder != null) "${rec.folder}  ·  " else ""
        holder.details.text = "$folder${formatTime(rec.durationMs)}  ·  ${dateFormat.format(Date(rec.date))}  ·  ${formatSize(rec.sizeBytes)}"
        holder.icon.setImageResource(if (isCurrent) R.drawable.ic_equalizer else R.drawable.ic_play)
        holder.itemView.setOnClickListener { onClick(rec) }
        holder.more.setOnClickListener { onMore(rec, it) }
    }
}

data class FolderItem(val dir: File, val name: String, val count: Int, val isRoot: Boolean)

class FoldersAdapter(
    private val onClick: (FolderItem) -> Unit,
    private val onMore: (FolderItem, View) -> Unit
) : RecyclerView.Adapter<FoldersAdapter.VH>() {

    private var items: List<FolderItem> = emptyList()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivFolderIcon)
        val name: TextView = view.findViewById(R.id.tvFolderName)
        val count: TextView = view.findViewById(R.id.tvFolderCount)
        val more: ImageButton = view.findViewById(R.id.btnFolderMore)
    }

    fun submit(list: List<FolderItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.count.text = when (item.count) {
            0 -> "Vacía"
            1 -> "1 audio"
            else -> "${item.count} audios"
        }
        holder.icon.setImageResource(if (item.isRoot) R.drawable.ic_inbox else R.drawable.ic_folder)
        holder.more.visibility = if (item.isRoot) View.INVISIBLE else View.VISIBLE
        holder.itemView.setOnClickListener { onClick(item) }
        holder.more.setOnClickListener { onMore(item, it) }
    }
}

fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatSize(bytes: Long): String =
    if (bytes < 1024 * 1024) "${bytes / 1024} KB"
    else String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
