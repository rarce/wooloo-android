package dev.herdroid.ui.hosts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owned by one screen/dialog; submit and callbacks run on its UI scope. */
internal class BackgroundEdit {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun <T> submit(scope: CoroutineScope, failure: String, work: () -> T, success: (T) -> Unit = {}) {
        // Set before launch: two taps in the same frame cannot enqueue two mutations.
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { work() }
                success(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never display private-key parser payloads or vault exceptions in the UI.
                error = failure
            } finally {
                busy = false
            }
        }
    }
}
