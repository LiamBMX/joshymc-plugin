package com.liam.joshymc.lifecycle

import java.util.logging.Level
import java.util.logging.Logger

/**
 * The plugin's shutdown steps, run once in the order they were added (issue #1325).
 *
 * Every step is isolated: a step that throws is logged once with its label and the
 * exception, and the next step still runs, so a broken manager can't keep the databases
 * from closing. A step whose [Step.ready] check is false (a manager that was never
 * initialized because startup failed earlier) is skipped without running.
 *
 * A fatal JVM error ([VirtualMachineError]) doesn't stop the remaining steps either, but
 * it is rethrown once they have all been attempted.
 *
 * Bukkit-free, so it can be checked outside a server.
 */
class ShutdownSequence(private val logger: Logger, private val tag: String = "Shutdown") {

    private class Step(val label: String, val ready: () -> Boolean, val action: () -> Unit)

    data class Report(val completed: List<String>, val failed: List<String>, val skipped: List<String>) {
        val ok: Boolean get() = failed.isEmpty()
    }

    private val steps = mutableListOf<Step>()
    private var report: Report? = null

    /** Adds a step. [ready] = false skips it (e.g. `{ ::fooManager.isInitialized }`). */
    fun step(label: String, ready: () -> Boolean = { true }, action: () -> Unit) {
        check(report == null) { "Shutdown already ran" }
        steps += Step(label, ready, action)
    }

    /** Runs every step once. A second call runs nothing and returns the first report. */
    fun run(): Report {
        report?.let {
            logger.warning("[$tag] Already ran; ignoring the repeated shutdown.")
            return it
        }
        val completed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var fatal: VirtualMachineError? = null

        for (step in steps) {
            try {
                if (!step.ready()) {
                    skipped += step.label
                    continue
                }
                step.action()
                completed += step.label
            } catch (e: VirtualMachineError) {
                failed += step.label
                if (fatal == null) fatal = e
                logFailure(step.label, e)
            } catch (e: Throwable) {
                failed += step.label
                logFailure(step.label, e)
            }
        }

        val result = Report(completed, failed, skipped)
        report = result
        if (skipped.isNotEmpty()) logger.info("[$tag] Skipped (never initialized): ${skipped.joinToString()}")
        if (failed.isNotEmpty()) {
            logger.severe("[$tag] ${failed.size} of ${steps.size} step(s) failed: ${failed.joinToString()}. The other steps still ran.")
        }
        fatal?.let { throw it }
        return result
    }

    private fun logFailure(label: String, e: Throwable) {
        try {
            logger.log(Level.SEVERE, "[$tag] $label failed: ${e.javaClass.simpleName}: ${e.message}", e)
        } catch (_: Throwable) {
            // Logging must never stop the remaining steps.
        }
    }
}
