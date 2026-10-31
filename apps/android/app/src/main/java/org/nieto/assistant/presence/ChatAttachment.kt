package org.nieto.assistant.presence

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Only explicitly selected documents are read. Bytes are not saved in preferences or telemetry. */
data class ChatAttachment(val name: String, val mimeType: String, val dataBase64: String, val byteSize: Int) {
    fun wire() = JSONObject().put("name", name).put("mimeType", mimeType).put("dataBase64", dataBase64)
    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 4 * 1024 * 1024
        const val MAX_COUNT = 3
        fun read(context: Context, uri: Uri): ChatAttachment {
            require(uri.scheme == "content") { "Elegí el archivo desde el selector de Android." }
            val resolver = context.contentResolver
            var name = "Archivo"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0).orEmpty().replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(120).ifBlank { "Archivo" }
                    if (!cursor.isNull(1)) require(cursor.getLong(1) <= MAX_BYTES) { "Cada archivo puede pesar hasta 2 MB." }
                }
            }
            val output = ByteArrayOutputStream()
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= MAX_BYTES) { "Cada archivo puede pesar hasta 2 MB." }; output.write(buffer, 0, count) }
            } ?: error("No pude abrir ese archivo. Elegilo nuevamente.")
            val bytes = output.toByteArray()
            val mime = when {
                bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
                bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() -> "image/jpeg"
                bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
                bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-" -> "application/pdf"
                else -> error("Elegí una captura o imagen PNG, JPG, WebP, o un PDF.")
            }
            return ChatAttachment(name, mime, Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size)
        }
    }
}
