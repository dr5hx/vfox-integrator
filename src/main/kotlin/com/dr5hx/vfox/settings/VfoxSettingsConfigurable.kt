package com.dr5hx.vfox.settings

import com.dr5hx.vfox.VfoxBundle
import com.dr5hx.vfox.core.VfoxCliRunner
import com.dr5hx.vfox.core.VfoxLog
import com.dr5hx.vfox.inject.SdkInjectors
import com.dr5hx.vfox.inject.VfoxSdkCatalog
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page under Settings → Tools → vfox SDKs.
 *
 * Controls where vfox is found, which SDK kinds are offered as candidates, and which build-tool
 * integrations are active. Build-tool injection defaults to off because it changes how builds run;
 * SDK registration defaults to on because it only adds candidates.
 *
 * The SDK-kind checkboxes are built from [VfoxSdkCatalog] filtered to the kinds *this product* can
 * actually register ([SdkInjectors.registrablePlugins]), so the page shows a Java toggle in IntelliJ
 * IDEA but not in DataGrip, where there is no Java SDK model to register into.
 *
 * Every string comes from [VfoxBundle], so the page follows the IDE's own language: a language pack
 * translates the platform's bundles only and cannot reach a plugin's compiled-in text, so any string
 * written inline here would stay English whatever language the IDE runs in.
 */
class VfoxSettingsConfigurable : Configurable {

    /**
     * The vfox executable found by auto-detection, or null when there is none.
     *
     * Shown in the field when nothing is configured, so the path actually in use is visible rather
     * than the field reading as empty and leaving "which vfox is this using?" unanswerable.
     */
    private val detectedExecutable: String? by lazy {
        VfoxCliRunner.locateExecutable(null)?.absolutePath
    }

    private val vfoxExecutable = TextFieldWithBrowseButton().apply {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withTitle(VfoxBundle.message("settings.executable.chooserTitle"))
        addBrowseFolderListener(null, descriptor)
        // A path field needs no more than the window it sits in: letting it keep its preferred width
        // is what made it stretch across the whole page. A fixed, scaled column count gives it a
        // sane size while the browse button stays pinned to the text field's right edge.
        textField.columns = 30
    }

    /** One checkbox per SDK kind this product can register, in catalogue order. */
    private val sdkCheckboxes: Map<String, JBCheckBox> =
        SdkInjectors.registrablePlugins().associateWith { plugin ->
            JBCheckBox(VfoxBundle.message("settings.candidates.register", VfoxSdkCatalog.titleFor(plugin)))
        }

    private val injectTerminalEnv = JBCheckBox(VfoxBundle.message("settings.buildTools.terminal"))
    private val injectGradleJvm = JBCheckBox(VfoxBundle.message("settings.buildTools.gradle"))
    private val injectMavenHome = JBCheckBox(VfoxBundle.message("settings.buildTools.maven"))
    private val injectRunConfigEnv = JBCheckBox(VfoxBundle.message("settings.buildTools.runConfig"))

    /**
     * Whether each project's own SDK settings are pointed at the versions it declares.
     *
     * Worded for what it does rather than for one SDK: whatever the project declares in vfox wins,
     * be that its Node runtime, its project JDK or its Go SDK.
     */
    private val applyProjectSdkDefaults = JBCheckBox(VfoxBundle.message("settings.buildTools.projectSdks"))

    private val notifyOnDetect = JBCheckBox(VfoxBundle.message("settings.detection.notify"))
    private val writeLogFile = JBCheckBox(VfoxBundle.message("settings.detection.logFile"))

    override fun getDisplayName(): String = VfoxBundle.message("settings.displayName")

    /** The executable path to show: the configured one when set, else the detected one. */
    private fun executableToShow(): String =
        VfoxSettings.getInstance().state.vfoxExecutable?.takeIf { it.isNotBlank() }
            ?: detectedExecutable.orEmpty()

    override fun createComponent(): JComponent {
        // Prefill before the panel is shown; `reset()` also does this, but it is not guaranteed to
        // have run by the time the component is first painted.
        vfoxExecutable.text = executableToShow()
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }
        panel.add(sectionLabel(VfoxBundle.message("settings.section.location")))
        panel.add(hint(detectedExecutableHint()))
        panel.add(labelledRow(VfoxBundle.message("settings.executable.label"), vfoxExecutable))
        panel.add(JBLabel())
        panel.add(sectionLabel(VfoxBundle.message("settings.section.candidates")))
        if (sdkCheckboxes.isEmpty()) {
            panel.add(hint(VfoxBundle.message("settings.candidates.none")))
        } else {
            sdkCheckboxes.values.forEach { panel.add(it) }
            panel.add(hint(VfoxBundle.message("settings.candidates.note")))
        }
        panel.add(JBLabel())
        panel.add(sectionLabel(VfoxBundle.message("settings.section.buildTools")))
        panel.add(injectTerminalEnv)
        panel.add(injectGradleJvm)
        panel.add(injectMavenHome)
        panel.add(injectRunConfigEnv)
        panel.add(applyProjectSdkDefaults)
        panel.add(hint(VfoxBundle.message("settings.buildTools.note")))
        panel.add(JBLabel())
        panel.add(sectionLabel(VfoxBundle.message("settings.section.detection")))
        panel.add(notifyOnDetect)
        panel.add(writeLogFile)
        panel.add(hint(VfoxBundle.message("settings.detection.logFilePath", VfoxLog.logFilePath())))
        return panel
    }

    private fun sectionLabel(text: String) = JBLabel(text).apply {
        border = JBUI.Borders.emptyTop(6)
    }

    /**
     * Explains the executable field, naming the auto-detected path.
     *
     * The field shows the detected path so it is visible; the hint says what leaving it there means,
     * so a shown path is not mistaken for a pinned setting.
     */
    private fun detectedExecutableHint(): String = if (detectedExecutable != null) {
        VfoxBundle.message("settings.executable.detectedHint")
    } else {
        VfoxBundle.message("settings.executable.notFoundHint")
    }

    private fun hint(text: String) = JBLabel(text).apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }

    /**
     * A label-and-field row.
     *
     * The label and the field are each pinned to their own width; the trailing glue takes whatever
     * space is left. `BoxLayout` sizes a component by its *maximum*, and both a label's and a text
     * field's maximum is unbounded, so without pinning them one of the two grows — the field
     * stretching to the width of the page, or the gap between label and field opening up.
     *
     * The label keeps its own preferred width rather than a fixed one because it is translated, and
     * a width that fits "vfox executable:" would clip the longer Chinese label.
     */
    private fun labelledRow(label: String, field: JComponent) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.emptyTop(2)
        add(JBLabel(label).apply {
            maximumSize = JBUI.size(preferredSize.width, preferredSize.height)
        })
        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(field.apply { maximumSize = preferredSize })
        add(Box.createHorizontalGlue())
    }

    override fun isModified(): Boolean {
        val state = VfoxSettings.getInstance().state
        return configuredExecutable() != state.vfoxExecutable ||
            sdkCheckboxes.any { (plugin, box) ->
                // A checked box means "enabled", stored as the negation of the disabled set.
                box.isSelected == (plugin in state.disabledSdks)
            } ||
            injectTerminalEnv.isSelected != state.injectTerminalEnv ||
            injectGradleJvm.isSelected != state.injectGradleJvm ||
            injectMavenHome.isSelected != state.injectMavenHome ||
            injectRunConfigEnv.isSelected != state.injectRunConfigEnv ||
            applyProjectSdkDefaults.isSelected != state.applyProjectSdkDefaults ||
            notifyOnDetect.isSelected != state.notifyOnDetect ||
            writeLogFile.isSelected != state.writeLogFile
    }

    /**
     * The executable the field asks for, with the shown auto-detected path treated as "unset".
     *
     * The detected path is displayed so it is visible, but leaving it there is not a choice: it is
     * stored as an empty setting so auto-detection keeps working (and keeps working if vfox later
     * moves). Only a path the user actually typed is saved.
     */
    private fun configuredExecutable(): String {
        val text = vfoxExecutable.text.trim()
        return if (text == detectedExecutable) "" else text
    }

    override fun apply() {
        val settings = VfoxSettings.getInstance()
        val state = settings.state
        state.vfoxExecutable = configuredExecutable()
        sdkCheckboxes.forEach { (plugin, box) -> settings.setRegistrationEnabled(plugin, box.isSelected) }
        state.injectTerminalEnv = injectTerminalEnv.isSelected
        state.injectGradleJvm = injectGradleJvm.isSelected
        state.injectMavenHome = injectMavenHome.isSelected
        state.injectRunConfigEnv = injectRunConfigEnv.isSelected
        state.applyProjectSdkDefaults = applyProjectSdkDefaults.isSelected
        state.notifyOnDetect = notifyOnDetect.isSelected
        state.writeLogFile = writeLogFile.isSelected
    }

    override fun reset() {
        val settings = VfoxSettings.getInstance()
        val state = settings.state
        // Show the executable that is actually in use: the configured one, or the path detection
        // found. An empty field would leave "which vfox is this using?" unanswerable.
        vfoxExecutable.text = executableToShow()
        sdkCheckboxes.forEach { (plugin, box) -> box.isSelected = settings.isRegistrationEnabled(plugin) }
        injectTerminalEnv.isSelected = state.injectTerminalEnv
        injectGradleJvm.isSelected = state.injectGradleJvm
        injectMavenHome.isSelected = state.injectMavenHome
        injectRunConfigEnv.isSelected = state.injectRunConfigEnv
        applyProjectSdkDefaults.isSelected = state.applyProjectSdkDefaults
        notifyOnDetect.isSelected = state.notifyOnDetect
        writeLogFile.isSelected = state.writeLogFile
    }
}
