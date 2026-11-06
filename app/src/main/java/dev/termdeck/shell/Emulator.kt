package dev.termdeck.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

private val TEXT_COLOR = Color(0xFFE6E9EF)
private val PROMPT_COLOR = Color(0xFF5EEAD4)

private val ANSI16 = arrayOf(
    Color(0xFF4B5563), Color(0xFFF87171), Color(0xFF86EFAC), Color(0xFFFBBF24),
    Color(0xFF60A5FA), Color(0xFFA78BFA), Color(0xFF5EEAD4), Color(0xFFE6E9EF),
    Color(0xFF7D8596), Color(0xFFFCA5A5), Color(0xFFBBF7D0), Color(0xFFFDE68A),
    Color(0xFF93C5FD), Color(0xFFC4B5FD), Color(0xFF99F6E4), Color(0xFFFFFFFF)
)

private val PROMPT = Regex("""^[^$\n]{1,40}\$ """)

// DEC line-drawing characters, used by htop and other ncurses boxes.
private val DEC: Map<Char, Char> = mapOf(
    'j' to '┘', 'k' to '┐', 'l' to '┌', 'm' to '└', 'n' to '┼',
    'q' to '─', 't' to '├', 'u' to '┤', 'v' to '┴', 'w' to '┬', 'x' to '│'
)

private fun color256(n: Int): Color {
    if (n !in 0..255) return TEXT_COLOR
    if (n < 16) return ANSI16[n]
    if (n < 232) {
        val c = n - 16
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        return Color(levels[c / 36], levels[(c / 6) % 6], levels[c % 6])
    }
    val g = 8 + (n - 232) * 10
    return Color(g, g, g)
}

internal class TermCell {
    var ch: Char = ' '
    var fg: Color? = null
    var bold: Boolean = false

    fun copyFrom(o: TermCell) {
        ch = o.ch
        fg = o.fg
        bold = o.bold
    }

    fun blank() {
        ch = ' '
        fg = null
        bold = false
    }
}

internal class TermRow(cols: Int) {
    val cells = Array(cols) { TermCell() }
    var wrapped = false

    fun clear(from: Int, to: Int) {
        for (i in from until to) cells[i].blank()
        if (from == 0) wrapped = false
    }
}

// Turns one logical line (one or more wrapped rows) into styled text.
internal fun build(rows: List<TermRow>, trimLast: Boolean, markPrompt: Boolean): AnnotatedString {
    val styled = buildAnnotatedString {
        rows.forEachIndexed { ri, row ->
            val cells = row.cells
            var end = cells.size
            if (trimLast && ri == rows.lastIndex) {
                while (end > 0 && cells[end - 1].ch == ' ') end--
            }
            var i = 0
            while (i < end) {
                val fg = cells[i].fg
                val b = cells[i].bold
                val sb = StringBuilder()
                while (i < end && cells[i].fg == fg && cells[i].bold == b) {
                    sb.append(cells[i].ch)
                    i++
                }
                withStyle(SpanStyle(color = fg ?: TEXT_COLOR, fontWeight = if (b) FontWeight.Bold else null)) {
                    append(sb.toString())
                }
            }
        }
    }
    if (!markPrompt) return styled
    val m = PROMPT.find(styled.text) ?: return styled
    return buildAnnotatedString {
        append(styled)
        addStyle(SpanStyle(color = PROMPT_COLOR, fontWeight = FontWeight.SemiBold), 0, m.range.last + 1)
    }
}

// A VT100/xterm-style screen. Feed it raw bytes from the PTY.
class Emulator(private val cols: Int = 80, private val rows: Int = 24) {
    private var screen: Array<TermRow> = Array(rows) { TermRow(cols) }
    private var mainScreen: Array<TermRow>? = null
    private var cx = 0
    private var cy = 0
    private var savedX = 0
    private var savedY = 0
    private var mainCx = 0
    private var mainCy = 0
    private var fg: Color? = null
    private var bold = false
    private var gfx = false
    private var designator = '('
    private var pendingWrap = false
    private var parseState = 0
    private val seq = StringBuilder()
    private val pendingRows = mutableListOf<TermRow>()
    private val committed = mutableListOf<AnnotatedString>()

    var altActive = false
        private set

    fun feed(chunk: CharArray, n: Int) {
        for (i in 0 until n) process(chunk[i])
    }

    // Lines that scrolled off the top of the main screen, since the last call.
    fun drainCommitted(): List<AnnotatedString> {
        if (committed.isEmpty()) return emptyList()
        val out = committed.toList()
        committed.clear()
        return out
    }

    // What is on screen right now. Main screen shows rows up to the cursor.
    // Full-screen mode shows every row.
    fun liveLines(): List<AnnotatedString> {
        if (altActive) return screen.map { build(listOf(it), trimLast = false, markPrompt = false) }
        val out = mutableListOf<AnnotatedString>()
        var group = mutableListOf<TermRow>()
        for (i in 0..cy) {
            group.add(screen[i])
            if (!screen[i].wrapped || i == cy) {
                out.add(build(group, trimLast = true, markPrompt = true))
                group = mutableListOf()
            }
        }
        return out
    }

    private fun process(ch: Char) {
        when (parseState) {
            1 -> parseState = when (ch) {
                '[' -> { seq.setLength(0); 2 }
                ']' -> 3
                '(', ')' -> { designator = ch; 4 }
                '7' -> { savedX = cx; savedY = cy; 0 }
                '8' -> { cx = savedX; cy = savedY; pendingWrap = false; 0 }
                'M' -> { if (cy > 0) cy--; 0 }
                else -> 0
            }
            2 -> if (ch in '\u0040'..'\u007E') {
                csi(seq.toString(), ch)
                parseState = 0
            } else {
                seq.append(ch)
            }
            3 -> when (ch) {
                '\u0007' -> parseState = 0
                '\u001B' -> parseState = 1
            }
            4 -> {
                if (designator == '(') gfx = ch == '0'
                parseState = 0
            }
            else -> when {
                ch == '\u001B' -> parseState = 1
                ch == '\n' -> lineFeed()
                ch == '\r' -> { cx = 0; pendingWrap = false }
                ch == '\b' -> { if (cx > 0) cx--; pendingWrap = false }
                ch == '\t' -> { cx = minOf(((cx / 8) + 1) * 8, cols - 1); pendingWrap = false }
                ch < ' ' || ch == '\u007F' -> {}
                else -> put(ch)
            }
        }
    }

    private fun put(raw: Char) {
        if (pendingWrap) {
            screen[cy].wrapped = true
            pendingWrap = false
            cx = 0
            lineFeed()
        }
        val ch = if (gfx) DEC[raw] ?: raw else raw
        val cell = screen[cy].cells[cx]
        cell.ch = ch
        cell.fg = fg
        cell.bold = bold
        if (cx < cols - 1) cx++ else pendingWrap = true
    }

    private fun lineFeed() {
        pendingWrap = false
        if (cy < rows - 1) cy++ else scrollUp()
    }

    private fun scrollUp() {
        val top = screen[0]
        if (!altActive) {
            pendingRows.add(top)
            if (!top.wrapped) {
                committed.add(build(pendingRows.toList(), trimLast = true, markPrompt = true))
                pendingRows.clear()
            }
        }
        for (i in 0 until rows - 1) screen[i] = screen[i + 1]
        screen[rows - 1] = TermRow(cols)
    }

    private fun csi(raw: String, final: Char) {
        val priv = raw.startsWith("?")
        val body = if (priv) raw.substring(1) else raw
        val p = if (body.isEmpty()) IntArray(0)
                else body.split(';').map { it.toIntOrNull() ?: 0 }.toIntArray()
        fun arg(i: Int, def: Int): Int = if (i < p.size && p[i] != 0) p[i] else def

        if (priv) {
            if (final == 'h' || final == 'l') {
                for (m in p) if (m == 1049 || m == 47 || m == 1047) setAlt(final == 'h')
            }
            return
        }
        when (final) {
            'm' -> sgr(body)
            'A' -> cy = maxOf(0, cy - arg(0, 1))
            'B' -> cy = minOf(rows - 1, cy + arg(0, 1))
            'C' -> cx = minOf(cols - 1, cx + arg(0, 1))
            'D' -> cx = maxOf(0, cx - arg(0, 1))
            'G' -> cx = (arg(0, 1) - 1).coerceIn(0, cols - 1)
            'd' -> cy = (arg(0, 1) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cy = (arg(0, 1) - 1).coerceIn(0, rows - 1)
                cx = (arg(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(if (p.isEmpty()) 0 else p[0])
            'K' -> eraseLine(if (p.isEmpty()) 0 else p[0])
            'X' -> screen[cy].clear(cx, minOf(cols, cx + arg(0, 1)))
            'P' -> deleteChars(arg(0, 1))
            '@' -> insertChars(arg(0, 1))
            'L' -> insertLines(arg(0, 1))
            'M' -> deleteLines(arg(0, 1))
            's' -> { savedX = cx; savedY = cy }
            'u' -> { cx = savedX; cy = savedY }
        }
        pendingWrap = false
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                screen[cy].clear(cx, cols)
                for (r in cy + 1 until rows) screen[r].clear(0, cols)
            }
            1 -> {
                for (r in 0 until cy) screen[r].clear(0, cols)
                screen[cy].clear(0, minOf(cx + 1, cols))
            }
            else -> for (r in 0 until rows) screen[r].clear(0, cols)
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> screen[cy].clear(cx, cols)
            1 -> screen[cy].clear(0, minOf(cx + 1, cols))
            else -> screen[cy].clear(0, cols)
        }
    }

    private fun deleteChars(n: Int) {
        val c = screen[cy].cells
        val k = minOf(n, cols - cx)
        for (i in cx until cols - k) c[i].copyFrom(c[i + k])
        for (i in cols - k until cols) c[i].blank()
    }

    private fun insertChars(n: Int) {
        val c = screen[cy].cells
        val k = minOf(n, cols - cx)
        for (i in cols - 1 downTo cx + k) c[i].copyFrom(c[i - k])
        for (i in cx until cx + k) c[i].blank()
    }

    private fun insertLines(n: Int) {
        val k = minOf(n, rows - cy)
        for (r in rows - 1 downTo cy + k) screen[r] = screen[r - k]
        for (r in cy until cy + k) screen[r] = TermRow(cols)
    }

    private fun deleteLines(n: Int) {
        val k = minOf(n, rows - cy)
        for (r in cy until rows - k) screen[r] = screen[r + k]
        for (r in rows - k until rows) screen[r] = TermRow(cols)
    }

    private fun setAlt(on: Boolean) {
        if (on && !altActive) {
            mainScreen = screen
            mainCx = cx
            mainCy = cy
            screen = Array(rows) { TermRow(cols) }
            cx = 0
            cy = 0
            pendingWrap = false
            altActive = true
        } else if (!on && altActive) {
            screen = mainScreen ?: Array(rows) { TermRow(cols) }
            mainScreen = null
            cx = mainCx
            cy = mainCy
            pendingWrap = false
            altActive = false
        }
    }

    private fun sgr(body: String) {
        val codes = if (body.isEmpty()) listOf(0) else body.split(';').map { it.toIntOrNull() ?: 0 }
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
}
