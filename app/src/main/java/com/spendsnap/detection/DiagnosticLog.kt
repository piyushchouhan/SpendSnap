package com.spendsnap.detection

import android.content.Context
import android.util.Log
import com.spendsnap.container
import com.spendsnap.parsing.ScreenNode

/**
 * Phase-0 helper: shows what text Android exposes for payment-app screens, so detection rules
 * can be tuned from real data. Output goes only to logcat (`adb logcat -s SpendSnapDump`),
 * is off by default, and never runs for PIN/OTP screens (those are dropped before this point).
 */
object DiagnosticLog {
    private const val TAG = "SpendSnapDump"
    private var lastDump = 0

    fun dump(context: Context, source: String, lines: List<String>) {
        if (!context.container.settings.diagnosticsEnabled) return
        Log.d(TAG, "---- $source (${lines.size} lines) ----")
        lines.forEachIndexed { i, line -> Log.d(TAG, "$i: $line") }
    }

    fun dumpNodes(context: Context, source: String, nodes: List<ScreenNode>) {
        if (!context.container.settings.diagnosticsEnabled) return
        // Skip unchanged screens; logd drops chatty apps' logs otherwise.
        val hash = nodes.hashCode()
        if (hash == lastDump) return
        lastDump = hash
        Log.d(TAG, "---- $source (${nodes.size} nodes) ----")
        nodes.forEachIndexed { i, n ->
            val flags = (if (n.editable) "E" else "") + (if (n.clickable) "C" else "")
            Log.d(TAG, "$i: [${flags}] ${n.text.replace('\n', '⏎')}  #${n.viewId?.substringAfter(":id/") ?: ""}")
        }
    }

    fun line(context: Context, message: String) {
        if (context.container.settings.diagnosticsEnabled) Log.d(TAG, ">> $message")
    }
}
