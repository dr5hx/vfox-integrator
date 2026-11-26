import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Ultimate is the compile-time target because it is the only IDEA edition that bundles all
        // four SDK ecosystems this plugin integrates with (Java, JavaScript, Python, Go). The plugin
        // itself stays product-agnostic: the same code loads in WebStorm/PyCharm/GoLand, where the
        // optional per-product config files simply do not merge.
        intellijIdeaUltimate("2026.2.5")
        testFramework(TestFrameworkType.Platform)

        // Bundled plugins used as optional, per-product integrations.
        // They are compile-only: the runtime plugin.xml declares each as an
        // <depends optional="true" config-file="..."> so the plugin still loads
        // in products that do not ship them (e.g. WebStorm has no Java plugin).
        bundledPlugin("com.intellij.java")
        bundledPlugin("JavaScript")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.jetbrains.idea.maven")

        // Go ships as a marketplace plugin rather than a bundle member, so the Go SDK injector —
        // which imports `GoSdkList` directly — needs it resolved and pinned to a compatible build.
        //
        // Python needs no such dependency: its injector is the generic `SdkTypeInjector`, which names
        // `PythonSdkType` only as a string and resolves it at run time, so no product-specific plan
        // (PythonCore) is required to compile.
        plugin("org.jetbrains.plugins.go", "262.11879.100")
    }
}

tasks.test {
    // Forward the sample-project path to the forked test JVM, so the integration test can target a
    // real project on this machine:
    //   ./gradlew test -Dvfox.sampleProject=D:/path/to/project
    // Gradle does not pass the build JVM's -D flags to a forked test JVM on its own, so without
    // this the property is always absent and the test silently skips.
    providers.systemProperty("vfox.sampleProject").orNull?.let {
        systemProperty("vfox.sampleProject", it)
    }
}

// ---------------------------------------------------------------------------------------------
// Leftover sandbox IDE cleanup
// ---------------------------------------------------------------------------------------------
//
// Each `prepareSandbox*` task copies every plugin into `.intellijPlatform/sandbox/**`, and `runIde`
// launches an IDE out of that copy. A sandbox IDE that is still running keeps the jars under its
// plugins directory memory-mapped, and on Windows overwriting a mapped file fails with
//
//   "The requested operation cannot be performed on a file with a user-mapped section open"
//
// which reads like a corrupt Gradle cache but is only a leftover process. The same leftover also
// leaves the sandbox serving the *previous* build, so a plugin change appears not to take effect
// until the sandbox is deleted by hand. Stopping those processes before the copy fixes both.
//
// The process is found through the `.pid` file the IDE writes for itself at
// `<sandbox>/<project>/<build>/system*/.pid`, **not** by matching `idea.plugins.path` out of the
// command line. The command-line route is the obvious one and does not work: on Windows the JDK
// returns an empty command line for nearly every process (the executable path is readable, the full
// command line is not), so a matcher silently finds nothing and the copy fails exactly as before.
// The `.pid` file needs no such privileges and is written by the IDE itself, so it is the reliable
// signal on every platform.
//
// Only `<sandbox>/system*/.pid` files under *this project's* sandbox are read, so an unrelated IDE
// the user has open is never affected; a process is only stopped when its own recorded pid is still
// alive *and* is a Java process, which keeps a recycled pid from being killed by a stale `.pid`.

/** The directory the plugin unpacks sandbox IDEs into; each holds `plugins`, `plugins_runIde`, ... */
val sandboxRoot: File = layout.projectDirectory.dir(".intellijPlatform/sandbox").asFile

/**
 * Registers a task that stops any IDE still running out of this project's sandbox.
 *
 * The check is cheap and a stray process can appear at any moment, so the task is never up-to-date.
 * Only values are captured for the action — a `File` and a `String` — because the project enables the
 * configuration cache (`org.gradle.configuration-cache=true` in gradle.properties) and an action that
 * reached for a script object such as `logger` would be rejected rather than cached. The logger is
 * therefore rebuilt from `Logging` inside the action, which is plain Gradle API.
 */
fun killLeftoverSandboxTask(taskName: String, sandboxSubdir: String) = tasks.register(taskName) {
    description = "Stops a leftover sandbox IDE holding $sandboxSubdir open."
    group = "intellij platform"
    outputs.upToDateWhen { false }

    val root = sandboxRoot.absolutePath
    val subdir = sandboxSubdir
    doLast {
        val log = org.gradle.api.logging.Logging.getLogger("vfox.sandbox")
        // `plugins_runIde` -> `system_runIde`; the IDE's system directory is named for the sandbox it
        // runs out of, so the pair is what tells this sandbox's pid file apart from another variant's.
        val systemDir = subdir.replaceFirst("plugins", "system")
        val pidFiles = File(root).walkTopDown()
            .filter { it.isFile && it.name == ".pid" && it.parentFile?.name == systemDir }
            .toList()
        if (pidFiles.isEmpty()) {
            log.lifecycle("vfox: no sandbox pid file for $subdir; nothing to stop")
            return@doLast
        }

        var stopped = 0
        for (pidFile in pidFiles) {
            val pid = pidFile.readText().trim().toLongOrNull() ?: continue
            val handle = ProcessHandle.of(pid).orElse(null) ?: continue
            if (!handle.isAlive) continue
            // A stale `.pid` can name a pid the OS has since handed to something else, so the target
            // must also look like the IDE's own JVM before it is stopped.
            val executable = handle.info().command().orElse("").lowercase()
            if (!executable.contains("java") && !executable.contains("idea")) {
                log.lifecycle("vfox: ignoring $pidFile (pid $pid is ${executable.ifEmpty { "unknown" }})")
                continue
            }
            if (handle.destroy()) {
                // The mapped jars are only released once the process is really gone, so wait for the
                // exit before returning; a failure to confirm it (a timeout, say) is not fatal —
                // the copy that follows reports the real problem if the handle is genuinely stuck.
                runCatching { handle.onExit().get(20, TimeUnit.SECONDS) }
                stopped++
                log.lifecycle("vfox: stopped leftover sandbox IDE (pid $pid)")
            }
        }
        if (stopped == 0) log.lifecycle("vfox: no running sandbox IDE holding $subdir")
    }
}

val killLeftoverSandboxRunIde = killLeftoverSandboxTask("killLeftoverSandboxRunIde", "plugins_runIde")
val killLeftoverSandboxTest = killLeftoverSandboxTask("killLeftoverSandboxTest", "plugins-test")
val killLeftoverSandboxDefault = killLeftoverSandboxTask("killLeftoverSandboxDefault", "plugins")

// `matching`/`configureEach` rather than `named`: the platform plugin registers these tasks lazily,
// so wiring them by exact name at this point in the script can run before they exist.
tasks.matching { it.name == "prepareSandbox_runIde" }.configureEach { dependsOn(killLeftoverSandboxRunIde) }
tasks.matching { it.name == "prepareTestSandbox" }.configureEach { dependsOn(killLeftoverSandboxTest) }
tasks.matching { it.name == "prepareSandbox" }.configureEach { dependsOn(killLeftoverSandboxDefault) }

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            // Java/Node/Python/Gradle/Maven integration touches internal APIs of
            // bundled plugins; keep the verified range tight and widen deliberately.
            untilBuild = "263.*"
        }
    }

    // -----------------------------------------------------------------------------------------
    // Signing and publishing (JetBrains Marketplace)
    // -----------------------------------------------------------------------------------------
    //
    // Every secret comes from the environment and never from a file inside the repository:
    //
    //   CERTIFICATE_CHAIN / PRIVATE_KEY / PRIVATE_KEY_PASSWORD  sign the distribution
    //   PUBLISH_TOKEN                                          authorizes the upload
    //
    // `scripts/publish.ps1` exports those from PEM files kept outside the working tree and then
    // runs `publishPlugin`; CI does the same from repository secrets. When no key is present the
    // signing step is skipped entirely, so an ordinary `./gradlew buildPlugin` needs none of it.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")

        // A pre-release belongs on a non-default channel: the Marketplace keeps `alpha` as a
        // separate repository (https://plugins.jetbrains.com/plugins/alpha/list) instead of
        // releasing to everyone, which is what a 1.0.0-alpha should be. Promoting it to the
        // default channel is a deliberate release decision, so it comes from a property rather
        // than being hardcoded here.
        channels = listOf(providers.gradleProperty("vfoxPublishChannel").getOrElse("alpha"))
    }

    pluginVerification {
        // Experimental and internal API usages are expected and deliberate (see the README): the
        // Gradle JVM resolver, the terminal customizer and the Maven/Node/Go/Python integrations all
        // build on bundled-plugin APIs that carry no compatibility guarantee. They are isolated and
        // failure-tolerant by design, so they are reported but not treated as build failures.
        //
        // Compatibility problems and structural issues stay fatal: those mean the plugin would not
        // load or would break at runtime, which is exactly what this task exists to catch.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.COMPATIBILITY_WARNINGS,
        )

        ides {
            // Verify against the IDEs installed locally rather than downloading them. Using `local`
            // rather than `recommended()`/`create()` matters for cross-product coverage: it checks
            // the plugin against the products that lack some optional integrations — WebStorm (no
            // Java plugin), PyCharm (no Java, no Go) and DataGrip (nothing but the platform) — which
            // is exactly where a broken per-product config file would surface. It also avoids
            // pulling several gigabytes of IDE distributions on every run.
            //
            // Directories are discovered by looking for an IDE's `build.txt` rather than by name, so
            // a Toolbox install named "PyCharm 2" or a differently-spelled directory is still found.
            // Set `localIdeRoot` in gradle.properties to point elsewhere. When nothing is found the
            // build falls back to `recommended()` rather than silently checking nothing.
            val root = providers.gradleProperty("localIdeRoot").orNull
                ?: "${System.getProperty("user.home")}/AppData/Local/Programs"
            val localIdes = File(root).listFiles()
                ?.filter { it.isDirectory && File(it, "build.txt").isFile }
                ?.sortedBy { it.name }
                .orEmpty()
            if (localIdes.isEmpty()) {
                logger.lifecycle("vfox: no local IDEs under $root; falling back to recommended()")
                recommended()
            } else {
                logger.lifecycle("vfox: verifying against ${localIdes.joinToString { it.name }}")
                localIdes.forEach { local(it) }
            }
        }
    }
}
