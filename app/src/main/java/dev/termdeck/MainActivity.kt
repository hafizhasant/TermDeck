package dev.termdeck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.termdeck.priv.Mode
import dev.termdeck.priv.PrivilegeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { TermScreen() }
        }
    }
}

@Composable
fun TermScreen() {
    val scope = rememberCoroutineScope()
    val hasRoot = remember { PrivilegeManager.hasRoot() }
    var mode by remember { mutableStateOf(Mode.NONE) }
    var cmd by remember { mutableStateOf("id") }
    var output by remember { mutableStateOf("") }

    val modes: List<Pair<Mode, String>> = buildList {
        add(Pair(Mode.NONE, "None"))
        add(Pair(Mode.SHIZUKU, "Shizuku"))
        if (hasRoot) add(Pair(Mode.ROOT, "Root"))
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in modes) {
                FilterChip(
                    selected = mode == entry.first,
                    onClick = { mode = entry.first },
                    label = { Text(entry.second) }
                )
            }
        }
        Text("Status: " + PrivilegeManager.status(mode))
        OutlinedTextField(
            value = cmd,
            onValueChange = { cmd = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Command") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    output = withContext(Dispatchers.IO) { PrivilegeManager.run(cmd, mode) }
                }
            }) { Text("Run") }
            OutlinedButton(onClick = { PrivilegeManager.requestShizuku() }) {
                Text("Grant Shizuku")
            }
        }
        Text(
            output,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        )
    }
}
