package xyz.regulad.blueheaven.util

import java.io.BufferedReader
import java.io.InputStreamReader

fun getRecentLogcatEntries(numberOfLines: Int): List<String> {
    val logEntries = mutableListOf<String>()
    try {
        val process = Runtime.getRuntime().exec("logcat -t $numberOfLines")
        val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))

        var line: String?
        while (bufferedReader.readLine().also { line = it } != null) {
            line?.let { logEntries.add(it) }
        }

        bufferedReader.close()
        process.destroy()
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return logEntries
}
