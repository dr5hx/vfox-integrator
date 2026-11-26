package com.dr5hx.vfox.ui

import com.dr5hx.vfox.VfoxBundle
import com.dr5hx.vfox.inject.InjectResult
import com.dr5hx.vfox.model.VfoxScan
import com.dr5hx.vfox.model.VfoxSdkVersion
import com.dr5hx.vfox.settings.VfoxSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * Shows the balloon reported after a scan finds vfox SDKs.
 *
 * Notifications are the plugin's only unsolicited UI, so they are deliberately sparse: one balloon
 * per scan that actually produced something to say, and nothing at all when the user has turned
 * every integration off or when vfox is simply not installed. The `vfox` notification group is
 * declared in `plugin.xml` and is `logByDefault`, so every balloon also lands in `idea.log`.
 */
object VfoxNotifier {

    /** The group id declared in `plugin.xml`. */
    private const val GROUP_ID = "vfox"

    /** One sentence describing the versions a scan found, or null when there is nothing to say. */
    internal fun scanSummary(versions: List<VfoxSdkVersion>): String? {
        if (versions.isEmpty()) return null

        return versions.groupBy { it.sdk }
            .toSortedMap()
            .entries
            .joinToString(", ") { (sdk, versionsOfSdk) ->
                "$sdk (${versionsOfSdk.size})"
            }
    }

    /**
     * Reports a completed scan, if it found anything worth telling the user about.
     *
     * @param scan the scan result; an empty one is reported only when it carries an error.
     * @param registered counts by outcome from registration, or null when nothing was registered.
     * @param project the project to attach the balloon to, or null for an application-wide one.
     */
    fun reportScan(
        scan: VfoxScan,
        registered: Map<InjectResult, Int>? = null,
        project: Project? = null,
    ) {
        if (!VfoxSettings.getInstance().notifyOnDetect) return

        val versions = scan.allVersions()
        val summary = scanSummary(versions)

        if (summary == null) {
            // Nothing found: only worth a balloon when an error explains why.
            scan.error?.let {
                notify(VfoxBundle.message("notification.none.title"), it, NotificationType.WARNING, project)
            }
            return
        }

        val added = registered?.get(InjectResult.ADDED) ?: 0
        val present = registered?.get(InjectResult.ALREADY_PRESENT) ?: 0
        val failed = registered?.get(InjectResult.FAILED) ?: 0

        val title = VfoxBundle.message("notification.detected.title", versions.size)
        val body = buildString {
            append(summary)
            if (registered != null) {
                append("\n\n")
                append(VfoxBundle.message("notification.registered", added, present))
                if (failed > 0) append(VfoxBundle.message("notification.failed", failed))
            }
        }

        val type = if (failed > 0) NotificationType.WARNING else NotificationType.INFORMATION
        notify(title, body, type, project)
    }

    /** Reports that a scan could not run at all. */
    fun reportFailure(message: String, project: Project? = null) {
        notify(VfoxBundle.message("notification.scanFailed.title"), message, NotificationType.ERROR, project)
    }

    private fun notify(title: String, content: String, type: NotificationType, project: Project?) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(title, content, type)
                .notify(project)
        } catch (e: Exception) {
            // A missing group (e.g. a trimmed distribution) must not escalate into a plugin error.
            thisLogger().debug("vfox: notification could not be shown", e)
        }
    }
}
