package fr.descentecanyon.app.perf

import android.util.Log
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

object PerformanceTrace {

    private const val TAG = "AppPerf"

    /**
     * Off in release builds: the traces cost string building on the main thread and would leave
     * the canyons viewed in the system log.
     */
    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    private var processStartElapsedMs: Long = nowMs()

    private val activeTraces = ConcurrentHashMap<String, ActiveTrace>()

    fun markProcessCreated(reason: String = "application_on_create") {
        processStartElapsedMs = nowMs()
        logEvent("app_process_created", "reason" to reason)
    }

    fun logEvent(event: String, vararg attributes: Pair<String, Any?>) {
        if (!isEnabled) return
        emitLog(buildMessage(event = event, durationMs = null, attributes = attributes.toMap()))
    }

    fun start(key: String, event: String, vararg attributes: Pair<String, Any?>) {
        if (!isEnabled) return
        activeTraces[key] = ActiveTrace(event = event, startedAtElapsedMs = nowMs(), attributes = attributes.toMap())
        logEvent("${event}_start", *attributes)
    }

    fun end(key: String, outcome: String = "ok", vararg attributes: Pair<String, Any?>) {
        if (!isEnabled) return
        val trace = activeTraces.remove(key)
        if (trace == null) {
            logEvent(
                event = "trace_end_without_start",
                "key" to key,
                "outcome" to outcome,
                *attributes,
            )
            return
        }

        val merged = LinkedHashMap<String, Any?>()
        merged.putAll(trace.attributes)
        merged["key"] = key
        merged["outcome"] = outcome
        attributes.forEach { (name, value) -> merged[name] = value }

        val durationMs = nowMs() - trace.startedAtElapsedMs
        emitLog(buildMessage(event = "${trace.event}_end", durationMs = durationMs, attributes = merged))
    }

    private fun buildMessage(event: String, durationMs: Long?, attributes: Map<String, Any?>): String {
        val sinceProcessStartMs = nowMs() - processStartElapsedMs
        return buildString {
            append("event=")
            append(event)
            append(' ')
            append("t=")
            append(sinceProcessStartMs)
            append("ms")
            durationMs?.let {
                append(' ')
                append("duration=")
                append(it)
                append("ms")
            }
            attributes.forEach { (name, value) ->
                if (value != null) {
                    append(' ')
                    append(name)
                    append('=')
                    append(formatValue(value))
                }
            }
        }
    }

    private fun formatValue(value: Any): String = value.toString()
        .replace('\n', '_')
        .replace('\r', '_')
        .replace(' ', '_')

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    private fun emitLog(message: String) {
        if (!isEnabled) return
        runCatching { Log.i(TAG, message) }
            .getOrElse { println("$TAG: $message") }
    }

    private data class ActiveTrace(val event: String, val startedAtElapsedMs: Long, val attributes: Map<String, Any?>)
}
