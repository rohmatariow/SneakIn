package com.sneakin.ui

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.ui.editor.EditorOptions
import com.sneakin.core.Exporter
import com.sneakin.core.toRow
import com.sneakin.model.Finding
import com.sneakin.oracle.Verdict
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.RowFilter
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * Results sub-tab: the findings table (colored verdicts, REJECTED hidden by default), a baseline-vs-
 * mutated request/response viewer on row click, right-click Send to Repeater / Comparer, multi-row
 * bulk copy (parameters / lines / CSV), "copy all vulnerable params", and CSV/JSON export.
 */
class ResultsPanel(private val api: MontoyaApi) : JPanel(BorderLayout()) {

    private val model = FindingsTableModel()
    private val table = JTable(model)
    private val sorter = TableRowSorter(model)
    private var showRejected = false

    private val reasons = JLabel(" ")
    private val baselineRequestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY)
    private val baselineResponseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY)
    private val mutatedRequestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY)
    private val mutatedResponseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY)

    private val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))

    init {
        table.rowSorter = sorter
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.columnSelectionAllowed = false
        applyColumnWidths()
        applyVerdictRenderer()
        applyRejectedFilter()

        installSelectionListener()
        table.componentPopupMenu = buildPopup()

        val showRejectedToggle = JCheckBox("Show rejected", showRejected)
        showRejectedToggle.addActionListener {
            showRejected = showRejectedToggle.isSelected
            applyRejectedFilter()
        }
        toolbar.add(showRejectedToggle)
        addBulkActions()

        val top = JPanel(BorderLayout())
        top.add(toolbar, BorderLayout.NORTH)
        top.add(JScrollPane(table), BorderLayout.CENTER)

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, top, buildDetail())
        split.resizeWeight = 0.5
        add(split, BorderLayout.CENTER)
    }

    /** The results toolbar, exposed for embedding additional actions. */
    fun toolbarPanel(): JPanel = toolbar

    fun addFinding(finding: Finding) {
        model.add(finding)
    }

    fun clear() {
        model.clear()
    }

    fun findings(): List<Finding> = model.all()

    fun selectedFindings(): List<Finding> =
        table.selectedRows.map { table.convertRowIndexToModel(it) }.map { model.getFinding(it) }

    private fun buildDetail(): JComponent {
        reasons.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
        val baseline = titled("Baseline", verticalSplit(baselineRequestEditor.uiComponent(), baselineResponseEditor.uiComponent()))
        val mutated = titled("Mutated", verticalSplit(mutatedRequestEditor.uiComponent(), mutatedResponseEditor.uiComponent()))
        val columns = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, baseline, mutated)
        columns.resizeWeight = 0.5
        val panel = JPanel(BorderLayout())
        panel.add(reasons, BorderLayout.NORTH)
        panel.add(columns, BorderLayout.CENTER)
        return panel
    }

    private fun showDetail(finding: Finding) {
        // Plain text (no HTML): Burp's themed L&F does not render `<html>` in labels here.
        reasons.text = buildString {
            append(finding.verdict.name)
            if (finding.classification.subLabel.isNotEmpty()) append(" — ").append(finding.classification.subLabel)
            append("    ")
            append(finding.classification.reasons.joinToString(" "))
            if (finding.signals.isNotEmpty()) append("    [signals: ").append(finding.signals).append("]")
        }
        baselineRequestEditor.request = finding.baselineRequest
        baselineResponseEditor.response = finding.baselineResponse ?: emptyResponse()
        mutatedRequestEditor.request = finding.mutatedRequest
        mutatedResponseEditor.response = finding.mutatedResponse ?: emptyResponse()
    }

    private fun emptyResponse(): HttpResponse = HttpResponse.httpResponse()

    private fun buildPopup(): JPopupMenu {
        val popup = JPopupMenu()

        val toRepeater = JMenuItem("Send to Repeater")
        toRepeater.addActionListener {
            selectedFindings().forEach {
                api.repeater().sendToRepeater(it.mutatedRequest, "SneakIn ${it.parameterName}")
            }
        }
        val toComparer = JMenuItem("Send to Comparer (responses)")
        toComparer.addActionListener {
            selectedFindings().firstOrNull()?.let { f ->
                f.baselineResponse?.let { api.comparer().sendToComparer(it.toByteArray()) }
                f.mutatedResponse?.let { api.comparer().sendToComparer(it.toByteArray()) }
            }
        }

        val copy = JMenu("Copy selection")
        copy.add(menuItem("Copy parameters") { copyToClipboard(parametersText(selectedFindings())) })
        copy.add(menuItem("Copy as lines") { copyToClipboard(linesText(selectedFindings())) })
        copy.add(menuItem("Copy as CSV") { copyToClipboard(Exporter.toCsv(selectedFindings().map { it.toRow() })) })

        popup.add(toRepeater)
        popup.add(toComparer)
        popup.addSeparator()
        popup.add(copy)
        return popup
    }

    /** Header bulk actions: copy all vulnerable params, select all vulnerable, export CSV/JSON. */
    private fun addBulkActions() {
        toolbar.add(JButton("Copy all vulnerable params").apply {
            addActionListener { copyToClipboard(parametersText(vulnerableFindings())) }
        })
        toolbar.add(JButton("Select all vulnerable").apply {
            addActionListener { selectAllVulnerable() }
        })
        toolbar.add(JButton("Export CSV").apply {
            addActionListener { export("csv") }
        })
        toolbar.add(JButton("Export JSON").apply {
            addActionListener { export("json") }
        })
    }

    private fun vulnerableFindings(): List<Finding> = model.all().filter { it.verdict == Verdict.VULNERABLE }

    private fun selectAllVulnerable() {
        table.clearSelection()
        for (viewRow in 0 until table.rowCount) {
            val finding = model.getFinding(table.convertRowIndexToModel(viewRow))
            if (finding.verdict == Verdict.VULNERABLE) table.addRowSelectionInterval(viewRow, viewRow)
        }
    }

    /** Parameter names only, one per line, de-duplicated. */
    private fun parametersText(findings: List<Finding>): String =
        findings.map { it.parameterName }.distinct().joinToString("\n")

    /** e.g. `amount · JSON body · payload: -99999 · Negative Accepted`. */
    private fun linesText(findings: List<Finding>): String = findings.joinToString("\n") { f ->
        val verdict = if (f.classification.subLabel.isNotEmpty()) f.classification.subLabel else f.verdict.name
        "${f.parameterName} · ${f.locationLabel} · payload: ${f.payload.value} · $verdict"
    }

    private fun export(kind: String) {
        val rows = model.all().map { it.toRow() }
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No results to export.")
            return
        }
        val chooser = JFileChooser()
        chooser.selectedFile = File("sneakin-results.$kind")
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val text = if (kind == "csv") Exporter.toCsv(rows) else Exporter.toJson(rows)
        runCatching { chooser.selectedFile.writeText(text) }
            .onFailure { JOptionPane.showMessageDialog(this, "Export failed: ${it.message}") }
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    private fun menuItem(label: String, action: () -> Unit): JMenuItem =
        JMenuItem(label).apply { addActionListener { action() } }

    private fun applyRejectedFilter() {
        sorter.rowFilter = object : RowFilter<FindingsTableModel, Int>() {
            override fun include(entry: Entry<out FindingsTableModel, out Int>): Boolean {
                if (showRejected) return true
                return model.getFinding(entry.identifier).verdict != Verdict.REJECTED
            }
        }
    }

    private fun applyVerdictRenderer() {
        val renderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, col: Int
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, selected, focus, row, col)
                val finding = model.getFinding(t.convertRowIndexToModel(row))
                if (!selected) c.foreground = finding.verdict.displayColor
                font = font.deriveFont(if (finding.verdict == Verdict.VULNERABLE) Font.BOLD else Font.PLAIN)
                return c
            }
        }
        table.columnModel.getColumn(COL_VERDICT).cellRenderer = renderer

        val scoreRenderer = object : DefaultTableCellRenderer() {
            init { horizontalAlignment = SwingConstants.RIGHT }
            override fun setValue(value: Any?) {
                text = (value as? Double)?.let { String.format("%.2f", it) } ?: ""
            }
        }
        table.columnModel.getColumn(COL_SCORE).cellRenderer = scoreRenderer
    }

    private fun applyColumnWidths() {
        val widths = intArrayOf(150, 110, 110, 160, 60, 80, 70, 130, 60, 140)
        for (i in widths.indices) table.columnModel.getColumn(i).preferredWidth = widths[i]
    }

    private fun installSelectionListener() {
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val row = table.selectedRow
                if (row >= 0) showDetail(model.getFinding(table.convertRowIndexToModel(row)))
            }
        }
    }

    private fun verticalSplit(top: Component, bottom: Component): JSplitPane {
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom)
        split.resizeWeight = 0.5
        return split
    }

    private fun titled(title: String, comp: Component): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder(title)
        panel.add(comp, BorderLayout.CENTER)
        return panel
    }

    companion object {
        const val COL_VERDICT = 7
        const val COL_SCORE = 8
    }
}

private class FindingsTableModel : AbstractTableModel() {
    private val findings = ArrayList<Finding>()
    private val columns = arrayOf(
        "Parameter", "Location", "Payload Category", "Payload", "Status",
        "Δ Length", "Δ Time", "Verdict", "Score", "Signals"
    )

    fun add(finding: Finding) {
        findings.add(finding)
        fireTableRowsInserted(findings.size - 1, findings.size - 1)
    }

    fun clear() {
        val n = findings.size
        findings.clear()
        if (n > 0) fireTableRowsDeleted(0, n - 1)
    }

    fun all(): List<Finding> = findings.toList()
    fun getFinding(row: Int): Finding = findings[row]

    override fun getRowCount(): Int = findings.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        4, 5 -> Int::class.javaObjectType
        6 -> Long::class.javaObjectType
        8 -> Double::class.javaObjectType
        else -> String::class.java
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val f = findings[rowIndex]
        return when (columnIndex) {
            0 -> f.parameterName
            1 -> f.locationLabel
            2 -> f.categoryLabel
            3 -> f.payloadPreview()
            4 -> f.statusCode
            5 -> f.deltaLength
            6 -> f.deltaTimeMs
            7 -> if (f.classification.subLabel.isNotEmpty()) "${f.verdict.name} · ${f.classification.subLabel}" else f.verdict.name
            8 -> f.score
            9 -> f.signals
            else -> ""
        }
    }
}
