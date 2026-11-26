package com.dr5hx.vfox.buildtools

import com.dr5hx.vfox.core.VfoxProjectResolver
import com.dr5hx.vfox.settings.VfoxSettings
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer
import java.nio.file.Path

/**
 * Puts the project's vfox environment into new terminal sessions.
 *
 * This is the most portable of the integrations: it works in every product that ships the terminal
 * plugin, and it makes the commands a developer runs by hand (`node`, `mvn`, `gradle`, `java`)
 * resolve to the same versions the IDE is using.
 *
 * Lives in `vfox-terminal.xml`, merged only where the terminal plugin is present.
 */
class TerminalEnvCustomizer : ShellExecOptionsCustomizer {

    override fun customizeExecOptions(project: Project, shellExecOptions: MutableShellExecOptions) {
        if (!VfoxSettings.getInstance().injectTerminalEnv) return

        val env = try {
            val basePath = project.basePath ?: return
            val required = VfoxProjectResolver.forLastScan(basePath).map { it.sdk to it.effectiveVersion }
            VfoxEnvironment.resolveForProject(required)
        } catch (e: Exception) {
            thisLogger().debug("vfox: terminal environment not injected", e)
            return
        }
        if (env.isEmpty) return

        // PATH entries are prepended so vfox versions win over anything already installed, and in
        // reverse order so the first entry ends up ahead of the rest.
        for (entry in env.pathEntries.asReversed()) {
            runCatching { shellExecOptions.prependEntryToPATH(Path.of(entry)) }
        }
        for ((key, value) in env.variables) {
            runCatching { shellExecOptions.setEnvironmentVariable(key, value) }
        }
    }
}
