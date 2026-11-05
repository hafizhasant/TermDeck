package dev.termdeck.shell

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.termdeck.SessionService
import dev.termdeck.priv.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStreamWriter

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\u001B[()][0-9A-Za-z]|\u001B[=>]")

// Process-wide list, so sessions survive the Activity being recreated.
object SessionStore {
    val sessions = mutableStateListOf<ShellSession>()
    private var nextId = 1

    fun create(ctx: Context, mode: Mode): ShellSession {
        val s = ShellSession("session-$nextId", mode)
        nextId += 1
        sessions.add(s)
        s.start(ctx)
        SessionService.sync(ctx, sessions.size)
        return s
    }

    fun close(ctx: Context, s: ShellSession) {
        s.close()
        sessions.remove(s)
        SessionService.sync(ctx, sessions.size)
    }
}

// Runs the native PTY helper, so the shell has a real terminal.
class ShellSession(val name: String, val mode: Mode) {
    val lines = mutableStateListOf<String>()
    var isRunning by mutableStateOf(false)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var partial = ""
    private var pendingShown = false

    val lastLine: String
        get() = lines.lastOrNull { it.isNotBlank() } ?: "empty"

    fun start(ctx: Context) {
        try {
            val p = openProcess(ctx)
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            isRunning = true
            scope.launch {
                try {
                    pump(p.inputStream)
                } catch (e: Throwable) {
                    // stream closed
                }
                withContext(Dispatchers.Main) {
                    isRunning = false
                    lines.add("[session ended]")
                }
            }
        } catch (e: Throwable) {
            lines.add("error: ${describe(e)}")
        }
    }

    fun send(cmd: String) {
        scope.launch {
            val w = writer
            if (w == null || !isRunning) {
                withContext(Dispatchers.Main) { lines.add("error: session not running") }
                return@launch
            }
            try {
                w.write(cmd)
                w.write("\n")
                w.flush()
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { lines.add("error: ${describe(e)}") }
            }
        }
    }

    fun sendRaw(text: String) {
        scope.launch {
            val w = writer
            if (w == null || !isRunning) return@launch
            try {
                w.write(text)
                w.flush()
            } catch (e: Throwable) {
            }
        }
    }

    fun close() {
        try { writer?.close() } catch (_: Throwable) {}
        try { process?.destroy() } catch (_: Throwable) {}
        isRunning = false
        scope.cancel()
    }

    private suspend fun pump(stream: InputStream) {
        val reader = stream.reader(Charsets.UTF_8)
        val buf = CharArray(4096)
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            feed(String(buf, 0, n).replace(ANSI, ""))
        }
    }

    private suspend fun feed(text: String) {
        val done = mutableListOf<String>()
        for (ch in text) {
            when (ch) {
                '\n' -> { done.add(partial); partial = "" }
                '\r' -> {}
                '\b', '\u007F' -> if (partial.isNotEmpty()) partial = partial.dropLast(1)
                else -> partial += ch
            }
        }
        val pending = partial
        withContext(Dispatchers.Main) {
            if (pendingShown) {
                lines.removeAt(lines.lastIndex)
                pendingShown = false
            }
            lines.addAll(done)
            if (pending.isNotEmpty()) {
                lines.add(pending)
                pendingShown = true
            }
            while (lines.size > 3000) lines.removeAt(0)
        }
    }

    private fun openProcess(ctx: Context): Process {
        val helper = ctx.applicationInfo.nativeLibraryDir + "/libtermdeckpty.so"
        if (mode != Mode.SHIZUKU) return ProcessBuilder(helper).start()
        if (!Shizuku.pingBinder()) throw IllegalStateException("Shizuku is not running.")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("Shizuku not granted. Open Setup and tap Grant Shizuku.")
        }
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh", "-c", helper), null, null) as Process
    }

    private fun describe(e: Throwable): String {
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        return cause.message ?: cause.javaClass.simpleName
    }
}
