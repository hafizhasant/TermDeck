package dev.termdeck.priv

import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku

enum class Mode { NONE, SHIZUKU, ROOT }

object PrivilegeManager {
    private const val SHIZUKU_REQUEST = 1001

    fun status(mode: Mode): String = when (mode) {
        Mode.NONE -> "unprivileged"
        Mode.SHIZUKU -> when {
            shizukuReady() -> "Shizuku granted"
            isShizukuRunning() -> "Shizuku running, tap Grant Shizuku"
            else -> "Shizuku not running"
        }
        Mode.ROOT -> if (hasRoot()) "root granted" else "no root access"
    }

    fun hasRoot(): Boolean =
        try { Shell.getShell().isRoot } catch (e: Throwable) { false }

    fun isShizukuRunning(): Boolean =
        try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun shizukuReady(): Boolean =
        try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) { false }

    fun requestShizuku() {
        try { Shizuku.requestPermission(SHIZUKU_REQUEST) } catch (e: Throwable) { /* not running */ }
    }

    fun run(cmd: String, mode: Mode): String = try {
        when (mode) {
            Mode.NONE -> exec(ProcessBuilder("sh", "-c", cmd))
            Mode.ROOT -> Shell.cmd(cmd).exec().let { (it.out + it.err).joinToString("\n") }
            Mode.SHIZUKU -> shizukuExec(cmd)
        }
    } catch (e: Throwable) {
        "error: ${e.message}"
    }

    private fun exec(pb: ProcessBuilder): String {
        val p = pb.redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText()
    }

    // Shizuku's newProcess is hidden in the public API, so it is called by reflection.
    private fun shizukuExec(cmd: String): String {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        return p.inputStream.bufferedReader().readText() +
            p.errorStream.bufferedReader().readText()
    }
}
