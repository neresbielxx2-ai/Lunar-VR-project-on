package com.agusvr.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.agusvr.util.Logx
import com.agusvr.util.extOf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Sandboxed file operations used by the Agus file manager, store and library
 * (module: AgusStorage). All operations stay inside the app sandbox; imports
 * happen through SAF Uris and are copied — never referenced in place.
 */
object FileOps {

    private const val MAX_ZIP_ENTRIES = 8_000
    private const val MAX_ZIP_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_TEXT_PREVIEW = 512 * 1024

    class FileOpException(message: String, cause: Throwable? = null) : IOException(message, cause)

    // ------------------------------------------------------------------
    // Basic operations
    // ------------------------------------------------------------------

    fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        src.inputStream().use { input -> FileOutputStream(dst).use { output -> input.copyTo(output) } }
    }

    fun copyDir(src: File, dst: File) {
        if (!src.isDirectory) throw FileOpException("Origem não é uma pasta: ${src.name}")
        src.walkTopDown().forEach { f ->
            val rel = f.relativeTo(src).path
            val target = File(dst, rel)
            if (f.isDirectory) target.mkdirs() else copyFile(f, target)
        }
    }

    fun moveFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            copyFile(src, dst)
            src.delete()
        }
    }

    fun moveDir(src: File, dst: File) {
        if (dst.absolutePath.startsWith(src.absolutePath + File.separator)) {
            throw FileOpException("Não é possível mover uma pasta para dentro dela mesma.")
        }
        if (!src.renameTo(dst)) {
            copyDir(src, dst)
            src.deleteRecursively()
        }
    }

    fun delete(target: File): Boolean =
        if (target.isDirectory) target.deleteRecursively() else target.delete()

    fun rename(target: File, newName: String): File {
        val clean = sanitizeName(newName)
        if (clean.isEmpty()) throw FileOpException("Nome inválido.")
        val dst = File(target.parentFile ?: AgusPaths.root, clean)
        if (dst.exists()) throw FileOpException("Já existe um item com o nome \"$clean\".")
        if (!target.renameTo(dst)) throw FileOpException("Não foi possível renomear (permissão ou item em uso).")
        return dst
    }

    fun sanitizeName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(120)

    fun createFolder(parent: File, name: String): File {
        val clean = sanitizeName(name)
        if (clean.isEmpty()) throw FileOpException("Nome inválido.")
        val dir = File(parent, clean)
        if (dir.exists()) throw FileOpException("A pasta \"$clean\" já existe.")
        if (!dir.mkdirs()) throw FileOpException("Não foi possível criar a pasta.")
        return dir
    }

    // ------------------------------------------------------------------
    // SAF import (copy an external Uri into the sandbox)
    // ------------------------------------------------------------------

    fun queryDisplayName(context: Context, uri: Uri): String {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        } catch (t: Throwable) {
            Logx.w("FileOps", "display name query failed", t)
        }
        if (name.isNullOrEmpty()) name = uri.lastPathSegment?.substringAfterLast('/')
        return sanitizeName(name ?: "importado")
    }

    fun querySize(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
            }
        } catch (_: Throwable) {
        }
        return -1L
    }

    /** Copies [uri] into [destDir] with a unique, sanitized name. Returns the new file. */
    fun importUri(context: Context, uri: Uri, destDir: File): File {
        val name = queryDisplayName(context, uri)
        val dst = AgusPaths.unique(AgusPaths.ensure(destDir), name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dst).use { output -> input.copyTo(output) }
        } ?: throw FileOpException("Não foi possível ler o arquivo selecionado.")
        if (dst.length() == 0L) {
            dst.delete()
            throw FileOpException("O arquivo selecionado está vazio ou inacessível.")
        }
        return dst
    }

    // ------------------------------------------------------------------
    // ZIP handling (guarded against zip-slip and zip bombs)
    // ------------------------------------------------------------------

    /**
     * Extracts [zip] into [destDir]. Returns the detected main HTML file when
     * the archive is a runnable web project, or null when there is no HTML.
     * Throws [FileOpException] on corrupt/malicious archives.
     */
    fun extractZip(zip: File, destDir: File): File? {
        AgusPaths.ensure(destDir)
        val destCanonical = destDir.canonicalPath
        var entries = 0
        var totalBytes = 0L
        val htmlCandidates = mutableListOf<File>()

        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entries++
                if (entries > MAX_ZIP_ENTRIES) throw FileOpException("ZIP com arquivos demais (> $MAX_ZIP_ENTRIES).")
                val rawName = entry.name
                if (rawName.contains("..") || rawName.startsWith("/")) {
                    throw FileOpException("ZIP contém caminho inseguro: $rawName")
                }
                val out = File(destDir, rawName)
                if (!out.canonicalPath.startsWith(destCanonical + File.separator) &&
                    out.canonicalPath != destCanonical
                ) {
                    throw FileOpException("ZIP contém caminho inseguro: $rawName")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            totalBytes += n
                            if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                                throw FileOpException("ZIP grande demais (limite ${MAX_ZIP_TOTAL_BYTES / (1024 * 1024)} MB descompactado).")
                            }
                            fos.write(buf, 0, n)
                        }
                    }
                    val ext = extOf(out.name)
                    if (ext == "html" || ext == "htm") htmlCandidates += out
                }
                zis.closeEntry()
            }
        }
        if (entries == 0) throw FileOpException("O ZIP está vazio ou corrompido.")
        // Prefer index.html at the shallowest directory level.
        return htmlCandidates.minByOrNull { f ->
            val depth = f.relativeTo(destDir).path.count { it == '/' }
            val nameScore = when {
                f.name.equals("index.html", true) -> 0
                f.name.equals("main.html", true) || f.name.equals("start.html", true) -> 1
                else -> 2
            }
            nameScore * 100 + depth
        }
    }

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    fun readTextPreview(file: File): String = try {
        if (file.length() > MAX_TEXT_PREVIEW) {
            file.inputStream().use { input ->
                val buf = ByteArray(MAX_TEXT_PREVIEW)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8) + "\n\n… (arquivo truncado para visualização)"
            }
        } else {
            file.readText(Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        "Não foi possível ler o arquivo: ${t.message}"
    }

    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    fun extensionLabel(file: File): String = extOf(file.name).uppercase().ifEmpty { "—" }
}
