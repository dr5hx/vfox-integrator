package com.dr5hx.vfox.ui

import com.dr5hx.vfox.VfoxBundle
import com.dr5hx.vfox.core.VfoxLog
import com.dr5hx.vfox.core.VfoxProjectResolver
import com.dr5hx.vfox.core.VfoxService
import com.dr5hx.vfox.core.VfoxScanListener
import com.dr5hx.vfox.inject.InjectResult
import com.dr5hx.vfox.inject.SdkInjectorRegistry
import com.dr5hx.vfox.inject.VfoxCandidates
import com.dr5hx.vfox.inject.VfoxSdkCatalog
import com.dr5hx.vfox.model.VfoxScan
import com.dr5hx.vfox.model.VfoxScope
import com.dr5hx.vfox.model.VfoxSdkVersion
import com.dr5hx.vfox.settings.VfoxSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/**
 * The vfox tool window: shows what the last scan found and lets the user re-scan or register.
 *
 * Registration is an explicit action here, matching the plugin's contract: scanning only offers
 * SDKs as candidates; nothing rewrites the project's configured SDKs.
 */
class VfoxToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = VfoxToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

/** Content of the vfox tool window. */
private class VfoxToolWindowPanel(private val project: Project) : com.intellij.openapi.Disposable {

    private companion object {
        /** Dialog title for the tool window's messages; a product name, the same in every language. */
        const val TITLE = "vfox"
    }

    private val model = SdkTableModel()
    private val table = object : JBTable(model) {
        /**
         * The full cell text as a tooltip.
         *
         * Columns are sized to their content, so a long SDK home is deliberately cut short rather
         * than allowed to stretch the column across the window; the tooltip is how the whole path
         * stays readable.
         */
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            val column = columnAtPoint(event.point)
            if (row < 0 || column < 0) return null
            return getValueAt(row, column)?.toString()?.takeIf { it.isNotBlank() }
        }
    }
    private val statusLabel = JBLabel()
    private val rescanButton = JButton(VfoxBundle.message("toolwindow.action.rescan"), AllIcons.Actions.Refresh)
    private val registerButton =
        JButton(VfoxBundle.message("toolwindow.action.register"), AllIcons.Actions.AddList)
    private val logButton = JButton(VfoxBundle.message("toolwindow.action.openLog"), AllIcons.Actions.Show)

    /** Set once the tool window is closed, so a late scan does not repaint a discarded table. */
    @Volatile
    private var disposed = false

    val component: JBPanel<*> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.empty(6)

        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(rescanButton)
            add(registerButton)
            add(logButton)
        }
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    init {
        table.rowHeight = JBUI.scale(22)
        table.fillsViewportHeight = true
        // Columns keep the width their content needs and the table scrolls sideways, instead of the
        // columns being squeezed equally into the window's width — which in a narrow tool window
        // leaves every header clipped to "已…", "支…".
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        // The table's own "nothing to show" text is English-only (it comes from the platform's
        // bundle, which the language pack does translate) — but the state it describes here is the
        // plugin's own, so it is set from the plugin's bundle to read right in either language.
        table.emptyText.text = VfoxBundle.message("toolwindow.empty")
        rescanButton.addActionListener { rescan() }
        registerButton.addActionListener { register() }
        logButton.addActionListener { openLog() }
        // Subscribe *before* reading the cache: the start-up scan runs in the background, so it may
        // finish either side of this panel being built. Subscribing first means a scan that lands
        // in between refreshes the table, while one that already landed is picked up by the read —
        // without this the window sits on "not scanned yet" until the user rescans by hand.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(VfoxService.SCAN_COMPLETED, VfoxScanListener { scan ->
                ApplicationManager.getApplication().invokeLater { if (!disposed) render(scan) }
            })
        render(VfoxService.getInstance().lastScan())
    }

    /** Re-scans vfox off the EDT; the result arrives through the scan-completed topic. */
    private fun rescan() {
        rescanButton.isEnabled = false
        statusLabel.text = VfoxBundle.message("toolwindow.status.scanning")
        ApplicationManager.getApplication().executeOnPooledThread {
            // `rescan` publishes the result, so the table is refreshed by the same path a start-up
            // scan takes — one refresh route rather than two that could disagree.
            val scan = VfoxService.getInstance().rescan()
            ApplicationManager.getApplication().invokeLater {
                rescanButton.isEnabled = true
                VfoxNotifier.reportScan(scan, project = project)
            }
        }
    }

    /** Opens the vfox log file, or says where it would be when it does not exist yet. */
    private fun openLog() {
        val file = VfoxLog.logFile()
        if (file == null || !file.isFile) {
            Messages.showInfoMessage(
                project,
                VfoxBundle.message("toolwindow.log.missing", VfoxLog.logFilePath()),
                TITLE,
            )
            return
        }
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)
        if (virtual != null) {
            // The single-argument overload is the non-deprecated one; the `boolean focus` variant is
            // deprecated, and focusing is the default anyway.
            FileEditorManager.getInstance(project).openFile(virtual)
        } else {
            Messages.showInfoMessage(project, VfoxBundle.message("toolwindow.log.path", file.absolutePath), TITLE)
        }
    }

    /** Registers every discovered version the settings allow. */
    private fun register() {
        val scan = VfoxService.getInstance().lastScan()
        if (scan == null || scan.isEmpty) {
            Messages.showInfoMessage(project, VfoxBundle.message("toolwindow.register.none"), TITLE)
            return
        }
        val settings = VfoxSettings.getInstance()
        // Both the machine-wide versions and the current project's own `.vfox/sdks` links are
        // offered, reduced to one entry per real executable: a link and the store it points at are
        // the same binary, and registering both would show the same tool twice.
        val versions = VfoxCandidates.distinctByRealPath(scan.allVersions() + projectCandidates())
        val counts = SdkInjectorRegistry.registerAll(versions, settings::isRegistrationEnabled)
        VfoxLog.registration(counts)
        val added = counts[InjectResult.ADDED] ?: 0
        val present = counts[InjectResult.ALREADY_PRESENT] ?: 0
        val failed = counts[InjectResult.FAILED] ?: 0
        val message = buildString {
            append(VfoxBundle.message("toolwindow.register.result", added, present))
            if (failed > 0) append(VfoxBundle.message("toolwindow.register.failed", failed, VfoxLog.logFilePath()))
        }
        Messages.showInfoMessage(project, message, TITLE)
    }

    /** Renders a scan result, highlighting what the current project asks for. */
    private fun render(scan: VfoxScan?) {
        if (scan == null) {
            fill(emptyList(), emptySet())
            // Reset the colour: the status label is reused, so a previous scan's error red would
            // otherwise colour an unrelated message.
            statusLabel.foreground = null
            statusLabel.text = VfoxBundle.message("toolwindow.status.notScanned")
            return
        }
        if (scan.error != null && scan.isEmpty) {
            fill(emptyList(), emptySet())
            statusLabel.text = scan.error
            statusLabel.foreground = JBColor.RED
            return
        }

        val requirements = resolveProjectRequirements()
        val links = projectCandidates()
        // The project's own `.vfox/sdks` links are listed alongside the machine-wide versions,
        // reduced to one row per real executable: a link and the store it points at are the same
        // binary, so listing both would show one Node twice. The link survives (it ranks first) and
        // the *Original* column shows the store it resolves to, so both paths stay visible.
        val rows = VfoxCandidates.distinctByRealPath(scan.allVersions() + links)
        fill(rows, requirements.map { "${it.sdk}@${it.effectiveVersion}" }.toSet())
        statusLabel.foreground = null
        statusLabel.text = buildString {
            append(VfoxBundle.message("toolwindow.status.home", scan.home ?: VfoxBundle.message("toolwindow.status.homeNotFound")))
            // Count what the table actually shows, not the raw scan: the project's links are added
            // and duplicates (a link and the store it points at) are collapsed, so the raw total
            // would disagree with the rows on screen.
            append("  |  ")
            append(VfoxBundle.message("toolwindow.status.versionCount", rows.size))
            if (requirements.isNotEmpty()) {
                append("  |  ")
                append(VfoxBundle.message("toolwindow.status.projectWants", requirements.joinToString(", ") { it.describe() }))
            }
        }
    }

    /**
     * Replaces the table's rows and re-sizes the columns to fit them.
     *
     * Every path that changes the rows goes through here, including the empty ones, because the
     * header widths depend on the content: an empty table still has headers to lay out, and leaving
     * them at the default width is what clipped them to "已…", "支…".
     */
    private fun fill(versions: List<VfoxSdkVersion>, required: Set<String>) {
        model.update(versions, required)
        sizeColumnsToContent()
    }

    /**
     * Widens each column to fit its header and its cells.
     *
     * Applied after every render because the content changes: a scan loads new rows, and the
     * columns then need the width of *those* rows. The width is driven by the renderer's preferred
     * size rather than a character count, so a translated header and a long path are both measured
     * as they will actually be drawn. A very long value (a deep SDK home) is capped, with the
     * full text left to the cell's tooltip.
     */
    private fun sizeColumnsToContent() {
        val margin = JBUI.scale(16)
        val maxWidth = JBUI.scale(420)
        for (column in 0 until model.columnCount) {
            var width = 0
            // Row -1 is the header, which is rendered with different attributes from the cells (bold
            // under some themes), so it is measured with the header's own renderer.
            for (row in -1 until model.rowCount) {
                val component = if (row < 0) {
                    table.tableHeader.defaultRenderer.getTableCellRendererComponent(
                        table, model.getColumnName(column), false, false, row, column,
                    )
                } else {
                    table.getCellRenderer(row, column).getTableCellRendererComponent(
                        table, model.getValueAt(row, column), false, false, row, column,
                    )
                }
                width = maxOf(width, component.preferredSize.width + margin)
            }
            table.columnModel.getColumn(column).preferredWidth = width.coerceAtMost(maxWidth)
        }
    }

    /** What the current project declares, resolved against the versions the last scan found. */
    private fun resolveProjectRequirements(): List<VfoxProjectResolver.Requirement> {
        val basePath = project.basePath ?: return emptyList()
        return runCatching { VfoxProjectResolver.forLastScan(basePath) }.getOrDefault(emptyList())
    }

    /**
     * The current project's `.vfox/sdks` links as candidates.
     *
     * Offered *in addition* to the machine-wide versions, because the link is the path the project
     * itself selects — and the path the IDE's own Node interpreter configuration records.
     */
    private fun projectCandidates(): List<VfoxSdkVersion> {
        val basePath = project.basePath ?: return emptyList()
        return runCatching {
            VfoxProjectResolver().projectCandidates(basePath, VfoxService.getInstance().versions())
        }.getOrDefault(emptyList())
    }

    override fun dispose() {
        disposed = true
    }

    /** Table model over the discovered versions. */
    private class SdkTableModel : AbstractTableModel() {
        private val columns = arrayOf(
            "toolwindow.column.sdk",
            "toolwindow.column.version",
            "toolwindow.column.scope",
            "toolwindow.column.active",
            "toolwindow.column.project",
            "toolwindow.column.support",
            "toolwindow.column.home",
            "toolwindow.column.original",
        )
        private var rows: List<Row> = emptyList()

        fun update(versions: List<VfoxSdkVersion>, required: Set<String>) {
            rows = versions.map { version ->
                Row(
                    sdk = version.sdk,
                    version = version.version,
                    scope = VfoxBundle.message(
                        if (version.scope == VfoxScope.PROJECT) "toolwindow.scope.project" else "toolwindow.scope.global"
                    ),
                    active = version.active,
                    requested = required.contains("${version.sdk}@${version.version}"),
                    // What the IDE can do with this SDK kind, from the catalogue, so a row that is
                    // not an SDK candidate reads as "env only" rather than as a missing feature.
                    support = VfoxSdkCatalog.shortLabel(VfoxSdkCatalog.supportFor(version.sdk)),
                    // For a project link, the link path is the home and the store it resolves to is
                    // the "original"; both are shown so neither the selection nor the installation
                    // is hidden.
                    home = version.home.orEmpty(),
                    original = version.storageHome.orEmpty(),
                )
            }
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = VfoxBundle.message(columns[column])

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.sdk
                1 -> row.version
                2 -> row.scope
                3 -> if (row.active) VfoxBundle.message("toolwindow.detail.active") else ""
                4 -> if (row.requested) VfoxBundle.message("toolwindow.detail.requested") else ""
                5 -> row.support
                6 -> row.home
                else -> row.original
            }
        }

        private data class Row(
            val sdk: String,
            val version: String,
            val scope: String,
            val active: Boolean,
            val requested: Boolean,
            val support: String,
            val home: String,
            val original: String,
        )
    }
}
