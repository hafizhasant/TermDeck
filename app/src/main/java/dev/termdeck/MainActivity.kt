package dev.termdeck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.termdeck.priv.Mode
import dev.termdeck.priv.PrivilegeManager
import dev.termdeck.shell.ShellSession

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { TermApp() }
        }
    }
}

enum class Screen { SESSIONS, PACKAGES, SETUP, TERMINAL }

@Composable
fun TermApp() {
    val sessions = remember { mutableStateListOf<ShellSession>() }
    var screen by remember { mutableStateOf(Screen.SESSIONS) }
    var current by remember { mutableStateOf<ShellSession?>(null) }
    var defaultMode by remember { mutableStateOf(Mode.SHIZUKU) }
    var counter by remember { mutableStateOf(1) }

    fun newSession(): ShellSession {
        val s = ShellSession("session-$counter", defaultMode)
        counter += 1
        s.start()
        sessions.add(s)
        return s
    }

    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) {
            current = newSession()
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TopBar(
            screen = screen,
            onMenu = { screen = it },
            onNew = {
                current = newSession()
                screen = Screen.TERMINAL
            }
        )
        when (screen) {
            Screen.SESSIONS -> SessionList(
                sessions = sessions,
                onOpen = { current = it; screen = Screen.TERMINAL },
                modifier = Modifier.weight(1f)
            )
            Screen.TERMINAL -> {
                val s = current
                if (s != null) TerminalView(s, Modifier.weight(1f))
            }
            Screen.PACKAGES -> Box(Modifier.weight(1f)) {
                Text("Packages: coming next")
            }
            Screen.SETUP -> SetupView(
                mode = defaultMode,
                onMode = { defaultMode = it },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun TopBar(screen: Screen, onMenu: (Screen) -> Unit, onNew: () -> Unit) {
    val tabs = listOf(
        Screen.SESSIONS to "Sessions",
        Screen.PACKAGES to "Packages",
        Screen.SETUP to "Setup"
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in tabs) {
            val selected = screen == t.first ||
                (t.first == Screen.SESSIONS && screen == Screen.TERMINAL)
            FilterChip(
                selected = selected,
                onClick = { onMenu(t.first) },
                label = { Text(t.second, fontSize = 12.sp) }
            )
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onNew) { Text("+") }
    }
}

@Composable
fun SessionList(sessions: List<ShellSession>, onOpen: (ShellSession) -> Unit, modifier: Modifier) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(sessions) { s ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(s) }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(s.mode.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        if (s.isRunning) "running" else "stopped",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        s.lastLine,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun TerminalView(session: ShellSession, modifier: Modifier) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(session.lines.size) {
        if (session.lines.isNotEmpty()) {
            listState.animateScrollToItem(session.lines.size - 1)
        }
    }

    val send: () -> Unit = {
        if (input.isNotBlank()) {
            session.send(input)
            input = ""
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(session.lines) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        ExtraKeys { key -> input += key }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("command") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
            Button(onClick = send) { Text("Run") }
        }
    }
}

@Composable
fun ExtraKeys(onKey: (String) -> Unit) {
    val keys = listOf("|", "/", "~", "-", "&&", ";", "\$")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (k in keys) {
            OutlinedButton(
                onClick = { onKey(k) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                Text(k, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun SetupView(mode: Mode, onMode: (Mode) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Default mode for new sessions")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == Mode.NONE,
                onClick = { onMode(Mode.NONE) },
                label = { Text("None") }
            )
            FilterChip(
                selected = mode == Mode.SHIZUKU,
                onClick = { onMode(Mode.SHIZUKU) },
                label = { Text("Shizuku") }
            )
        }
        Text("Shizuku: " + PrivilegeManager.status(Mode.SHIZUKU))
        OutlinedButton(onClick = { PrivilegeManager.requestShizuku() }) {
            Text("Grant Shizuku")
        }
    }
}
