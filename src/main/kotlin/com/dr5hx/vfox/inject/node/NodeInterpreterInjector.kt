package com.dr5hx.vfox.inject.node

import com.dr5hx.vfox.inject.InjectResult
import com.dr5hx.vfox.inject.SdkInjector
import com.dr5hx.vfox.inject.VfoxPathUtil
import com.dr5hx.vfox.model.VfoxSdkVersion
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreterManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import java.io.File

/**
 * Registers vfox Node installations as Node.js interpreters.
 *
 * Node is not modelled as a platform `Sdk`; the JavaScript plugin keeps its own list of local
 * interpreters in [NodeJsLocalInterpreterManager]. That manager's list is replaced wholesale by
 * `setInterpreters`, so existing interpreters are read back and merged rather than overwritten —
 * dropping them would silently break every project that already points at one.
 *
 * Lives in `vfox-node.xml`, merged only where the JavaScript plugin is present.
 */
class NodeInterpreterInjector : SdkInjector {

    override val sdkName: String = "nodejs"

    override fun register(version: VfoxSdkVersion): InjectResult {
        val binary = NodeBinary.forVersion(version) ?: run {
            thisLogger().debug("Skipping ${version.displayName}: no node binary found under ${version.home}")
            return InjectResult.SKIPPED
        }

        val manager = NodeJsLocalInterpreterManager.getInstance()
        val existing = manager.interpreters
        // Compare on the *real* file, not the literal path: a project's `.vfox/sdks/nodejs/node.exe`
        // and the store copy behind it are one executable, and the IDE's own list does not
        // canonicalize — so registering both would show the same Node twice, under two paths.
        if (existing.any { VfoxPathUtil.sameRealPath(it.interpreterSystemDependentPath, binary.absolutePath) }) {
            return InjectResult.ALREADY_PRESENT
        }

        val interpreter = NodeJsLocalInterpreter(binary.absolutePath)
        ApplicationManager.getApplication().runWriteAction {
            manager.interpreters = existing + interpreter
        }
        return InjectResult.ADDED
    }
}
