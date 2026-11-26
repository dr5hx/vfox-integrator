package com.dr5hx.vfox.buildtools

import com.dr5hx.vfox.core.VfoxProjectResolver
import com.dr5hx.vfox.core.VfoxService
import com.dr5hx.vfox.model.VfoxSdkVersion
import com.dr5hx.vfox.settings.VfoxSettings
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ui.configuration.SdkLookupProvider
import com.intellij.openapi.roots.ui.configuration.SdkLookupProvider.SdkInfo
import org.jetbrains.plugins.gradle.resolvers.GradleJvmResolver
import org.jetbrains.plugins.gradle.settings.GradleSettings

/**
 * Offers a vfox JDK to Gradle as the JVM to run the build with.
 *
 * Gradle resolves its JVM through a chain of [GradleJvmResolver]s, each answering for one way of
 * naming a JVM (a `gradle.properties` macro, a toolchain, a registered JDK, ...). This resolver
 * answers for the synthetic names [VfoxGradleJvm.PREFIX] + `<sdk>-<version>`, so a vfox JDK appears
 * in Settings → Build Tools → Gradle → Gradle JVM where a hand-registered JDK would.
 *
 * It only *resolves*; it never writes the project's Gradle JVM setting. Choosing one stays a user
 * action, matching the plugin's contract of offering candidates rather than rewriting configuration.
 *
 * `GradleJvmResolver` is experimental (no compatibility guarantee), so failures are logged and
 * degraded to "undefined" rather than escaping into the IDE's SDK lookup.
 */
class VfoxGradleJvmResolver : GradleJvmResolver() {

    override fun canBeResolved(gradleJvm: String): Boolean =
        VfoxGradleJvm.isEnabled() && VfoxGradleJvm.isVfoxName(gradleJvm)

    override fun getResolvedSdkInfo(
        project: Project,
        projectSdk: Sdk?,
        externalProjectPath: String?,
        sdkLookupProvider: SdkLookupProvider,
    ): SdkInfo {
        if (!VfoxGradleJvm.isEnabled()) return SdkInfo.Undefined

        return try {
            val selected = selectedJvmName(project, externalProjectPath)
            if (!VfoxGradleJvm.isVfoxName(selected)) return SdkInfo.Undefined

            val version = VfoxGradleJvm.versionFor(javaVersionRequestedBy(project))
                ?: return SdkInfo.Undefined
            // createSdkInfo fills in the version string itself and returns SdkInfo.Undefined for a
            // null home, so unknown homes degrade safely without an extra null check here.
            createSdkInfo(VfoxGradleJvm.nameFor(version), version.home)
        } catch (e: Exception) {
            thisLogger().debug("vfox: Gradle JVM could not be resolved", e)
            SdkInfo.Undefined
        }
    }

    /**
     * The JVM name the project's Gradle settings select.
     *
     * `gradleJvm` is not handed to [getResolvedSdkInfo], so it is read back from the settings for the
     * linked project — which is why the name is compared here rather than in the caller's dispatch.
     */
    private fun selectedJvmName(project: Project, externalProjectPath: String?): String? {
        val settings = GradleSettings.getInstance(project)
        val linked = externalProjectPath?.let { settings.getLinkedProjectSettings(it) }
            ?: settings.linkedProjectsSettings.firstOrNull()
        return linked?.gradleJvm
    }

    /**
     * The Java version the project's vfox files ask for, or null when it declares none — vfox's
     * active Java is then used, so a project without version files still resolves to a real JDK.
     */
    private fun javaVersionRequestedBy(project: Project): String? {
        val basePath = project.basePath ?: return null
        // The resolved (installed) version, so a declared `11` binds the installed `11.0.16+8-librca`
        // rather than failing the exact lookup in `VfoxGradleJvm.versionFor`.
        return VfoxProjectResolver.forLastScan(basePath)
            .firstOrNull { it.sdk == VfoxGradleJvm.JAVA_SDK }
            ?.effectiveVersion
    }
}

/**
 * The naming, lookup and settings-gate shared by the Gradle JVM resolver and the settings UI.
 *
 * Keeping it in one place means the name the resolver answers for and the name the UI offers can
 * never drift apart.
 */
object VfoxGradleJvm {

    const val PREFIX = "vfox-"

    /** The vfox SDK name backing a Gradle JVM. */
    const val JAVA_SDK = "java"

    /** Whether the user asked for Gradle JVM injection. */
    fun isEnabled(): Boolean = VfoxSettings.getInstance().injectGradleJvm

    /** Whether [gradleJvm] is one of the names this plugin answers for. */
    fun isVfoxName(gradleJvm: String?): Boolean =
        gradleJvm != null && gradleJvm.startsWith(PREFIX)

    /** The Gradle-JVM name offered for [version], e.g. `vfox-java-21.0.2+14-librca`. */
    fun nameFor(version: VfoxSdkVersion): String = "$PREFIX${version.displayName}"

    /**
     * The vfox Java version to resolve to.
     *
     * @param requested the version the project asks for, or null to fall back to vfox's active Java.
     */
    fun versionFor(requested: String?): VfoxSdkVersion? {
        val javaVersions = VfoxService.getInstance().versions()
            .filter { it.sdk == JAVA_SDK && it.home != null }
        if (javaVersions.isEmpty()) return null

        val exact = requested?.let { want -> javaVersions.firstOrNull { it.version == want } }
        return exact ?: javaVersions.firstOrNull { it.active } ?: javaVersions.first()
    }

    /** Every Gradle-JVM name this plugin offers, in the order the settings combo should show them. */
    fun offeredNames(): List<String> {
        val javaVersions = VfoxService.getInstance().versions()
            .filter { it.sdk == JAVA_SDK && it.home != null }
        return javaVersions
            .sortedWith(compareByDescending<VfoxSdkVersion> { it.active }.thenBy { it.version })
            .map { nameFor(it) }
    }
}
