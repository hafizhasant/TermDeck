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
    var mode by remember { mutableStateOf(Mode.NONE) }
    var cmd by remember { mutableStateOf("id") }
    var output by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Mode.NONE to "None", Mode.SHIZUKU to "Shizuku", Mode.ROOT to "Root")
                .forEach { (m, label) ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(label) }
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
