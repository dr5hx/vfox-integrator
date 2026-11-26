package com.dr5hx.vfox.inject

import com.dr5hx.vfox.VfoxBundle

/**
 * How far the IDE can be wired to the SDKs a vfox plugin installs.
 *
 * The levels are ordered by how much of the IDE is involved, and each implies the ones below it: a
 * registered SDK is always also listed in the tool window and reflected in the environment.
 */
enum class VfoxSupport {
    /** Registered in the IDE's own SDK table through a real `SdkType` (Java, Kotlin, Python, ...). */
    SDK_CANDIDATE,

    /** Registered through a bespoke IDE model that is not an `SdkType` (Go's `GoSdkList`, Node's interpreter list). */
    DEDICATED,

    /** Not an SDK at all: wired into a build tool (Gradle's JVM, Maven's home). */
    BUILD_TOOL,

    /** No IDE SDK model exists: offered through `PATH`/variables and listed in the tool window only. */
    ENV_ONLY,
}

/**
 * One vfox plugin and how much of the IDE the plugin can be wired into.
 *
 * @property plugin the vfox plugin name, spelled as `vfox list` and `vfox available` spell it.
 * @property title a human-readable name for the settings page, e.g. `Node.js` for `nodejs`.
 * @property support how much of the IDE is wired up.
 * @property sdkTypes for [VfoxSupport.SDK_CANDIDATE], the `SdkType` class names to try, most
 *   preferred first.
 *
 *   Class names are kept as strings on purpose. An integration for a bundled plugin must not be a
 *   compile-time reference, or this plugin would refuse to load in the products that lack that
 *   plugin (the Java plugin exists only in IntelliJ IDEA / Android Studio, for instance). Matching
 *   on the run-time class name instead means a product without the type simply skips the SDK, with
 *   no shared code depending on it.
 * @property ide the IDE or IDE plugin that natively understands this SDK's tooling, when one exists.
 *   Recorded so the tool window can say *why* a row is env-only rather than leaving it looking
 *   unsupported, and so a future level can be raised for a plugin that gains a real IDE model.
 */
data class VfoxCatalogEntry(
    val plugin: String,
    val title: String,
    val support: VfoxSupport,
    val sdkTypes: List<String> = emptyList(),
    val ide: String? = null,
)

/**
 * The catalogue of every plugin `vfox available` offers, and how this plugin treats each one.
 *
 * It exists so "which vfox SDKs does the IDE support?" is answered by one table rather than by
 * scattered `if (sdk == "...")` tests: the injectors, the settings page and the tool window all read
 * their behaviour from here. Adding support for a new vfox plugin that maps onto an existing IDE
 * `SdkType` is then a single row, and a plugin with no IDE model is still covered — it is listed in
 * the tool window and exported into the environment rather than silently ignored.
 *
 * The `sdkTypes` class names were verified against the plugins actually installed on the reference
 * machine: IntelliJ IDEA registers `JavaSdkImpl`, `SimpleJavaSdkType` and `KotlinSdkType`; PyCharm
 * adds `PythonSdkType`; WebStorm and DataGrip register only `SimpleJavaSdkType`.
 */
object VfoxSdkCatalog {

    /** Every known vfox plugin, in the order `vfox available` lists them. */
    val entries: List<VfoxCatalogEntry> = listOf(
        // --- SDKs the IDE models directly as an SdkType -------------------------------------------
        VfoxCatalogEntry(
            plugin = "java",
            title = "Java",
            support = VfoxSupport.SDK_CANDIDATE,
            // IntelliJ IDEA's Java SDK is `JavaSdkImpl` (id `JavaSDK`) and is the only type the Java
            // SDK chooser accepts there. It is deliberately the *only* name listed: the platform's
            // `SimpleJavaSdkType` exists in every product, and offering it would put JDK entries
            // into the SDK table of products with no Java support at all (WebStorm, DataGrip). The
            // Java integration is therefore IDEA/Android-Studio-only, exactly as before.
            sdkTypes = listOf("com.intellij.openapi.projectRoots.impl.JavaSdkImpl"),
            ide = "IntelliJ IDEA",
        ),
        VfoxCatalogEntry(
            plugin = "kotlin",
            title = "Kotlin",
            support = VfoxSupport.SDK_CANDIDATE,
            sdkTypes = listOf("org.jetbrains.kotlin.idea.framework.KotlinSdkType"),
            ide = "Kotlin plugin",
        ),
        VfoxCatalogEntry(
            plugin = "python",
            title = "Python",
            support = VfoxSupport.SDK_CANDIDATE,
            sdkTypes = listOf("com.jetbrains.python.sdk.PythonSdkType"),
            ide = "PyCharm / IntelliJ IDEA",
        ),

        // --- SDKs the IDE models only through a bespoke, non-SdkType list --------------------------
        VfoxCatalogEntry("golang", "Go", VfoxSupport.DEDICATED, ide = "GoLand / IntelliJ IDEA"),
        VfoxCatalogEntry("nodejs", "Node.js", VfoxSupport.DEDICATED, ide = "WebStorm / IntelliJ IDEA"),

        // --- Toolchains that are build tools rather than SDKs --------------------------------------
        VfoxCatalogEntry("gradle", "Gradle", VfoxSupport.BUILD_TOOL, ide = "Gradle plugin"),
        VfoxCatalogEntry("maven", "Maven", VfoxSupport.BUILD_TOOL, ide = "Maven plugin"),

        // --- Everything else: no IDE SDK model, offered through the environment ---------------------
        VfoxCatalogEntry("bun", "Bun", VfoxSupport.ENV_ONLY, ide = "JavaScript plugin"),
        VfoxCatalogEntry("chaosblade", "ChaosBlade", VfoxSupport.ENV_ONLY),
        VfoxCatalogEntry("clang", "Clang", VfoxSupport.ENV_ONLY, ide = "CLion (toolchain)"),
        VfoxCatalogEntry("cmake", "CMake", VfoxSupport.ENV_ONLY, ide = "CLion (CMake settings)"),
        VfoxCatalogEntry("crystal", "Crystal", VfoxSupport.ENV_ONLY),
        VfoxCatalogEntry("dart", "Dart", VfoxSupport.ENV_ONLY, ide = "Dart plugin"),
        VfoxCatalogEntry("deno", "Deno", VfoxSupport.ENV_ONLY, ide = "JavaScript plugin"),
        VfoxCatalogEntry("dotnet", ".NET", VfoxSupport.ENV_ONLY, ide = "Rider"),
        VfoxCatalogEntry("elixir", "Elixir", VfoxSupport.ENV_ONLY, ide = "Elixir plugin"),
        VfoxCatalogEntry("erlang", "Erlang", VfoxSupport.ENV_ONLY, ide = "Erlang plugin"),
        VfoxCatalogEntry("etcd", "etcd", VfoxSupport.ENV_ONLY),
        VfoxCatalogEntry("flutter", "Flutter", VfoxSupport.ENV_ONLY, ide = "Flutter plugin"),
        VfoxCatalogEntry("gcc-arm-none-eabi", "GCC ARM", VfoxSupport.ENV_ONLY, ide = "CLion (toolchain)"),
        VfoxCatalogEntry("grails", "Grails", VfoxSupport.ENV_ONLY, ide = "rides the Java SDK"),
        VfoxCatalogEntry("groovy", "Groovy", VfoxSupport.ENV_ONLY, ide = "rides the Java SDK"),
        VfoxCatalogEntry("jdtls", "JDT LS", VfoxSupport.ENV_ONLY),
        VfoxCatalogEntry("julia", "Julia", VfoxSupport.ENV_ONLY),
        VfoxCatalogEntry("kubectl", "kubectl", VfoxSupport.ENV_ONLY),
        VfoxCatalogEntry("lua", "Lua", VfoxSupport.ENV_ONLY),
        VfoxCatalogEntry("make", "Make", VfoxSupport.ENV_ONLY, ide = "CLion (toolchain)"),
        VfoxCatalogEntry("mingw64", "MinGW-w64", VfoxSupport.ENV_ONLY, ide = "CLion (toolchain)"),
        VfoxCatalogEntry("mongo", "MongoDB", VfoxSupport.ENV_ONLY, ide = "Database tools"),
        VfoxCatalogEntry("mongod", "mongod", VfoxSupport.ENV_ONLY, ide = "Database tools"),
        VfoxCatalogEntry("moonbit", "MoonBit", VfoxSupport.ENV_ONLY),
        VfoxCatalogEntry("nim", "Nim", VfoxSupport.ENV_ONLY),
        VfoxCatalogEntry("ninja", "Ninja", VfoxSupport.ENV_ONLY, ide = "CLion (toolchain)"),
        VfoxCatalogEntry("php", "PHP", VfoxSupport.ENV_ONLY, ide = "PhpStorm"),
        VfoxCatalogEntry("protobuf", "Protobuf", VfoxSupport.ENV_ONLY, ide = "Protocol Buffers plugin"),
        VfoxCatalogEntry("ruby", "Ruby", VfoxSupport.ENV_ONLY, ide = "RubyMine"),
        VfoxCatalogEntry("rust", "Rust", VfoxSupport.ENV_ONLY, ide = "RustRover"),
        VfoxCatalogEntry("scala", "Scala", VfoxSupport.ENV_ONLY, ide = "rides the Java SDK"),
        VfoxCatalogEntry("terraform", "Terraform", VfoxSupport.ENV_ONLY, ide = "Terraform plugin"),
        VfoxCatalogEntry("tinygo", "TinyGo", VfoxSupport.ENV_ONLY, ide = "rides the Go SDK"),
        VfoxCatalogEntry("tomcat", "Tomcat", VfoxSupport.ENV_ONLY, ide = "Java EE application server"),
        VfoxCatalogEntry("typst", "Typst", VfoxSupport.ENV_ONLY),
        VfoxCatalogEntry("vagrant", "Vagrant", VfoxSupport.ENV_ONLY),
        VfoxCatalogEntry("vlang", "V", VfoxSupport.ENV_ONLY),
        VfoxCatalogEntry("zig", "Zig", VfoxSupport.ENV_ONLY),
    )

    private val byPlugin: Map<String, VfoxCatalogEntry> = entries.associateBy { it.plugin }

    /** The catalogued entry for a vfox [plugin], or null when it is not one we know. */
    fun entryFor(plugin: String): VfoxCatalogEntry? = byPlugin[plugin]

    /**
     * How [plugin] is supported, defaulting to [VfoxSupport.ENV_ONLY].
     *
     * Defaulting rather than throwing is deliberate: a vfox plugin this table has never heard of is
     * still listed in the tool window and exported into the environment, which is what env-only
     * means. A new vfox plugin therefore arrives "supported" and only earns a higher level by being
     * given a row.
     */
    fun supportFor(plugin: String): VfoxSupport = byPlugin[plugin]?.support ?: VfoxSupport.ENV_ONLY

    /** The `SdkType` class names to try for [plugin], most preferred first. */
    fun sdkTypesFor(plugin: String): List<String> = byPlugin[plugin]?.sdkTypes.orEmpty()

    /** A human-readable name for [plugin], falling back to the plugin name itself. */
    fun titleFor(plugin: String): String = byPlugin[plugin]?.title ?: plugin

    /** The IDE that understands [plugin]'s tooling natively, or null. */
    fun ideFor(plugin: String): String? = byPlugin[plugin]?.ide

    /**
     * The plugins that can be registered as an IDE SDK, in catalogue order.
     *
     * These are the ones the settings page offers a toggle for: the SDK candidates and the dedicated
     * integrations. Build tools and env-only SDKs are controlled by their own settings.
     */
    fun registrablePlugins(): List<String> = entries
        .filter { it.support == VfoxSupport.SDK_CANDIDATE || it.support == VfoxSupport.DEDICATED }
        .map { it.plugin }

    /**
     * A short label for [support], for the tool window's per-row "Support" column.
     *
     * Taken from the bundle rather than hardcoded so the column follows the IDE's language.
     */
    fun shortLabel(support: VfoxSupport): String = VfoxBundle.message(
        when (support) {
            VfoxSupport.SDK_CANDIDATE -> "support.sdk"
            VfoxSupport.DEDICATED -> "support.sdk"
            VfoxSupport.BUILD_TOOL -> "support.buildTool"
            VfoxSupport.ENV_ONLY -> "support.envOnly"
        }
    )
}
