package com.sneakin.ui

import com.sneakin.core.PayloadLibrary
import com.sneakin.model.ComparisonMode
import com.sneakin.model.GlobalConfig
import com.sneakin.model.Preset
import com.sneakin.model.ScanConfig
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Config sub-tab: scan knobs bound live to the tab's [ScanConfig] + the Payload Editor (Section 8). */
class ConfigPanel(
    private val config: ScanConfig,
    private val global: GlobalConfig,
    private val payloadLibrary: PayloadLibrary,
) : JPanel(BorderLayout()) {

    init {
        val inner = JTabbedPane()
        inner.addTab("Settings", JScrollPane(SettingsForm(config, global)))
        inner.addTab("Payloads", JScrollPane(PayloadEditorPanel(payloadLibrary)))
        add(inner, BorderLayout.CENTER)
    }
}

/**
 * A form whose controls write straight into [config]. Edits take effect on the next scan. "Reset
 * to defaults" restores [config] from [global] and reloads every control.
 */
private class SettingsForm(
    private val config: ScanConfig,
    private val global: GlobalConfig,
) : JPanel() {

    private val reloaders = ArrayList<() -> Unit>()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(section("Threshold preset") {
            add(row("Preset", combo(Preset.entries.toTypedArray(), { config.preset }) { config.preset = it }))
        })

        add(section("Definition of normal / success") {
            add(row("", check("Treat any 2xx as success", { config.matchStatusClass2xx }) { config.matchStatusClass2xx = it }))
            add(row("Success status list", textField(24, { statusListText() }) { applyStatusList(it) }, "comma-separated, overrides 2xx"))
            add(row("Positive match (present)", textField(20, { config.positiveMatch }) { config.positiveMatch = it },
                "marks success, e.g. \"success\":true"))
            add(row("", check("positive is regex", { config.positiveMatchIsRegex }) { config.positiveMatchIsRegex = it },
                check("use positive", { config.usePositive }) { config.usePositive = it }))
            add(row("Negative match (absent)", textField(20, { config.negativeMatch }) { config.negativeMatch = it },
                "marks a validation rejection"))
            add(row("", check("negative is regex", { config.negativeMatchIsRegex }) { config.negativeMatchIsRegex = it },
                check("use negative", { config.useNegative }) { config.useNegative = it }))
            add(row("Length tolerance %", doubleSpinner(0.0, 100.0, 1.0, { config.lengthTolerancePercent }) { config.lengthTolerancePercent = it },
                check("use length", { config.useLength }) { config.useLength = it }))
            add(row("Timing tolerance ms", intSpinner(0, 600_000, 50, { config.timingToleranceMs.toInt() }) { config.timingToleranceMs = it.toLong() }))
            add(row("", check("Lock content-type", { config.lockContentType }) { config.lockContentType = it }))
        })

        add(section("Comparison") {
            add(row("Mode", combo(ComparisonMode.entries.toTypedArray(), { config.comparisonMode }) { config.comparisonMode = it }))
            add(row("", check("use structure", { config.useStructure }) { config.useStructure = it }))
        })

        add(section("Criterion weights") {
            add(row("Status", weightSpinner({ config.weightStatus }) { config.weightStatus = it },
                "Positive", weightSpinner({ config.weightPositive }) { config.weightPositive = it }))
            add(row("Negative", weightSpinner({ config.weightNegative }) { config.weightNegative = it },
                "Structure", weightSpinner({ config.weightStructure }) { config.weightStructure = it }))
            add(row("Length", weightSpinner({ config.weightLength }) { config.weightLength = it }))
        })

        add(section("Manual ignore (force-masked paths / regex, one per line)") {
            add(manualIgnoreArea())
        })

        add(section("Baseline") {
            add(row("Sample count N", intSpinner(1, 20, 1, { config.baselineSamples }) { config.baselineSamples = it },
                check("warm-up", { config.warmUp }) { config.warmUp = it }))
        })

        add(section("Engine") {
            add(row("Threads", intSpinner(1, 32, 1, { config.threadCount }) { config.threadCount = it },
                "Inter-request delay ms", intSpinner(0, 60_000, 50, { config.interRequestDelayMs.toInt() }) { config.interRequestDelayMs = it.toLong() }))
            add(row("Timeout ms", intSpinner(1_000, 600_000, 1_000, { config.requestTimeoutMs.toInt() }) { config.requestTimeoutMs = it.toLong() },
                "Retries", intSpinner(0, 10, 1, { config.retryCount }) { config.retryCount = it }))
            add(row("Max requests (0 = unlimited)", intSpinner(0, 1_000_000, 100, { config.maxRequests }) { config.maxRequests = it },
                "429 backoff ms", intSpinner(0, 60_000, 250, { config.backoff429Ms.toInt() }) { config.backoff429Ms = it.toLong() }))
        })

        val resetBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        resetBar.add(JButton("Reset to defaults").apply {
            addActionListener {
                config.resetTo(global)
                reloaders.forEach { it() }
            }
        })
        add(resetBar)
    }

    // ---- field helpers (each registers a reloader so Reset can refresh it) ----

    private fun statusListText(): String = config.successStatuses.joinToString(", ")

    private fun applyStatusList(text: String) {
        val codes = text.split(",").mapNotNull { it.trim().toIntOrNull() }
        config.successStatuses.clear()
        config.successStatuses.addAll(codes)
        config.useStatusList = codes.isNotEmpty()
    }

    private fun <T> combo(values: Array<T>, get: () -> T, set: (T) -> Unit): JComboBox<T> {
        val box = JComboBox(values)
        box.selectedItem = get()
        box.addActionListener { @Suppress("UNCHECKED_CAST") set(box.selectedItem as T) }
        reloaders.add { box.selectedItem = get() }
        return box
    }

    private fun check(label: String, get: () -> Boolean, set: (Boolean) -> Unit): JCheckBox {
        val box = JCheckBox(label, get())
        box.addActionListener { set(box.isSelected) }
        reloaders.add { box.isSelected = get() }
        return box
    }

    private fun textField(cols: Int, get: () -> String, set: (String) -> Unit): JTextField {
        val field = JTextField(get(), cols)
        field.document.addDocumentListener(simpleDocListener { set(field.text) })
        reloaders.add { field.text = get() }
        return field
    }

    private fun intSpinner(min: Int, max: Int, step: Int, get: () -> Int, set: (Int) -> Unit): JSpinner {
        val spinner = JSpinner(SpinnerNumberModel(get().coerceIn(min, max), min, max, step))
        spinner.addChangeListener { set((spinner.value as Number).toInt()) }
        reloaders.add { spinner.value = get().coerceIn(min, max) }
        return spinner
    }

    private fun doubleSpinner(min: Double, max: Double, step: Double, get: () -> Double, set: (Double) -> Unit): JSpinner {
        val spinner = JSpinner(SpinnerNumberModel(get().coerceIn(min, max), min, max, step))
        spinner.addChangeListener { set((spinner.value as Number).toDouble()) }
        reloaders.add { spinner.value = get().coerceIn(min, max) }
        return spinner
    }

    private fun weightSpinner(get: () -> Double, set: (Double) -> Unit): JSpinner =
        doubleSpinner(0.0, 5.0, 0.5, get, set)

    private fun manualIgnoreArea(): JComponent {
        val area = JTextArea(config.manualIgnore.joinToString("\n"), 4, 30)
        area.document.addDocumentListener(simpleDocListener {
            config.manualIgnore.clear()
            config.manualIgnore.addAll(area.text.split("\n").map { it.trim() }.filter { it.isNotEmpty() })
        })
        reloaders.add { area.text = config.manualIgnore.joinToString("\n") }
        return JScrollPane(area)
    }

    // ---- layout helpers ----

    private fun section(title: String, build: JPanel.() -> Unit): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createTitledBorder(title)
        panel.alignmentX = LEFT_ALIGNMENT
        panel.build()
        return panel
    }

    private fun row(label: String, vararg comps: Any): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        panel.alignmentX = LEFT_ALIGNMENT
        if (label.isNotEmpty()) panel.add(JLabel("$label:").apply { preferredSize = Dimension(150, preferredSize.height) })
        for (c in comps) {
            when (c) {
                is Component -> panel.add(c)
                is String -> panel.add(JLabel(c))
            }
        }
        return panel
    }

    private fun simpleDocListener(onChange: () -> Unit): DocumentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = onChange()
        override fun removeUpdate(e: DocumentEvent) = onChange()
        override fun changedUpdate(e: DocumentEvent) = onChange()
    }
}
