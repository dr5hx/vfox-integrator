package com.dr5hx.vfox.buildtools

import com.dr5hx.vfox.core.VfoxLog
import com.dr5hx.vfox.core.VfoxProjectResolver
import com.dr5hx.vfox.core.VfoxService
import com.dr5hx.vfox.settings.VfoxSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import org.jetbrains.idea.maven.project.MavenInSpecificPath
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Points Maven's home at a vfox Maven installation when the project asks for one.
 *
 * Maven exposes no extension point for contributing a home, so the only route is to write
 * `MavenGeneralSettings` directly. That makes this the most invasive integration, and it is
 * therefore off by default and gated on the project actually declaring a Maven version: a project
 * that never mentions Maven in its version files keeps whatever home the user configured.
 *
 * The write happens at most once per project per session (tracked in [appliedProjects]), because
 * re-applying on every open would fight the user the moment they changed the setting by hand.
 *
 * Registered from `vfox-maven.xml`: `MavenProjectsManager` and `MavenGeneralSettings` exist only in
 * the Maven plugin, so the whole service must be absent where it is not.
 */
@Service
class VfoxMavenHomeInjector {

    /** Project base paths already given a vfox Maven home this session. */
    private val appliedProjects = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Applies the project's vfox Maven home, if it declares one and the user enabled the integration.
     *
     * @return true when a home was written; false when the project asked for nothing or was skipped.
     */
    fun applyIfWanted(project: Project): Boolean {
        if (!VfoxSettings.getInstance().injectMavenHome) return false
        val basePath = project.basePath ?: return false
        if (!appliedProjects.add(basePath)) return false

        return try {
            // `effectiveVersion` is the installed version a declared value was resolved to (for
            // example a declared `3.9.15` binding the installed `3.9.16`), so a project whose
            // declaration does not match an install exactly still gets its home applied.
            val requested = VfoxProjectResolver.forLastScan(basePath)
                .firstOrNull { it.sdk == MAVEN_SDK }
                ?.effectiveVersion ?: return false

            val version = VfoxService.getInstance().versions()
                .firstOrNull { it.sdk == MAVEN_SDK && it.version == requested }
                ?: return false
            val home = version.home ?: return false

            val settings = MavenProjectsManager.getInstance(project).generalSettings
            // MavenInSpecificPath is the public value type for "a home at this path" — the obvious
            // choice over the `@Internal` resolveMavenHomeType() helper, which exists for the Maven
            // UI to parse a typed-in string and would be an internal-API usage here.
            settings.setMavenHomeType(MavenInSpecificPath(home))
            VfoxLog.note("maven: home set to $home for $basePath")
            true
        } catch (e: Exception) {
            thisLogger().debug("vfox: Maven home not applied for $basePath", e)
            appliedProjects.remove(basePath)
            false
        }
    }

    /** Forgets [project] so a later attempt may re-apply, used when the user toggles the setting on. */
    fun forget(project: Project) {
        project.basePath?.let { appliedProjects.remove(it) }
    }

    companion object {
        private const val MAVEN_SDK = "maven"

        fun getInstance(): VfoxMavenHomeInjector =
            ApplicationManager.getApplication().getService(VfoxMavenHomeInjector::class.java)
    }
}

/**
 * Applies the vfox Maven home when a project opens.
 *
 * Per-project settings mean the plugin cannot know which projects care about vfox until each loads;
 * a project that declares no Maven version is skipped cheaply by [VfoxMavenHomeInjector].
 */
class VfoxMavenProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!VfoxSettings.getInstance().injectMavenHome) return
        VfoxMavenHomeInjector.getInstance().applyIfWanted(project)
    }

    companion object {
        /** Applies to every open project, used by the settings page after the toggle changes. */
        fun applyToOpenProjects() {
            val injector = VfoxMavenHomeInjector.getInstance()
            for (project in ProjectManager.getInstance().openProjects) {
                injector.forget(project)
                injector.applyIfWanted(project)
            }
        }
    }
}
