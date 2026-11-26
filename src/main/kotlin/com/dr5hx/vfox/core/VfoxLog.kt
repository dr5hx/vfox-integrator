package com.dr5hx.vfox.core

import com.dr5hx.vfox.inject.InjectResult
import com.dr5hx.vfox.model.VfoxScan
import com.dr5hx.vfox.model.VfoxSdkVersion
import com.dr5hx.vfox.settings.VfoxSettings
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes a human-readable record of each vfox scan to its own log file.
 *
 * The IDE's `idea.log` already receives the plugin's lines, but it is large, it rotates, and it
 * mixes every subsystem together. When something is wrong with SDK detection the first question is
 * always "what did the scan actually see?", so the scan result is also written verbatim here:
 * one file, one line per version, with the resolved home and where it came from.
 *
 * The file lives next to the IDE's other logs (`PathManager.getLogDir()`) and is appended to across
 * sessions, so a report of "it stopped finding Java yesterday" is still answerable today.
 *
 * Every write is best-effort: a read-only log directory or a locked file must never break a scan,
 * so failures are swallowed after one debug line.
 */
object VfoxLog {

    private const val FILE_NAME = "vfox-integrator.log"
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** The log file's path, or null when the log directory could not be resolved. */
    fun logFile(): File? = try {
        File(PathManager.getLogDir().toFile(), FILE_NAME)
    } catch (e: Exception) {
        thisLogger().debug("vfox: log directory unavailable", e)
        null
    }

    /** The log file's absolute path for display, or a description of why there is none. */
    fun logFilePath(): String = logFile()?.absolutePath ?: "(unavailable)"

    /**
     * Records a completed scan: a one-line header plus one line per discovered version.
     *
     * Called from [VfoxService.rescan] so every scan is recorded from one place, whichever path
     * triggered it.
     */
    fun scan(scan: VfoxScan) {
        if (!VfoxSettings.getInstance().writeLogFile) return
        append(scanLines(scan))
    }

    /** Records the outcome of registering a scan's versions. */
    fun registration(counts: Map<InjectResult, Int>) {
        if (!VfoxSettings.getInstance().writeLogFile) return
        append(listOf(registrationLine(counts)))
    }

    /** Records a free-form line, e.g. a build-tool integration that applied or was declined. */
    fun note(message: String) {
        if (!VfoxSettings.getInstance().writeLogFile) return
        append(listOf(message))
    }

    /** One aligned line per version: SDK, version, active flag, and resolved home. */
    internal fun versionLine(version: VfoxSdkVersion): String = buildString {
        append(version.sdk.padEnd(8))
        append("  ")
        append(version.version.padEnd(22))
        append(if (version.active) "active" else "      ")
        append("  ")
        append(version.home ?: "(no home)")
        if (version.variables.isNotEmpty()) {
            append("  vars=")
            append(version.variables.keys.sorted().joinToString(","))
        }
    }

    /**
     * The lines a scan contributes: a header, then one line per discovered version.
     *
     * Separated from the writing so the format can be asserted without a real log directory.
     */
    internal fun scanLines(scan: VfoxScan): List<String> = buildList {
        add(
            "scan: ${scan.sdks.size} SDK(s), ${scan.allVersions().size} version(s), " +
                "home=${scan.home ?: "not found"}, sdkRoot=${scan.sdkRoot ?: "not found"}" +
                (scan.error?.let { ", error=$it" } ?: "")
        )
        for (sdk in scan.sdks) {
            for (version in sdk.versions) {
                add("  " + versionLine(version))
            }
        }
    }

    /** The line recording a registration outcome. */
    internal fun registrationLine(counts: Map<InjectResult, Int>): String {
        val added = counts[InjectResult.ADDED] ?: 0
        val present = counts[InjectResult.ALREADY_PRESENT] ?: 0
        val skipped = counts[InjectResult.SKIPPED] ?: 0
        val failed = counts[InjectResult.FAILED] ?: 0
        return "register: $added added, $present already present, $skipped skipped, $failed failed"
    }

    /**
     * Appends [lines] to [file], each stamped with the current time, creating the file if needed.
     *
     * The write target is a parameter so the behaviour can be tested against a temp file; the public
     * entry points resolve it from [logFile]. Never throws — a locked or read-only log must not
     * break a scan.
     */
    internal fun writeTo(file: File, lines: List<String>) {
        if (lines.isEmpty()) return
        val stamp = LocalDateTime.now().format(TIMESTAMP)
        val text = buildString {
            for (line in lines) {
                append(stamp).append("  ").append(line).append(System.lineSeparator())
            }
        }
        try {
            synchronized(this) {
                file.parentFile?.mkdirs()
                file.appendText(text, StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            thisLogger().debug("vfox: could not write ${file.absolutePath}", e)
        }
    }

    /** Appends [lines] to the log file. Never throws. */
    private fun append(lines: List<String>) {
        val file = logFile() ?: return
        writeTo(file, lines)
    }
}
