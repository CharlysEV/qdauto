package dev.qdauto.app.log

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.qdauto.app.util.Clock
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * "Exportar log": copia a Descargas/QDAuto un `.txt` con un resumen del estado y todos los ficheros de log
 * (sesión actual y anteriores), mediante MediaStore (sin permisos de almacenamiento en Android 10+).
 */
object LogExport {
    class Exported(val uri: Uri, val displayName: String, val bytes: Long, val files: Int)

    const val RELATIVE_DIR = "QDAuto"
    private const val MAX_TOTAL_BYTES = 150L * 1024 * 1024

    /** Bloquea: llamar fuera del hilo principal. */
    @Throws(IOException::class)
    fun export(context: Context, summary: String): Exported {
        AppLog.flush(3_000)
        val files = logFiles()
        val name = "qdauto-log-${Clock.stamp()}.txt"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + RELATIVE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore no creó el fichero en Descargas")
        var written = 0L
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("no se pudo abrir $uri")
            out.use { o ->
                written += writeText(o, summary)
                for (f in files) {
                    written += writeText(o, "\n\n===== ${f.name} (${f.length()} B) =====\n")
                    f.inputStream().use { input -> written += input.copyTo(o, 64 * 1024) }
                }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw if (e is IOException) e else IOException("error exportando: $e", e)
        }
        AppLog.i("QD/Export", "log exportado a Descargas/$RELATIVE_DIR/$name: ${files.size} ficheros, $written B")
        return Exported(uri, name, written, files.size)
    }

    /** Hoja de compartir para el fichero exportado. */
    fun shareIntent(exported: Exported): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, exported.uri)
            putExtra(Intent.EXTRA_SUBJECT, "QDAuto: ${exported.displayName}")
            clipData = ClipData.newRawUri(exported.displayName, exported.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Compartir log de QDAuto")
    }

    /** Ficheros de log, del más antiguo al más reciente, sin pasar de [MAX_TOTAL_BYTES] (se quitan los viejos). */
    private fun logFiles(): List<File> {
        val all = AppLog.directory()?.listFiles { f -> f.isFile && f.name.startsWith("qdauto-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        val chosen = ArrayList<File>()
        var total = 0L
        for (f in all) {
            if (chosen.isNotEmpty() && total + f.length() > MAX_TOTAL_BYTES) break
            chosen += f
            total += f.length()
        }
        return chosen.reversed()
    }

    private fun writeText(out: OutputStream, text: String): Long {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.write(bytes)
        return bytes.size.toLong()
    }
}
