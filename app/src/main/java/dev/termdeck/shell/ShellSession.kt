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
import androidx.compose.ui.text.font.FontWeight
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

private val TEXT = Color(0xFFE6E9EF)
private val ACCENT = Color(0xFF5EEAD4)
private val MUTED = Color(0xFF7D8596)
private val ERR = Color(0xFFF87171)

// Standard 16 ANSI colors, tuned to the app's palette.
private val ANSI16 = arrayOf(
    Color(0xFF4B5563), Color(0xFFF87171), Color(0xFF86EFAC), Color(0xFFFBBF24),
    Color(0xFF60A5FA), Color(0xFFA78BFA), Color(0xFF5EEAD4), Color(0xFFE6E9EF),
    Color(0xFF7D8596), Color(0xFFFCA5A5), Color(0xFFBBF7D0), Color(0xFFFDE68A),
    Color(0xFF93C5FD), Color(0xFFC4B5FD), Color(0xFF99F6E4), Color(0xFFFFFFFF)
)

// Matches a shell prompt such as "OP612DL1:/ $ " at the start of a line.
private val PROMPT = Regex("""^[^$\n]{1,40}\$ """)

private data class Seg(val text: String, val color: Color?, val bold: Boolean)

private fun render(segs: List<Seg>, markPrompt: Boolean): AnnotatedString {
    val plain = segs.joinToString("") { it.text }
    val promptEnd = if (markPrompt) (PROMPT.find(plain)?.range?.last ?: -1) + 1 else 0
    return buildAnnotatedString {
        for (seg in segs) {
            withStyle(SpanStyle(color = seg.color ?: TEXT, fontWeight = if (seg.bold) FontWeight.Bold else null)) {
                append(seg.text)
            }
        }
        if (promptEnd > 0) {
            addStyle(SpanStyle(color = ACCENT, fontWeight = FontWeight.SemiBold), 0, promptEnd)
        }
    }
}

private fun color256(n: Int): Color {
    if (n !in 0..255) return TEXT
    if (n < 16) return ANSI16[n]
    if (n < 232) {
        val c = n - 16
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        return Color(levels[c / 36], levels[(c / 6) % 6], levels[c % 6])
    }
    val g = 8 + (n - 232) * 10
    return Color(g, g, g)
}

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

// Runs the native PTY helper. Output is parsed for ANSI color codes.
class ShellSession(val name: String, val mode: Mode) {
    val lines = mutableStateListOf<AnnotatedString>()
    var isRunning by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val writes = Channel<String>(Channel.UNLIMITED)

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

    // Parser state. It persists across reads because escape codes can split between chunks.
    private var segs = mutableListOf<Seg>()
    private var pendingShown = false
    private var fg: Color? = null
    private var bold = false
    private var esc = 0 // 0 normal, 1 after ESC, 2 CSI, 3 OSC, 4 skip one char
    private val escBuf = StringBuilder()

    val lastLine: String
        get() = lines.lastOrNull { it.text.isNotBlank() }?.text ?: "empty"

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
                    lines.add(render(listOf(Seg("[session ended]", MUTED, false)), false))
                }
            }
        } catch (e: Throwable) {
            lines.add(render(listOf(Seg("error: ${describe(e)}", ERR, false)), false))
        }
    }

    fun sendRaw(text: String) {
        writes.trySend(text)
    }

    fun send(cmd: String) {
        sendRaw("$cmd\n")
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
            val done = mutableListOf<AnnotatedString>()
            parse(buf, n, done)
            val partial = if (segs.isNotEmpty()) render(segs.toList(), true) else null
            withContext(Dispatchers.Main) {
                if (pendingShown) {
                    lines.removeAt(lines.lastIndex)
                    pendingShown = false
                }
                lines.addAll(done)
                if (partial != null) {
                    lines.add(partial)
                    pendingShown = true
                }
                while (lines.size > 3000) lines.removeAt(0)
            }
        }
    }

    private fun parse(chunk: CharArray, n: Int, done: MutableList<AnnotatedString>) {
        for (i in 0 until n) {
            val ch = chunk[i]
            when (esc) {
                1 -> esc = when (ch) {
                    '[' -> { escBuf.clear(); 2 }
                    ']' -> 3
                    '(', ')' -> 4
                    else -> 0
                }
                2 -> {
                    if (ch in '\u0040'..'\u007E') {
                        if (ch == 'm') applySgr(escBuf.toString())
                        esc = 0
                    } else {
                        escBuf.append(ch)
                    }
                }
                3 -> when (ch) {
                    '\u0007' -> esc = 0
                    '\u001B' -> esc = 1
                }
                4 -> esc = 0
                else -> when {
                    ch == '\u001B' -> esc = 1
                    ch == '\n' -> {
                        done.add(render(segs.toList(), true))
                        segs = mutableListOf()
                    }
                    ch == '\r' -> {}
                    ch == '\b' || ch == '\u007F' -> backspace()
                    ch == '\t' || ch >= ' ' -> appendChar(ch)
                }
            }
        }
    }

    private fun appendChar(ch: Char) {
        val last = segs.lastOrNull()
        if (last != null && last.color == fg && last.bold == bold) {
            segs[segs.lastIndex] = last.copy(text = last.text + ch)
        } else {
            segs.add(Seg(ch.toString(), fg, bold))
        }
    }

    private fun backspace() {
        val last = segs.lastOrNull() ?: return
        if (last.text.length <= 1) {
            segs.removeAt(segs.lastIndex)
        } else {
            segs[segs.lastIndex] = last.copy(text = last.text.dropLast(1))
        }
    }

    private fun applySgr(params: String) {
        val codes = if (params.isEmpty()) listOf(0) else params.split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < codes.size) {
            val c = codes[i]
            when {
                c == 0 -> { fg = null; bold = false }
                c == 1 -> bold = true
                c == 22 -> bold = false
                c in 30..37 -> fg = ANSI16[c - 30]
                c == 39 -> fg = null
                c in 90..97 -> fg = ANSI16[c - 90 + 8]
                c == 38 && i + 2 < codes.size && codes[i + 1] == 5 -> {
                    fg = color256(codes[i + 2])
                    i += 2
                }
                c == 38 && i + 4 < codes.size && codes[i + 1] == 2 -> {
                    fg = Color(
                        codes[i + 2].coerceIn(0, 255),
                        codes[i + 3].coerceIn(0, 255),
                        codes[i + 4].coerceIn(0, 255)
                    )
                    i += 4
                }
            }
            i++
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
