package dev.herdroid.ui.components

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** One composer's upload transaction; failed sends retain local files and completed uploads. */
class AttachmentUploads {
    private val lock = Mutex()
    private var destination: Any? = null
    private data class Uploaded(val size: Long, val modified: Long, val path: String)
    private val uploaded = mutableMapOf<String, Uploaded>()

    suspend fun upload(source: Any, files: List<File>, send: suspend (File) -> String): List<String> = lock.withLock {
        if (destination !== source) { uploaded.clear(); destination = source }
        uploaded.keys.retainAll(files.map { it.absolutePath }.toSet())
        files.map { file ->
            val size = file.length()
            val modified = file.lastModified()
            uploaded[file.absolutePath]?.takeIf { it.size == size && it.modified == modified }?.path
                ?: send(file).also { uploaded[file.absolutePath] = Uploaded(size, modified, it) }
        }
    }
}
