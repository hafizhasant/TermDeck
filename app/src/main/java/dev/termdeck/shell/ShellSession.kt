package dev.termdeck.shell

import androidx.compose.runtime.mutableStateListOf
import dev.termdeck.priv.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.BufferedWriter
import java.io.OutputStreamWriter

// One long-lived sh process per session. Commands are written to its stdin,
// and output from stdout and stderr is appended to lines.
// This is not a PTY, so full-screen programs (vim, top) will not work.
class ShellSession(val name: String, val mode: Mode) {
    val lines = mutableStateListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    val lastLine: String
        get() = lines.lastOrNull { it.isNotBlank() } ?: "empty"

    val isRunning: Boolean
        get() = process?.isAlive == true

    fun start() {
        try {
            val p = openProcess()
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            scope.launch {
                p.inputStream.bufferedReader().forEachLine { post(it) }
                post("[shell exited]")
            }
            scope.launch {
                p.errorStream.bufferedReader().forEachLine { post(it) }
            }
        } catch (e: Throwable) {
            post("error: ${e.message}")
        }
    }

    fun send(cmd: String) {
        post("$ $cmd")
        scope.launch {
            val w = writer
            if (w == null) {
                post("error: shell not running")
                return@launch
            }
            try {
                w.write(cmd)
                w.write("\n")
                w.flush()
            } catch (e: Throwable) {
                post("error: ${e.message}")
            }
        }
    }

    fun close() {
        process?.destroy()
        scope.cancel()
    }

    private fun post(line: String) {
        scope.launch(Dispatchers.Main) { lines.add(line) }
    }

    private fun openProcess(): Process = when (mode) {
        Mode.SHIZUKU -> {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            m.invoke(null, arrayOf("sh"), null, null) as Process
        }
        else -> ProcessBuilder("sh").start()
    }
}
