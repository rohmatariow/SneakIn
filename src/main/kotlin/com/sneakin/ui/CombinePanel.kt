package com.sneakin.ui

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.ui.editor.EditorOptions
import com.sneakin.core.RequestMutator
import com.sneakin.model.Finding
import com.sneakin.model.PayloadCategory
import com.sneakin.oracle.Verdict
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

/**
 * Combine sub-tab: takes the VULNERABLE findings (parameters whose illegal payload "sneaked in"),
 * lets the user view/copy them per payload category, and builds a single request that fills every
 * accepted parameter at once — for one category or a hand-picked subset — to send to Repeater or
 * fire directly.
 *
 * - **Category filter** (#1): show + copy the accepted parameters of one category.
 * - **Combined request** (#2): every checked finding's successful payload chained onto the seed.
 * - **Send as one** (#3): uncheck rows to subset, then Send to Repeater / Send now.
 */
class CombinePanel(
    private val api: MontoyaApi,
    private val findingsProvider: () -> List<Finding>,
    private val seedProvider: () -> HttpRequest,
) : JPanel(BorderLayout()) {

    private val mutator = RequestMutator()
    private val model = IncludeTableModel()
    private val table = JTable(model)

    private val scope = JComboBox(arrayOf(ALL, "Symbol", "Bad Format", "Negative"))
    private val status = JLabel(" ")

    private val requestEditor = api.userInterface().createHttpRequestEditor()
    private val responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY)

    /** Vulnerable findings last pulled from results, before category filtering. */
    private var vulnerable: List<Finding> = emptyList()

    init {
        table.fillsViewportHeight = true
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).maxWidth = 60
        table.columnModel.getColumn(1).preferredWidth = 150
        table.columnModel.getColumn(2).preferredWidth = 110
        table.columnModel.getColumn(3).preferredWidth = 100
        // Any table change (checkbox toggle, repopulate) rebuilds the combined request live.
        model.addTableModelListener { rebuild() }

        scope.addActionListener { repopulate() }

        add(buildToolbar(), BorderLayout.NORTH)

        val left = JPanel(BorderLayout())
        left.add(JScrollPane(table), BorderLayout.CENTER)
        left.add(status, BorderLayout.SOUTH)
        val right = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            titled("Combined request", requestEditor.uiComponent()),
            titled("Response", responseEditor.uiComponent()),
        )
        right.resizeWeight = 0.55
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right)
        split.resizeWeight = 0.4
        add(split, BorderLayout.CENTER)

        refresh()
    }

    private fun buildToolbar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        bar.add(JLabel("Category:"))
        bar.add(scope)
        bar.add(JButton("Refresh").apply { addActionListener { refresh() } })
        bar.add(JButton("Copy parameters").apply { addActionListener { copyParams() } })
        bar.add(JButton("Send to Repeater").apply { addActionListener { sendToRepeater() } })
        bar.add(JButton("Send now").apply { addActionListener { sendNow() } })
        return bar
    }

    /** Re-pull vulnerable findings from results and repopulate the include table. */
    fun refresh() {
        vulnerable = findingsProvider().filter { it.verdict == Verdict.VULNERABLE }
        repopulate()
    }

    private fun repopulate() {
        val cat = selectedCategory()
        val rows = vulnerable
            .filter { cat == null || it.payload.category == cat }
            .map { IncludeRow(it, true) }
        model.setRows(rows) // fires table change -> rebuild()
    }

    private fun selectedCategory(): PayloadCategory? = when (scope.selectedItem) {
        "Symbol" -> PayloadCategory.SYMBOL
        "Bad Format" -> PayloadCategory.BAD_FORMAT
        "Negative" -> PayloadCategory.NEGATIVE
        else -> null
    }

    /** Build the combined request from the checked rows and show it. */
    private fun rebuild() {
        val checked = model.checkedFindings()
        val combined = mutator.mutateAll(seedProvider(), checked.map { it.point to it.payload.value })
        requestEditor.request = combined
        status.text = "  ${checked.size} of ${model.rowCount} accepted parameter(s) combined into 1 request"
    }

    private fun copyParams() {
        val params = model.checkedFindings().map { it.parameterName }.distinct().joinToString("\n")
        if (params.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No parameters selected.")
            return
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(params), null)
    }

    private fun sendToRepeater() {
        val checked = model.checkedFindings()
        if (checked.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No parameters selected.")
            return
        }
        api.repeater().sendToRepeater(requestEditor.request, scopeLabel(checked.size))
    }

    private fun sendNow() {
        if (model.checkedFindings().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No parameters selected.")
            return
        }
        val req = requestEditor.request
        responseEditor.response = HttpResponse.httpResponse()
        status.text = "  sending…"
        Thread({
            val result = runCatching { api.http().sendRequest(req) }
            SwingUtilities.invokeLater {
                result
                    .onSuccess { rr ->
                        responseEditor.response = rr.response() ?: HttpResponse.httpResponse()
                        status.text = "  sent — ${model.checkedFindings().size} param(s) combined into 1 request"
                    }
                    .onFailure { status.text = "  send failed: ${it.message}" }
            }
        }, "sneakin-combine-send").apply { isDaemon = true }.start()
    }

    private fun scopeLabel(n: Int): String {
        val cat = scope.selectedItem as? String ?: ALL
        return if (cat == ALL) "SneakIn combined ($n)" else "SneakIn $cat ($n)"
    }

    private fun titled(title: String, comp: Component): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder(title)
        panel.add(comp, BorderLayout.CENTER)
        return panel
    }

    companion object {
        private const val ALL = "All categories"
    }
}

private class IncludeRow(val finding: Finding, var include: Boolean)

private class IncludeTableModel : AbstractTableModel() {
    private var rows: List<IncludeRow> = emptyList()
    private val columns = arrayOf("Include", "Parameter", "Location", "Category", "Payload")

    fun setRows(rows: List<IncludeRow>) {
        this.rows = rows
        fireTableDataChanged()
    }

    fun checkedFindings(): List<Finding> = rows.filter { it.include }.map { it.finding }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(c: Int): String = columns[c]
    override fun getColumnClass(c: Int): Class<*> =
        if (c == 0) Boolean::class.javaObjectType else String::class.java

    override fun isCellEditable(r: Int, c: Int): Boolean = c == 0

    override fun getValueAt(r: Int, c: Int): Any {
        val f = rows[r].finding
        return when (c) {
            0 -> rows[r].include
            1 -> f.parameterName
            2 -> f.locationLabel
            3 -> f.categoryLabel
            4 -> f.payloadPreview()
            else -> ""
        }
    }

    override fun setValueAt(value: Any?, r: Int, c: Int) {
        if (c == 0) {
            rows[r].include = value as? Boolean ?: false
            fireTableRowsUpdated(r, r)
        }
    }
}
