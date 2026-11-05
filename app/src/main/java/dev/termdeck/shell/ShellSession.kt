package dev.termdeck.shell

import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.termdeck.priv.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStreamWriter
import java.lang.reflect.InvocationTargetException

// One long-lived sh process per session. Not a PTY: full-screen programs will not work.
class ShellSession(val name: String, val mode: Mode) {
    val lines = mutableStateListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    val lastLine: String
        get() = lines.lastOrNull { it.isNotBlank() } ?: "empty"

    var isRunning by mutableStateOf(false)
        private set

    fun start() {
        try {
            val p = openProcess()
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            isRunning = true
            scope.launch {
                pump(p.inputStream)
                scope.launch(Dispatchers.Main) { isRunning = false }
                post("[shell exited]")
            }
            scope.launch { pump(p.errorStream) }
        } catch (e: Throwable) {
            post("error: ${describe(e)}")
        }
    }

    fun send(cmd: String) {
        post("$ $cmd")
        scope.launch {
            val w = writer
            if (w == null) {
                post("error: shell not running. Tap + to start a new session.")
                return@launch
            }
            try {
                w.write(cmd)
                w.write("\n")
                w.flush()
            } catch (e: Throwable) {
                post("error: ${describe(e)}")
            }
        }
    }

    fun close() {
        process?.destroy()
        isRunning = false
        scope.cancel()
    }

    private fun pump(stream: InputStream) {
        try {
            stream.bufferedReader().forEachLine { post(it) }
        } catch (e: Throwable) {
            post("read error: ${describe(e)}")
        }
    }

    private fun post(line: String) {
        scope.launch(Dispatchers.Main) { lines.add(line) }
    }

    private fun describe(e: Throwable): String {
        val cause = (e as? InvocationTargetException)?.targetException ?: e
        return cause.message ?: cause.javaClass.simpleName
    }

    private fun openProcess(): Process {
        if (mode == Mode.SHIZUKU) {
            if (!Shizuku.pingBinder()) {
                throw IllegalStateException("Shizuku is not running. Start it, then tap + for a new session.")
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                throw IllegalStateException("Shizuku not granted. Open Setup, tap Grant Shizuku, then tap + for a new session.")
            }
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            return m.invoke(null, arrayOf("sh"), null, null) as Process
        }
        return ProcessBuilder("sh").start()
    }
}
