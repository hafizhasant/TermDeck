package dev.termdeck

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.termdeck.priv.Mode
import dev.termdeck.priv.PrivilegeManager
import dev.termdeck.shell.SessionStore
import android.content.ClipData
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import android.content.ClipboardManager
import android.widget.Toast
import dev.termdeck.shell.ShellSession

private val Bg = Color(0xFF0D0F14)
private val Surface = Color(0xFF161A22)
private val Surface2 = Color(0xFF1D222C)
private val Line = Color(0xFF262C38)
private val TextMain = Color(0xFFE6E9EF)
private val Muted = Color(0xFF7D8596)
private val Accent = Color(0xFF5EEAD4)
private val Accent2 = Color(0xFFA78BFA)
private val Ok = Color(0xFF86EFAC)
private val Err = Color(0xFFF87171)
private val Mono = FontFamily.Monospace
private const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"

enum class Screen { SESSIONS, PACKAGES, SETUP, TERMINAL }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2002)
        if (checkSelfPermission(TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(TERMUX_PERMISSION), 2001)
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(background = Bg, surface = Surface, primary = Accent)
            ) { TermApp() }
        }
    }
}

@Composable
fun TermApp() {
    val crash = remember { CrashLog.read() }
    var showCrash by remember { mutableStateOf(crash != null) }
    if (showCrash && crash != null) {
        AlertDialog(
            onDismissRequest = { CrashLog.clear(); showCrash = false },
            title = { Text("Last crash") },
            text = { Text(crash, fontFamily = Mono, fontSize = 9.sp) },
            confirmButton = {
                TextButton(onClick = { CrashLog.clear(); showCrash = false }) { Text("Dismiss") }
            }
        )
    }

    val ctx = LocalContext.current
    val sessions = SessionStore.sessions
    var screen by remember { mutableStateOf(Screen.SESSIONS) }
    var current by remember { mutableStateOf<ShellSession?>(null) }
    var defaultMode by remember { mutableStateOf(Mode.SHIZUKU) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopBar(
            screen = screen,
            onSelect = { screen = it },
            onNew = {
                current = SessionStore.create(ctx, defaultMode)
                screen = Screen.TERMINAL
            }
        )
        when (screen) {
            Screen.SESSIONS -> SessionsScreen(sessions, Modifier.weight(1f)) {
                current = it
                screen = Screen.TERMINAL
            }
            Screen.TERMINAL -> current?.let { s ->
                TerminalScreen(s, Modifier.weight(1f)) { screen = Screen.SESSIONS }
            }
            Screen.PACKAGES -> PackagesScreen(Modifier.weight(1f))
            Screen.SETUP -> SetupScreen(defaultMode, { defaultMode = it }, Modifier.weight(1f))
        }
    }
}

@Composable
fun TopBar(screen: Screen, onSelect: (Screen) -> Unit, onNew: () -> Unit) {
    val tabs: List<Pair<Screen, String>> = listOf(
        Pair(Screen.SESSIONS, "Sessions"),
        Pair(Screen.PACKAGES, "Packages"),
        Pair(Screen.SETUP, "Setup")
    )
    val active: Screen = if (screen == Screen.TERMINAL) Screen.SESSIONS else screen
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (t in tabs) {
                val selected = active == t.first
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Surface2 else Color.Transparent)
                        .clickable { onSelect(t.first) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        t.second,
                        color = if (selected) Accent else Muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface2)
                .clickable { onNew() },
            contentAlignment = Alignment.Center
        ) {
            Text("+", color = Accent2, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SessionsScreen(sessions: List<ShellSession>, modifier: Modifier, onOpen: (ShellSession) -> Unit) {
    val running = sessions.count { it.isRunning }
    val ctx = LocalContext.current
    var pendingClose by remember { mutableStateOf<ShellSession?>(null) }
    pendingClose?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingClose = null },
            title = { Text("Close ${s.name}?") },
            text = { Text("The shell and any running command in it will stop.") },
            confirmButton = {
                TextButton(onClick = { SessionStore.close(ctx, s); pendingClose = null }) { Text("Close") }
            },
            dismissButton = { TextButton(onClick = { pendingClose = null }) { Text("Cancel") } }
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Sessions", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "${sessions.size} sessions · $running running",
            color = Muted,
            fontFamily = Mono,
            fontSize = 11.sp
        )
        if (sessions.isEmpty()) {
            Text("No sessions yet. Tap + to start one.", color = Muted, fontSize = 14.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions) { s ->
                SessionCard(s, onClick = { onOpen(s) }, onLongClick = { pendingClose = s })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionCard(s: ShellSession, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .border(1.dp, if (s.isRunning) Accent.copy(alpha = 0.35f) else Line, RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (s.isRunning) Ok else Muted)
            )
            Spacer(Modifier.width(8.dp))
            Text(s.name, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Tag(s.mode.name.lowercase())
        }
        Text(
            s.lastLine,
            color = Muted,
            fontFamily = Mono,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun Tag(label: String) {
    Text(
        label,
        color = Accent2,
        fontFamily = Mono,
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Accent2.copy(alpha = 0.15f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(session: ShellSession, modifier: Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = ctx.getSystemService(ClipboardManager::class.java)
    val scrollState = rememberScrollState()
    val hScroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible

    val holder = remember { arrayOfNulls<TermInput>(1) }
    val alt = session.altMode

    fun paste() {
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(ctx, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        } else {
            session.sendRaw(text.replace("\r", "").replace("\n", "\r"))
        }
    }

    fun toggleKeyboard() {
        val v = holder[0] ?: return
        val imm = ctx.getSystemService(InputMethodManager::class.java)
        if (imeVisible) {
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
        } else {
            v.requestFocus()
            imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun copyAll() {
        clipboard?.setPrimaryClip(ClipData.newPlainText("TermDeck", session.allText()))
        Toast.makeText(ctx, "Output copied", Toast.LENGTH_SHORT).show()
    }

    val annotated = buildAnnotatedString {
        if (!alt) {
            session.lines.forEach {
                append(it)
                append("\n")
            }
        }
        session.live.forEachIndexed { i, l ->
            append(l)
            if (i < session.live.lastIndex) append("\n")
        }
    }

    LaunchedEffect(annotated.text.length) {
        withFrameNanos { }
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface2)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("<", color = TextMain, fontSize = 18.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(session.name, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (session.isRunning) "running" else "stopped") +
                        " · " + session.mode.name.lowercase() +
                        (if (alt) " · full screen" else ""),
                    color = Muted,
                    fontFamily = Mono,
                    fontSize = 11.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Paste") { paste() }
                Pill("Copy") { copyAll() }
            }
        }

        ExtraKeys { key ->
            when (key) {
                "ESC" -> session.sendRaw("\u001b")
                "TAB" -> session.sendRaw("\t")
                "UP" -> session.sendRaw("\u001b[A")
                "DOWN" -> session.sendRaw("\u001b[B")
                "DEL" -> session.sendRaw("\u007F")
                "CTRL-C" -> session.sendRaw("\u0003")
            }
        }

        // Tap the output to show or hide the keyboard.
        SelectionContainer(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface)
                .clickable { toggleKeyboard() }
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .then(if (alt) Modifier.horizontalScroll(hScroll) else Modifier)
                    .padding(12.dp)
            ) {
                Text(
                    annotated,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    softWrap = !alt
                )
            }
        }

        // Real input view. Keystrokes go straight to the shell.
        AndroidView(
            factory = { c -> TermInput(c).also { holder[0] = it } },
            update = { v -> v.send = { text -> session.sendRaw(text) } },
            modifier = Modifier.size(1.dp)
        )
    }
}

@Composable
fun OutputLine(line: String) {
    val color: Color = when {
        line.startsWith("\$ ") -> Accent
        line.startsWith("error") || line.startsWith("read error") -> Err
        line.startsWith("[shell") -> Muted
        else -> TextMain
    }
    Text(
        line,
        color = color,
        fontFamily = Mono,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(bottom = 2.dp)
    )
}

@Composable
fun ExtraKeys(onKey: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val keys = listOf(
        Pair("ESC", "ESC"),
        Pair("TAB", "Tab"),
        Pair("UP", "\u2191"),
        Pair("DOWN", "\u2193"),
        Pair("DEL", "Del"),
        Pair("CTRL-C", "^C")
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in keys) {
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface2)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onKey(k.first)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(k.second, color = Accent, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun Pill(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent else Surface2)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            color = if (selected) Bg else TextMain,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
private const val TERMUX_HOME = "/data/data/com.termux/files/home"

fun runInTermux(context: Context, command: String): String {
    val intent = Intent("com.termux.RUN_COMMAND").apply {
        setClassName("com.termux", "com.termux.app.RunCommandService")
        putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH)
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
        putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
    }
    return try {
        context.startService(intent)
        "Sent to Termux: $command. Check Termux for progress."
    } catch (e: Throwable) {
        "Could not reach Termux: ${e.message ?: e.javaClass.simpleName}. Check the Termux setup step."
    }
}

@Composable
fun PackagesScreen(modifier: Modifier) {
    val context = LocalContext.current
    var status by remember {
        mutableStateOf("Installs run inside Termux. Tap Install, then check Termux for progress.")
    }
    val packages: List<String> = listOf(
        "git", "python", "nodejs", "openssh", "curl", "wget", "vim", "htop", "tmux", "rsync"
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Packages", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(status, color = Muted, fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(packages) { pkg ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surface)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(pkg, color = TextMain, fontFamily = Mono, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Pill("Install", selected = true) {
                        status = runInTermux(context, "pkg install -y $pkg")
                    }
                }
            }
        }
    }
}

@Composable
fun SetupScreen(mode: Mode, onMode: (Mode) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val termuxReady = context.checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Setup", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        PtyTestCard()

        Text("Default mode for new sessions", color = Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("None", selected = mode == Mode.NONE) { onMode(Mode.NONE) }
            Pill("Shizuku", selected = mode == Mode.SHIZUKU) { onMode(Mode.SHIZUKU) }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Shizuku", color = TextMain, fontWeight = FontWeight.SemiBold)
            Text(PrivilegeManager.status(Mode.SHIZUKU), color = Muted, fontSize = 12.sp)
            Pill("Grant Shizuku") { PrivilegeManager.requestShizuku() }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Termux", color = TextMain, fontWeight = FontWeight.SemiBold)
            Text(
                if (termuxReady) "Permission granted" else "Permission not granted. Accept the prompt when it appears.",
                color = Muted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun PtyTestCard() {
    val context = LocalContext.current
    var result by remember { mutableStateOf("Tap Run test to check the PTY helper.") }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("PTY test", color = TextMain, fontWeight = FontWeight.SemiBold)
        Text(result, color = Muted, fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp)
        Pill("Run test") {
            result = "running..."
            Thread {
                val out = ptyTest(context)
                android.os.Handler(android.os.Looper.getMainLooper()).post { result = out }
            }.start()
        }
    }
}

fun ptyTest(context: Context): String {
    val path = context.applicationInfo.nativeLibraryDir + "/libtermdeckpty.so"
    val sb = StringBuilder()
    sb.append("helper: ").append(path).append("\n")
    sb.append("exists: ").append(java.io.File(path).exists()).append("\n")
    sb.append("--- app user ---\n")
    sb.append(
        try {
            val pb = ProcessBuilder(path).redirectErrorStream(true)
            runPty(pb.start())
        } catch (e: Throwable) {
            "error: " + (e.message ?: e.javaClass.simpleName)
        }
    )
    sb.append("\n--- shizuku ---\n")
    sb.append(
        try {
            if (!dev.termdeck.priv.PrivilegeManager.shizukuReady()) {
                "Shizuku not granted"
            } else {
                val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                m.isAccessible = true
                val p = m.invoke(null, arrayOf("sh", "-c", path), null, null) as Process
                runPty(p)
            }
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            "error: " + (cause.message ?: cause.javaClass.simpleName)
        }
    )
    return sb.toString()
}

fun runPty(p: Process): String {
    return try {
        Thread {
            Thread.sleep(8000)
            p.destroy()
        }.start()
        p.outputStream.write("id\nexit\n".toByteArray())
        p.outputStream.flush()
        val text = p.inputStream.bufferedReader().readText()
        val clean = text.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
        if (clean.isBlank()) "no output" else clean.take(1500)
    } catch (e: Throwable) {
        "error: " + (e.message ?: e.javaClass.simpleName)
    }
}

// A view that acts as a text input for the terminal. Each keystroke, backspace,
// and Enter is sent to the shell in order, with no text field to get out of sync.
class TermInput(context: Context) : View(context) {
    var send: (String) -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this@TermInput, false) {
            private var composing = ""

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing = ""
                val t = text?.toString() ?: return true
                if (t.isNotEmpty()) send(t.replace("\n", "\r"))
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val now = text?.toString() ?: ""
                var common = 0
                val max = minOf(composing.length, now.length)
                while (common < max && composing[common] == now[common]) common++
                val sb = StringBuilder()
                repeat(composing.length - common) { sb.append('\u007F') }
                sb.append(now.substring(common))
                if (sb.isNotEmpty()) send(sb.toString().replace("\n", "\r"))
                composing = now
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { send("\u007F") }
                composing = composing.dropLast(beforeLength)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> send("\u007F")
                    KeyEvent.KEYCODE_ENTER -> send("\r")
                    KeyEvent.KEYCODE_TAB -> send("\t")
                    KeyEvent.KEYCODE_ESCAPE -> send("\u001b")
                    else -> {
                        val c = event.unicodeChar
                        if (c != 0) send(c.toChar().toString())
                    }
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                send("\r")
                return true
            }
        }
    }
}
