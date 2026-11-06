package dev.termdeck.shell

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.termdeck.SessionService
import dev.termdeck.priv.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStreamWriter

private val MUTED = Color(0xFF7D8596)
private val ERR = Color(0xFFF87171)

private fun notice(text: String, color: Color): AnnotatedString =
    buildAnnotatedString { withStyle(SpanStyle(color = color)) { append(text) } }

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

class ShellSession(val name: String, val mode: Mode) {
    // Scrollback: finished lines from the main screen.
    val lines = mutableStateListOf<AnnotatedString>()

    // The live screen: rows up to the cursor on the main screen, or every row in full-screen mode.
    var live by mutableStateOf<List<AnnotatedString>>(emptyList())
        private set

    var altMode by mutableStateOf(false)
        private set

    var isRunning by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val writes = Channel<String>(Channel.UNLIMITED)
    private val emulator = Emulator(80, 24)

    init {
        scope.launch {
            for (t in writes) {
                val w = writer ?: continue
                try {
                    w.write(t)
                    w.flush()
                } catch (_: Throwable) {
                }
            }
        }
    }

    val lastLine: String
        get() = live.lastOrNull { it.text.isNotBlank() }?.text
            ?: lines.lastOrNull { it.text.isNotBlank() }?.text
            ?: "empty"

    fun allText(): String = (lines.map { it.text } + live.map { it.text }).joinToString("\n")

    fun start(ctx: Context) {
        try {
            val p = openProcess(ctx)
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            isRunning = true
            scope.launch {
                try {
                    pump(p.inputStream)
                } catch (_: Throwable) {
                }
                withContext(Dispatchers.Main) {
                    isRunning = false
                    lines.add(notice("[session ended]", MUTED))
                }
            }
        } catch (e: Throwable) {
            lines.add(notice("error: ${describe(e)}", ERR))
        }
    }

    fun sendRaw(text: String) {
        writes.trySend(text)
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
            emulator.feed(buf, n)
            val committedNow = emulator.drainCommitted()
            val liveNow = emulator.liveLines()
            val altNow = emulator.altActive
            withContext(Dispatchers.Main) {
                lines.addAll(committedNow)
                while (lines.size > 3000) lines.removeAt(0)
                live = liveNow
                altMode = altNow
            }
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
