package com.dr5hx.vfox.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent plugin settings.
 *
 * Stored at application level (not per project) because vfox itself is machine-wide: the same
 * SDKs are offered regardless of which project is open.
 */
@Service
@State(name = "VfoxSettings", storages = [Storage("vfox.xml")])
class VfoxSettings : SimplePersistentStateComponent<VfoxSettings.State>(State()) {

    class State : BaseState() {
        /**
         * Explicit path to the vfox executable, or empty to auto-detect.
         *
         * The one path worth overriding: vfox may be installed somewhere not on the IDE's `PATH`
         * (the IDE is often launched from a desktop shortcut and does not inherit a shell's `PATH`).
         *
         * The vfox *home* needs no setting: it is found from `VFOX_HOME`, `~/.vfox` or
         * `~/.version-fox`, and the SDK location inside it from `storage.sdkPath` in `config.yaml`.
         */
        var vfoxExecutable by string()

        /**
         * SDK kinds the user turned *off*, by vfox plugin name.
         *
         * Stored as the exceptions rather than the full set on purpose: a vfox plugin that appears in
         * a future release — or one newly given a row in [com.dr5hx.vfox.inject.VfoxSdkCatalog] — is
         * then registered by default without a settings migration, and the file only ever names what
         * the user actually changed their mind about.
         */
        var disabledSdks by stringSet()

        /** Add vfox paths and variables to the built-in terminal. */
        var injectTerminalEnv by property(true)

        /** Point Gradle's JVM at a vfox JDK. */
        var injectGradleJvm by property(false)

        /** Point Maven's home at a vfox Maven. */
        var injectMavenHome by property(false)

        /** Add vfox variables to Run/Debug configuration environments. */
        var injectRunConfigEnv by property(false)

        /**
         * Point each *project's* own SDK settings at the versions it declares.
         *
         * The only integration that writes a project's configuration, which is why it is off by
         * default: every other integration only offers candidates. Turning it on keeps the IDE's
         * project-level settings correct for a project that selects versions with vfox — its Node
         * runtime, its project JDK, its Go SDK — instead of leaving them on whatever was picked
         * before. Whatever the project declares wins; nothing is written for an SDK it does not
         * declare.
         */
        var applyProjectSdkDefaults by property(false)

        /** Show a balloon when a scan detects vfox SDKs. */
        var notifyOnDetect by property(true)

        /** Write each scan's result to vfox-integrator.log beside the IDE's other logs. */
        var writeLogFile by property(true)
    }

    /** True when the given SDK type should be registered as an IDE candidate. */
    fun isRegistrationEnabled(sdk: String): Boolean = sdk !in state.disabledSdks

    /** Enables or disables registration for [sdk]; called from the settings page. */
    fun setRegistrationEnabled(sdk: String, enabled: Boolean) {
        if (enabled) state.disabledSdks.remove(sdk) else state.disabledSdks.add(sdk)
    }

    /** True when the user has disabled every SDK kind they were offered. */
    fun allRegistrationsDisabled(offered: Collection<String>): Boolean =
        offered.isNotEmpty() && offered.all { !isRegistrationEnabled(it) }

    // Build-tool toggles, exposed unwrapped so call sites read `settings.injectMavenHome` rather
    // than reaching through `settings.state`. Each mirrors its `State` field of the same name.

    /** Whether vfox PATH and variables are added to the built-in terminal. */
    val injectTerminalEnv: Boolean get() = state.injectTerminalEnv

    /** Whether Gradle is offered a vfox JDK. */
    val injectGradleJvm: Boolean get() = state.injectGradleJvm

    /** Whether Maven's home is pointed at a vfox Maven. */
    val injectMavenHome: Boolean get() = state.injectMavenHome

    /** Whether vfox variables are added to Run/Debug configuration environments. */
    val injectRunConfigEnv: Boolean get() = state.injectRunConfigEnv

    /**
     * Whether each project's own SDK settings are pointed at the versions it declares.
     *
     * The application-level setting is the master switch; the write itself happens per project.
     */
    val applyProjectSdkDefaults: Boolean get() = state.applyProjectSdkDefaults

    /** Whether a balloon is shown when a scan detects vfox SDKs. */
    val notifyOnDetect: Boolean get() = state.notifyOnDetect

    /** Whether each scan is recorded in the vfox log file. */
    val writeLogFile: Boolean get() = state.writeLogFile

    companion object {
        fun getInstance(): VfoxSettings = ApplicationManager.getApplication().getService(VfoxSettings::class.java)
    }
}
