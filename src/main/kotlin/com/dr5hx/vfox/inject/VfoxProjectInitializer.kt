package com.dr5hx.vfox.inject

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * A per-project step that runs once vfox SDKs have been scanned and registered.
 *
 * Registration alone is not enough for an integration that has to *consult* what was registered and
 * then act on the project — pointing the project's Node.js interpreter at the version it declares,
 * say. Running that as its own start-up activity would race the scan that has to happen first, so it
 * is contributed here and invoked by [com.dr5hx.vfox.VfoxStartupActivity] after registration.
 *
 * Implementations live in the per-product config files, because they reference bundled-plugin APIs
 * that exist only in some products (`vfox-node.xml` in WebStorm and IDEA Ultimate, not DataGrip).
 * A config file that is never merged means the implementation class is never loaded.
 *
 * Implementations must be idempotent and must never throw: start-up must not fail because a project
 * could not be adjusted.
 */
interface VfoxProjectInitializer {

    /** Applies this integration to [project]. Called off the EDT, after SDKs are registered. */
    fun apply(project: Project)

    companion object {
        @JvmField
        val EP_NAME: ExtensionPointName<VfoxProjectInitializer> =
            ExtensionPointName.create("com.dr5hx.vfox.projectInitializer")

        /** Every initializer contributed by the product's merged config files. */
        fun all(): List<VfoxProjectInitializer> = EP_NAME.extensionList.filterNotNull()
    }
}
