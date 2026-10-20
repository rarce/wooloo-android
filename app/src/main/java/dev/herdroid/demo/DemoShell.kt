package dev.herdroid.demo

import dev.herdroid.ssh.CommandChannel
import dev.herdroid.ssh.RemoteShell
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * The demo machine: answers the same command lines an SSH server would, from a [DemoHerdr]
 * running on the phone. Nothing leaves the device. Each connection starts a fresh demo.
 */
class DemoShell internal constructor(private val herdr: DemoHerdr) : RemoteShell {
    constructor() : this(DemoHerdr())

    @Volatile private var open = true
    override val isConnected: Boolean get() = open

    override suspend fun exec(command: String): CommandChannel {
        check(open) { "The demo machine stopped" }
        return herdr.exec(command)
    }

    override fun close() {
        open = false
        herdr.stop()
    }
}

/** One command on the demo machine. The client reads [stdout]; what it writes reaches [onInput] line by line. */
internal class DemoChannel : CommandChannel {
    private val output = QueueInputStream()
    @Volatile var onInput: (String) -> Unit = {}
    /** The client closed stdin, as `stdin.use { … }` does for uploads. */
    @Volatile var onInputEnd: () -> Unit = {}
    @Volatile var onClose: () -> Unit = {}
    @Volatile private var open = true

    override val stdin: OutputStream = LineSink({ onInput(it) }, { onInputEnd() })
    override val stdout: InputStream get() = output
    override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
    override val isOpen: Boolean get() = open

    fun send(line: String) = write((line + "\n").toByteArray(Charsets.UTF_8))
    fun write(bytes: ByteArray) = output.offer(bytes)
    /** The remote command exited: the client reads to end of stream. */
    fun end() = output.finish()

    override fun close() {
        if (!open) return
        open = false
        output.finish()
        onClose()
    }
}

/** A blocking stream fed by another thread; reads react to interruption like sshj's. */
internal class QueueInputStream : InputStream() {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var current = ByteArray(0)
    private var position = 0
    @Volatile private var finished = false

    fun offer(bytes: ByteArray) {
        if (!finished && bytes.isNotEmpty()) queue.put(bytes)
    }

    fun finish() {
        if (finished) return
        finished = true
        queue.put(END)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (current === END) return -1
        if (position >= current.size) {
            current = try { queue.take() } catch (e: InterruptedException) { throw InterruptedIOException() }
            position = 0
            if (current === END) return -1
        }
        val count = minOf(len, current.size - position)
        System.arraycopy(current, position, b, off, count)
        position += count
        return count
    }

    override fun available(): Int = if (current === END) 0 else current.size - position

    override fun close() = finish()

    private companion object {
        val END = ByteArray(0)
    }
}

/** Splits what the client writes into UTF-8 lines. */
internal class LineSink(private val onLine: (String) -> Unit, private val onEnd: () -> Unit) : OutputStream() {
    private val buffer = ByteArrayOutputStream()
    private var closed = false

    @Synchronized override fun write(b: Int) {
        if (closed) throw IOException("Stream closed")
        if (b == '\n'.code) {
            val line = buffer.toString("UTF-8")
            buffer.reset()
            onLine(line)
        } else {
            // Uploads are binary and never read as lines; don't let them grow a line forever.
            if (buffer.size() < MAX_LINE) buffer.write(b)
        }
    }

    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(b[i].toInt() and 0xff)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        onEnd()
    }

    private companion object {
        const val MAX_LINE = 1_048_576
    }
}
