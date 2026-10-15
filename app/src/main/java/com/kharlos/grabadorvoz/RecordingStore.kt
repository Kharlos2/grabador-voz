package com.kharlos.grabadorvoz

import java.io.File

/**
 * Organización de los audios en disco:
 * recordings/            -> "Sin carpeta"
 * recordings/<Carpeta>/  -> cada carpeta creada por el usuario
 */
class RecordingStore(val root: File) {

    init {
        root.mkdirs()
    }

    fun folders(): List<File> =
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun audiosIn(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.extension == "m4a" } ?: emptyList()

    fun allAudios(): List<File> = audiosIn(root) + folders().flatMap { audiosIn(it) }

    /** Nombre de la carpeta del audio, o null si está en "Sin carpeta". */
    fun folderOf(file: File): String? =
        if (file.parentFile?.absolutePath == root.absolutePath) null else file.parentFile?.name

    fun isRoot(dir: File) = dir.absolutePath == root.absolutePath

    fun createFolder(name: String): File? {
        val clean = clean(name)
        if (clean.isEmpty()) return null
        val dir = File(root, clean)
        if (dir.exists()) return null
        return if (dir.mkdir()) dir else null
    }

    fun renameFolder(dir: File, name: String): File? {
        val clean = clean(name)
        if (clean.isEmpty()) return null
        val target = File(root, clean)
        if (target.exists()) return null
        return if (dir.renameTo(target)) target else null
    }

    /** Pasa los audios a "Sin carpeta" y borra la carpeta. Devuelve (viejo -> nuevo) de cada audio. */
    fun deleteFolder(dir: File): Map<File, File> {
        val moved = HashMap<File, File>()
        for (f in audiosIn(dir)) {
            move(f, root)?.let { moved[f] = it }
        }
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
        return moved
    }

    fun move(file: File, dir: File): File? {
        if (file.parentFile?.absolutePath == dir.absolutePath) return file
        val target = unique(dir, file.nameWithoutExtension, file.extension)
        return if (file.renameTo(target)) target else null
    }

    fun rename(file: File, name: String): File? {
        val clean = clean(name)
        if (clean.isEmpty()) return null
        if (clean == file.nameWithoutExtension) return file
        val target = File(file.parentFile, "$clean.${file.extension}")
        if (target.exists()) return null
        return if (file.renameTo(target)) target else null
    }

    private fun unique(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var i = 2
        while (f.exists()) {
            f = File(dir, "$base ($i).$ext")
            i++
        }
        return f
    }

    companion object {
        fun clean(name: String): String =
            name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").trim('.', ' ')
    }
}
