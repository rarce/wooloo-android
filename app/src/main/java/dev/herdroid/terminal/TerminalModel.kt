package dev.herdroid.terminal

/**
 * Thread-safe owner of a [TerminalEmulator]: frames are applied on an IO thread while the
 * view reads the grid on the UI thread.
 */
class TerminalModel {
    val lock = Any()
    val emulator = TerminalEmulator()

    @Volatile
    var listener: (() -> Unit)? = null

    fun applyFrame(width: Int, height: Int, full: Boolean, bytes: ByteArray) {
        synchronized(lock) {
            if (width > 0 && height > 0 && (width != emulator.cols || height != emulator.rows)) {
                emulator.resize(width, height)
            }
            if (full) emulator.reset()
            emulator.feed(bytes)
        }
        listener?.invoke()
    }

    fun screenText(): String = synchronized(lock) { emulator.screenText() }
    fun linkAt(x: Int, y: Int): String? = synchronized(lock) { emulator.linkAt(x, y) }
    fun rowText(y: Int): String = synchronized(lock) {
        if (y in 0 until emulator.rows) emulator.rowText(y) else ""
    }
}
