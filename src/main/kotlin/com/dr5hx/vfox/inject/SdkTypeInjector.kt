package com.dr5hx.vfox.inject

import com.dr5hx.vfox.model.VfoxSdkVersion
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.projectRoots.SdkType

/**
 * Registers vfox versions as IDE SDKs for any SDK kind the IDE models with a real `SdkType`.
 *
 * One injector serves every catalogued SDK instead of one class per ecosystem, because the only
 * thing that differs between them is which `SdkType` to use — and that is data ([VfoxCatalogEntry]).
 * The type is resolved by **class name at run time**, never through a compile-time reference, which
 * is what lets this live in the base plugin rather than a per-product config file: a product whose
 * bundled plugins do not contain the class simply does not list it, and the SDK is skipped with no
 * `NoClassDefFoundError` and no optional-dependency bookkeeping.
 *
 * This covers Java, Kotlin and Python. Go and Node deliberately do *not* go through here: the IDE
 * models them in their own lists rather than in the SDK table, so they have dedicated injectors.
 */
class SdkTypeInjector(
    override val sdkName: String,
    private val typeSimpleNames: List<String>,
) : SdkInjector {

    /**
     * Whether this product has one of the `SdkType`s this injector needs.
     *
     * False in a product that does not bundle the SDK kind (no Kotlin plugin in GoLand, say). Such
     * an injector can never do anything, so it is dropped when the injector list is assembled rather
     * than being invoked only to return `SKIPPED`.
     */
    val isAvailable: Boolean get() = resolve(typeSimpleNames) != null

    override fun register(version: VfoxSdkVersion): InjectResult {
        val type = resolve(typeSimpleNames) ?: return InjectResult.SKIPPED
        val home = SdkTableHelper.resolveHome(version) ?: run {
            thisLogger().debug("Skipping ${version.displayName}: home not resolvable")
            return InjectResult.SKIPPED
        }
        if (!type.isValidSdkHome(home)) {
            thisLogger().debug("Skipping ${version.displayName}: $home is not a valid ${type.name} home")
            return InjectResult.SKIPPED
        }

        val existing = SdkTableHelper.findByHome(type, home)
        if (existing != null) return InjectResult.ALREADY_PRESENT

        val versionString = runCatching { type.getVersionString(home) }.getOrNull()
        val sdk = SdkTableHelper.createSdk(type, version.displayName, home, versionString)
        // Some types (Python) populate roots/flavour data in setupSdkPaths, exactly as their own
        // add-SDK flow does; running it keeps a vfox-added SDK identical to a hand-added one.
        runCatching { type.setupSdkPaths(sdk) }
            .onFailure { thisLogger().debug("setupSdkPaths failed for ${version.displayName}", it) }
        return SdkTableHelper.addIfAbsent(sdk)
    }

    companion object {
        /**
         * Resolves the first of [simpleNames] present as a registered `SdkType`.
         *
         * Lookup goes through [SdkType.getAllTypeList] — the IDE's own registry — rather than
         * `Class.forName`, because a class that is on the classpath but not registered as an SDK
         * type is not usable. Names are compared on the simple name, so a type registered under a
         * subclass is still found however it was loaded. The registry is read once and cached: a scan
         * resolves the type for every version, and the registered set does not change while the IDE
         * runs.
         */
        fun resolve(simpleNames: List<String>): SdkType? {
            if (simpleNames.isEmpty()) return null
            val byName = registeredTypesByName()
            return simpleNames.firstNotNullOfOrNull { byName[it] }
        }

        /**
         * Registered SDK types keyed by simple class name.
         *
         * Memoized on success only. A failure (the registry is not available yet, as can happen
         * while the platform is still coming up) is deliberately *not* cached, so a later call —
         * after the user opens the settings page, say — resolves properly instead of a transient
         * start-up failure leaving every SDK kind unavailable for the rest of the session.
         */
        @Volatile
        private var cachedTypes: Map<String, SdkType>? = null

        private fun registeredTypesByName(): Map<String, SdkType> {
            cachedTypes?.let { return it }
            val resolved = runCatching {
                SdkType.getAllTypeList().associateBy { type -> type.javaClass.simpleName }
            }.getOrNull() ?: return emptyMap()
            cachedTypes = resolved
            return resolved
        }

        /** The injector for [plugin], or null when the catalogue gives it no `SdkType`. */
        fun forPlugin(plugin: String): SdkTypeInjector? {
            val simpleNames = VfoxSdkCatalog.sdkTypesFor(plugin).map { it.substringAfterLast('.') }
            return if (simpleNames.isEmpty()) null else SdkTypeInjector(plugin, simpleNames)
        }

        /** One injector per catalogued plugin that maps onto a real `SdkType`, in catalogue order. */
        fun forCatalog(): List<SdkTypeInjector> =
            VfoxSdkCatalog.registrablePlugins().mapNotNull { forPlugin(it) }
    }
}

/**
 * Assembles the injectors this product can run.
 *
 * The per-product config files contribute the integrations that *must* be absent where their
 * bundled plugin is missing (Go, Node), because those classes reference bundled-plugin APIs
 * directly. The `SdkType`-backed kinds are different: they resolve their type by name and so are
 * safe to build everywhere, and are added to whatever the config files contributed.
 */
object SdkInjectors {

    /**
     * Every injector this product can actually run, in catalogue order.
     *
     * A `SdkType`-backed injector whose type this product lacks is left out, so callers never see a
     * kind that would only ever be skipped. Where a kind has both a dedicated injector (contributed
     * by a per-product config file) and a generic one, the dedicated injector wins: it knows the
     * IDE model that kind actually uses.
     *
     * @param contributed injectors from the merged per-product config files (Go, Node, ...).
     */
    fun all(contributed: List<SdkInjector> = SdkInjector.all()): List<SdkInjector> {
        val dedicated = contributed.associateBy { it.sdkName }
        val generic = SdkTypeInjector.forCatalog()
            .filter { it.isAvailable && it.sdkName !in dedicated }
            .associateBy { it.sdkName }
        return VfoxSdkCatalog.entries.mapNotNull { dedicated[it.plugin] ?: generic[it.plugin] }
    }

    /**
     * The vfox plugins this product can register as an IDE SDK, in catalogue order.
     *
     * Read by the settings page to offer a toggle only for kinds that would do something here, so a
     * WebStorm user is not shown "Register vfox JDKs" when WebStorm has no Java SDK model.
     */
    fun registrablePlugins(): List<String> = all().map { it.sdkName }
}
