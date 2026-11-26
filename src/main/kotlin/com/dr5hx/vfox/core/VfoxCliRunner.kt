package com.dr5hx.vfox.core

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * [VfoxCommandRunner] backed by the real `vfox` executable.
 *
 * The executable is resolved once from `PATH` / well-known locations and cached. Every invocation
 * is time-boxed: a hung vfox must never stall a background scan.
 */
class VfoxCliRunner(
    private val executable: File? = locateExecutable(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : VfoxCommandRunner {

    /** Whether a vfox executable was found at all; used to degrade to pure disk scanning. */
    val available: Boolean get() = executable != null

    /** The resolved executable path, for diagnostics in the tool window. */
    val executablePath: String? get() = executable?.absolutePath

    override fun run(args: List<String>, workingDirectory: String?): String? {
        val exe = executable ?: return null
        val commandLine = GeneralCommandLine(exe.absolutePath)
            .withParameters(args)
            .withCharset(Charset.forName("UTF-8"))
        if (workingDirectory != null) commandLine.withWorkDirectory(workingDirectory)

        return try {
            val process = commandLine.createProcess()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            // Drain both streams on their own threads so a full pipe buffer cannot deadlock the wait.
            val outReader = streamPump(process.inputStream, stdout)
            val errReader = streamPump(process.errorStream, stderr)

            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                thisLogger().warn("vfox ${args.joinToString(" ")} timed out after ${timeoutMillis}ms")
                return null
            }
            outReader.join(1_000)
            errReader.join(1_000)

            if (process.exitValue() != 0) {
                thisLogger().debug(
                    "vfox ${args.joinToString(" ")} exited with ${process.exitValue()}: ${stderr.toString().trim()}"
                )
                null
            } else {
                stdout.toString()
            }
        } catch (e: Exception) {
            thisLogger().debug("Failed to run vfox ${args.joinToString(" ")}", e)
            null
        }
    }

    private fun streamPump(stream: java.io.InputStream, sink: StringBuilder): Thread =
        Thread {
            runCatching {
                stream.bufferedReader(Charsets.UTF_8).use { sink.append(it.readText()) }
            }
        }.apply { isDaemon = true; start() }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 20_000L

        /** Finds the `vfox` executable: an explicit path wins, then `PATH`, then common locations. */
        fun locateExecutable(configuredPath: String? = null): File? {
            configuredPath?.takeIf { it.isNotBlank() }?.let { path ->
                val file = File(path)
                if (file.isFile) return file
            }

            val names = if (isWindows()) listOf("vfox.exe", "vfox.cmd", "vfox.bat") else listOf("vfox")
            val path = System.getenv("PATH").orEmpty()
            for (dir in path.split(File.pathSeparatorChar)) {
                if (dir.isBlank()) continue
                for (name in names) {
                    val candidate = File(dir, name)
                    if (candidate.isFile) return candidate
                }
            }
            return wellKnownLocations().firstOrNull { it.isFile }
        }

        /**
         * Conventional install locations, tried only after `PATH`.
         *
         * These cover the layouts the installer and package managers use, so a vfox that is installed
         * but not on the IDE's `PATH` (the usual case when the IDE is launched from a desktop
         * shortcut) is still found. Scoop's root comes from `$SCOOP` when set, otherwise its two
         * defaults, since a user can pick a non-standard root and the plugin cannot see that choice.
         */
        private fun wellKnownLocations(): List<File> {
            val home = File(System.getProperty("user.home"))
            return buildList {
                add(File(home, ".vfox/bin/vfox"))
                if (isWindows()) {
                    add(File(home, ".vfox/bin/vfox.exe"))
                    val scoopRoots = listOfNotNull(System.getenv("SCOOP"))
                        .map { File(it) } +
                        listOf(File(home, "scoop"), File(System.getenv("USERPROFILE") ?: home.path, "scoop"))
                    for (root in scoopRoots) add(File(root, "shims/vfox.exe"))
                }
            }
        }

        fun isWindows(): Boolean =
            System.getProperty("os.name").lowercase().contains("win")
    }
}
