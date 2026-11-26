package com.dr5hx.vfox.buildtools

import com.dr5hx.vfox.core.VfoxProjectResolver
import com.dr5hx.vfox.settings.VfoxSettings
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.diagnostic.thisLogger
import org.jdom.Element

/**
 * Adds the project's vfox variables to JVM Run/Debug configuration processes.
 *
 * Only variables the configuration does not already set are added, so an explicit choice in the run
 * configuration always wins over the vfox default.
 *
 * This type extends `com.intellij.execution.RunConfigurationExtension`, which lives in the Java
 * plugin (`intellij.java.execution.impl.jar`, an inner module of `java-impl.jar`) rather than in the
 * platform. It is therefore registered from `vfox-java.xml` and is simply absent in products without
 * the Java plugin (WebStorm, PyCharm, ...) — those rely on the terminal customizer instead.
 */
class VfoxRunConfigExtension : RunConfigurationExtension() {

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        if (!VfoxSettings.getInstance().injectRunConfigEnv) return

        val env = try {
            val basePath = configuration.project.basePath ?: return
            val required = VfoxProjectResolver.forLastScan(basePath).map { it.sdk to it.effectiveVersion }
            VfoxEnvironment.resolveForProject(required)
        } catch (e: Exception) {
            thisLogger().debug("vfox: run configuration environment not injected", e)
            return
        }
        if (env.isEmpty) return

        // `JavaParameters.env` already carries everything the run configuration itself set, so keys
        // present there are the user's explicit choices and are left untouched.
        val configuredKeys = params.env.keys
        for ((key, value) in env.variables) {
            if (key in configuredKeys) continue
            params.addEnv(key, value)
        }
    }

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = true

    // The plugin exposes its own settings page, so the per-configuration editor hooks stay unused.
    override fun <P : RunConfigurationBase<*>> createEditor(configuration: P) = null

    override fun readExternal(configuration: RunConfigurationBase<*>, element: Element) = Unit

    override fun writeExternal(configuration: RunConfigurationBase<*>, element: Element) = Unit

    override fun isEnabledFor(
        configuration: RunConfigurationBase<*>,
        runnerSettings: RunnerSettings?,
    ): Boolean = VfoxSettings.getInstance().injectRunConfigEnv
}
