package com.dr5hx.vfox.inject

import com.dr5hx.vfox.core.VfoxProjectResolver
import com.dr5hx.vfox.core.VfoxService
import com.dr5hx.vfox.model.VfoxSdkVersion
import com.intellij.openapi.projectRoots.Sdk

/**
 * Resolves what a *project* declares in vfox into the SDKs the IDE already has registered.
 *
 * This is the shared half of "point the project's IDE settings at what it declares": every
 * integration needs the same two steps — ask [VfoxProjectResolver] which version the project wants,
 * then find the registered SDK for it — and only the final write differs (a project JDK, a Go SDK, a
 * Node interpreter). Keeping the resolution here means the integrations cannot disagree about which
 * version a project asks for.
 *
 * A project's own `.vfox/sdks/<sdk>` link wins over a version file, because it is the selection vfox
 * actually made for this project.
 */
object VfoxProjectSdks {

    /**
     * The vfox SDK kinds that can occupy the project's single **project SDK** slot, in priority
     * order.
     *
     * A project has one project SDK, so when several are declared they cannot all win. Java takes
     * precedence because the project SDK is the JDK for a JVM project — the build tools and the
     * module compiler read it — and Python and Kotlin follow. A project that declares only one is
     * unaffected by the order.
     */
    private val PROJECT_SDK_PRIORITY = listOf("java", "python", "kotlin")

    /**
     * The home path for [sdk] as the project declares it, or null when it declares none.
     *
     * The project's own `.vfox/sdks/<sdk>` link is preferred — it is what vfox created for this
     * project, and what the IDE's own configuration records. Otherwise the version a file declares
     * is bound to the installed release, so a declaration like `java = "11"` finds `11.0.16+8-librca`.
     */
    fun declaredHome(basePath: String, sdk: String, installed: List<VfoxSdkVersion>): String? {
        val resolver = VfoxProjectResolver()
        resolver.projectCandidates(basePath, installed).firstOrNull { it.sdk == sdk }?.home?.let { return it }

        val requirement = resolver.resolve(basePath, installed).firstOrNull { it.sdk == sdk } ?: return null
        return installed.firstOrNull { it.sdk == sdk && it.version == requirement.effectiveVersion }?.home
    }

    /** [declaredHome] against the versions the last scan found. */
    fun declaredHome(basePath: String, sdk: String): String? =
        declaredHome(basePath, sdk, VfoxService.getInstance().versions())

    /**
     * The registered project SDK the project asks for, or null when it declares none this product
     * can supply.
     *
     * The `SdkType` is resolved by class name through [SdkTypeInjector], so a product without that
     * SDK kind simply skips it. A kind the project declares but which was never registered (the user
     * turned it off) is skipped too, rather than having an unregistered home written into the project.
     */
    fun projectSdkFor(basePath: String): Sdk? {
        val installed = VfoxService.getInstance().versions()
        for (sdkName in PROJECT_SDK_PRIORITY) {
            val home = declaredHome(basePath, sdkName, installed) ?: continue
            val type = SdkTypeInjector.resolve(simpleTypeNames(sdkName)) ?: continue
            SdkTableHelper.findByHome(type, home)?.let { return it }
        }
        return null
    }

    /** The simple class names of the `SdkType`s the catalogue gives [plugin], best first. */
    private fun simpleTypeNames(plugin: String): List<String> =
        VfoxSdkCatalog.sdkTypesFor(plugin).map { it.substringAfterLast('.') }
}
