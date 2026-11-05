package dev.termdeck

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashLog {
    private var file: File? = null

    fun install(context: Context) {
        val f = File(context.filesDir, "crash.txt")
        file = f
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                f.writeText(sw.toString())
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(): String? = file?.takeIf { it.exists() }?.readText()

    fun clear() {
        file?.delete()
    }
}
