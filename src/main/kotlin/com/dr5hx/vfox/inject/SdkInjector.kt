package com.dr5hx.vfox.inject

import com.dr5hx.vfox.model.VfoxSdkVersion
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.ExtensionPointName

/** Outcome of attempting to register one vfox version as an IDE SDK candidate. */
enum class InjectResult {
    /** The SDK was added to the IDE. */
    ADDED,

    /** An equivalent SDK was already registered; nothing changed. */
    ALREADY_PRESENT,

    /** The integration is unavailable in this product or the settings disabled it. */
    SKIPPED,

    /** Registration was attempted but failed; the cause is logged. */
    FAILED,
}

/**
 * Registers vfox-managed SDKs of one kind as selectable candidates in the IDE.
 *
 * Implementations are contributed through [EP_NAME] from a per-product config file
 * (`vfox-java.xml`, `vfox-node.xml`, ...), which matters because an injector references classes
 * from a bundled plugin that exists only in some products. A config file that is never merged (for
 * example `vfox-java.xml` in WebStorm) means the injector class is never loaded at all, so no
 * `NoClassDefFoundError` can occur.
 *
 * Implementations must be idempotent: re-running a scan must not create duplicate SDKs.
 */
interface SdkInjector {
    /** The vfox SDK name this injector handles, e.g. `java`. */
    val sdkName: String

    /** Registers [version] as a candidate, or reports why it was not. */
    fun register(version: VfoxSdkVersion): InjectResult

    companion object {
        @JvmField
        val EP_NAME: ExtensionPointName<SdkInjector> =
            ExtensionPointName.create("com.dr5hx.vfox.sdkInjector")

        /** Every injector contributed by the product's merged config files (Go, Node, ...). */
        fun all(): List<SdkInjector> =
            EP_NAME.extensionList.filterNotNull()
    }
}

/**
 * Runs [SdkInjector]s, isolating their failures.
 *
 * Registration touches internal APIs of bundled plugins, which can change without notice; a broken
 * injector must not abort the rest of the scan.
 */
object SdkInjectorRegistry {

    /**
     * Registers every [version] that a contributed injector handles.
     *
     * @param versions the versions discovered by a scan.
     * @param isEnabled decides, per vfox SDK name, whether the user wants it registered.
     * @return a count of outcomes, keyed by [InjectResult].
     */
    fun registerAll(
        versions: List<VfoxSdkVersion>,
        isEnabled: (String) -> Boolean,
        injectors: List<SdkInjector> = SdkInjectors.all(),
    ): Map<InjectResult, Int> {
        val counts = HashMap<InjectResult, Int>()
        val byName = injectors.associateBy { it.sdkName }

        for (version in versions) {
            val injector = byName[version.sdk] ?: continue
            if (!isEnabled(version.sdk)) {
                counts.merge(InjectResult.SKIPPED, 1, Int::plus)
                continue
            }
            val result = try {
                injector.register(version)
            } catch (e: Exception) {
                thisLogger().warn("Failed to register ${version.displayName}", e)
                InjectResult.FAILED
            }
            counts.merge(result, 1, Int::plus)
        }
        return counts
    }
}
