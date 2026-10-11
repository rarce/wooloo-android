package dev.herdroid.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal val storeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/** Callers serialize the entire state transaction, including this commit. */
internal fun File.writeAtomically(text: String) {
    val directory = absoluteFile.parentFile!!
    directory.mkdirs()
    val tmp = File.createTempFile("$name-", ".tmp", directory)
    try {
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        // Never fall back to truncating the committed file. Unsupported atomic
        // rename is an error, leaving the old state intact.
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        tmp.delete()
    }
}

/** Preserve invalid JSON before allowing a new store to be written. IO failures propagate. */
internal fun <T> File.readStore(empty: T, decode: (String) -> T): T {
    if (!exists()) return empty
    val text = readText()
    return try {
        decode(text)
    } catch (e: SerializationException) {
        val recovery = File(absoluteFile.parentFile, "$name.corrupt-${UUID.randomUUID()}")
        Files.move(toPath(), recovery.toPath(), StandardCopyOption.ATOMIC_MOVE)
        empty
    }
}
