package org.nieto.assistant.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import java.io.IOException

/** Installs trusted, bundled Spanish assets in app-private storage; never downloads at runtime. */
internal object OfflineVoiceModel {
    const val ASSET_DIRECTORY = "vosk-model-small-es-0.42"
    private val installMutex = Mutex()

    suspend fun load(context: Context): Model {
        var candidate: Model? = null
        try {
            return withContext(Dispatchers.IO) {
                val directory = installMutex.withLock { install(context) }
                currentCoroutineContext().ensureActive()
                Model(directory.absolutePath).also { candidate = it }
            }
        } catch (error: Throwable) {
            // withContext may reject delivery after construction when its caller is cancelled.
            candidate?.close()
            throw error
        }
    }

    private suspend fun install(context: Context): File {
        val assets = context.assets
        val version = runCatching {
            assets.open("$ASSET_DIRECTORY/uuid").bufferedReader().use { it.readLine() }.trim()
        }.getOrDefault(ASSET_DIRECTORY)
        val root = File(context.filesDir, "wake-models")
        val directory = File(root, ASSET_DIRECTORY)
        val marker = File(directory, ".asset-version")
        if (runCatching { marker.readText() }.getOrNull() != version || !modelFilesPresent(directory)) {
            val pending = File(root, "$ASSET_DIRECTORY.pending")
            if (pending.exists() && !pending.deleteRecursively()) throw IOException("Could not replace local model")
            copyAssets(context, ASSET_DIRECTORY, pending)
            if (!modelFilesPresent(pending)) throw IOException("Incomplete local model assets")
            File(pending, ".asset-version").writeText(version)
            if (directory.exists() && !directory.deleteRecursively()) throw IOException("Could not update local model")
            if (!pending.renameTo(directory)) throw IOException("Could not install local model")
        }
        return directory
    }

    private suspend fun copyAssets(context: Context, source: String, destination: File) {
        currentCoroutineContext().ensureActive()
        val children = context.assets.list(source) ?: throw IOException("Missing local model")
        if (children.isEmpty()) {
            destination.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Could not create local model directory") }
            context.assets.open(source).use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            } }
        } else {
            if (!destination.exists() && !destination.mkdirs()) throw IOException("Could not create local model directory")
            children.forEach { child -> copyAssets(context, "$source/$child", File(destination, child)) }
        }
    }

    private fun modelFilesPresent(directory: File) = listOf("am/final.mdl", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst")
        .all { File(directory, it).isFile }
}
