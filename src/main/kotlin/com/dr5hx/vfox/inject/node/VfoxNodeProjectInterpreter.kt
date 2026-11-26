package com.dr5hx.vfox.inject.node

import com.dr5hx.vfox.inject.VfoxPathUtil
import com.dr5hx.vfox.inject.VfoxProjectInitializer
import com.dr5hx.vfox.inject.VfoxProjectSdks
import com.dr5hx.vfox.settings.VfoxSettings
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreterManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Points the *project's* Node.js interpreter at the version the project declares in vfox.
 *
 * Node is not modelled as the project's SDK, so this is separate from the project-SDK write: the
 * JavaScript plugin keeps the selection in [NodeJsInterpreterManager]. Without this a project whose
 * `.vfox.toml` says `nodejs = "18.20.8"` keeps whatever interpreter was chosen before — often the
 * machine-wide one — even though the right one is registered and selectable.
 *
 * The version comes from [VfoxProjectSdks.declaredHome], so the two agree: the project's own
 * `.vfox/sdks/nodejs` link is preferred over a version file, and a declaration like `18` is bound to
 * the installed `18.20.8`. Nothing is written when the project declares no Node.js, or when the
 * project already resolves to the same executable.
 *
 * Runs as a [VfoxProjectInitializer] rather than its own start-up activity, so it is guaranteed to
 * run *after* the scan registered the interpreters — the one it selects has to exist in the IDE's
 * list before it can be selected.
 *
 * Lives in `vfox-node.xml`, merged only where the JavaScript plugin is present.
 */
class VfoxNodeProjectInterpreter : VfoxProjectInitializer {

    override fun apply(project: Project) {
        if (!VfoxSettings.getInstance().applyProjectSdkDefaults) return
        val basePath = project.basePath ?: return

        val home = try {
            VfoxProjectSdks.declaredHome(basePath, NODE_SDK)
        } catch (e: Exception) {
            thisLogger().debug("vfox: could not resolve the project's Node.js version", e)
            return
        } ?: return

        // The IDE's interpreter list is keyed on the executable, so the home has to be resolved to
        // the `node` binary inside it before it can be matched or selected.
        val binary = NodeBinary.inHome(File(home)) ?: run {
            thisLogger().debug("vfox: no node binary under $home; not setting the project interpreter")
            return
        }

        try {
            // One write action covers the lookup, the comparison and the write: resolving a ref
            // reads the interpreter list, and setting it writes the project's configuration, so
            // keeping them together avoids a torn read between the two.
            ApplicationManager.getApplication().runWriteAction {
                val manager = NodeJsInterpreterManager.getInstance(project)
                // Point at the interpreter as the IDE actually holds it — the settings file records
                // a reference by path, and the registered path may be the link or the store
                // directory, depending on which was added first. Both name the same executable.
                val registered = registeredInterpreter(binary) ?: run {
                    thisLogger().debug(
                        "vfox: $binary is not registered as a Node.js interpreter; not setting the project default"
                    )
                    return@runWriteAction
                }
                if (resolvesToSameFile(manager, project, registered)) return@runWriteAction

                manager.setInterpreterRef(NodeJsInterpreterRef.create(registered))
                thisLogger().info(
                    "vfox: set the project's Node.js interpreter to ${registered.interpreterSystemDependentPath}"
                )
            }
        } catch (e: Exception) {
            thisLogger().debug("vfox: could not set the project's Node.js interpreter", e)
        }
    }

    /**
     * The registered interpreter for [binary], or null when it is not in the IDE's list.
     *
     * Matched on the executable's **real** path, so a link and the store directory it points at find
     * the same registered entry whichever spelling was added. Returns the entry the manager holds
     * rather than a freshly built one, so the reference written to the project matches a path the
     * IDE can actually resolve.
     */
    private fun registeredInterpreter(binary: File): NodeJsLocalInterpreter? =
        NodeJsLocalInterpreterManager.getInstance().interpreters
            .firstOrNull { interpreter ->
                VfoxPathUtil.sameRealPath(interpreter.interpreterSystemDependentPath, binary.absolutePath)
            }

    /**
     * Whether the project's interpreter already resolves to the same real file as [wanted].
     *
     * Compared on the real path, because a project link and the store directory it points at are the
     * same `node.exe` under two names: without this, a project already on the right Node would have
     * its configuration rewritten on every start-up.
     */
    private fun resolvesToSameFile(
        manager: NodeJsInterpreterManager,
        project: Project,
        wanted: NodeJsLocalInterpreter,
    ): Boolean {
        val current = runCatching { manager.interpreterRef.resolve(project) }.getOrNull()
        val currentPath = (current as? NodeJsLocalInterpreter)?.interpreterSystemDependentPath ?: return false
        return VfoxPathUtil.sameRealPath(currentPath, wanted.interpreterSystemDependentPath)
    }

    private companion object {
        /** The vfox plugin name for Node.js. */
        const val NODE_SDK = "nodejs"
    }
}
