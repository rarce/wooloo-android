package dev.herdroid.herdr

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream

/** Bound memory even when a peer never sends a newline. */
internal fun BufferedReader.readBoundedLine(maxChars: Int): String? {
    val line = StringBuilder()
    while (true) {
        val char = read()
        if (char == -1) return line.takeIf { it.isNotEmpty() }?.toString()
        if (char == '\n'.code) return line.toString().removeSuffix("\r")
        if (line.length >= maxChars) throw IOException("Remote record exceeds $maxChars characters")
        line.append(char.toChar())
    }
}

internal fun InputStream.readAtMostBytes(limit: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (result.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - result.size()))
        if (count < 0) break
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}
