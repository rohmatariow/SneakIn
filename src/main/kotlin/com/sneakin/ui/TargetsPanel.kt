package com.sneakin.ui

import com.sneakin.model.InjectionPoint
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/** Targets sub-tab: the enumerated injection points with per-row Test + Unique checkboxes. */
class TargetsPanel : JPanel(BorderLayout()) {

    private val model = TargetsTableModel()
    private val table = JTable(model)
    private val sorter = TableRowSorter(model)
    private val search = JTextField(18)
    private val summary = JLabel()

    /** Anchor row for Shift+click range selection, kept in VIEW coordinates so it survives sort/filter. */
    private var anchorViewRow = -1

    init {
        table.fillsViewportHeight = true
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.rowSorter = sorter
        // Checkbox columns are driven by clicks; sorting them would just shuffle rows confusingly.
        sorter.setSortable(COL_TEST, false)
        sorter.setSortable(COL_UNIQUE, false)
        table.columnModel.getColumn(COL_TEST).maxWidth = 45
        table.columnModel.getColumn(COL_UNIQUE).maxWidth = 60
        table.columnModel.getColumn(COL_LOCATION).preferredWidth = 120
        table.columnModel.getColumn(COL_NAME).preferredWidth = 190
        table.columnModel.getColumn(COL_VALUE).preferredWidth = 240
        table.columnModel.getColumn(COL_TYPE).preferredWidth = 70

        // Test-column toggling goes through this listener so Shift+click can flip a whole range.
        // The Unique column is a normal editable checkbox handled by the default cell editor.
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = onTestColumnClick(e)
        })

        // Live filter by parameter name — endpoints with many params, only a few must be unique.
        search.toolTipText = "Filter targets by parameter name / path"
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })

        val top = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        top.add(JLabel("Search:"))
        top.add(search)
        top.add(JButton("Clear").apply { addActionListener { search.text = "" } })
        val selectAll = JButton("Select all")
        val selectNone = JButton("Deselect all")
        // Scoped to the rows currently visible, so "filter then select all" does what it looks like.
        selectAll.addActionListener { setVisibleTest(true) }
        selectNone.addActionListener { setVisibleTest(false) }
        top.add(selectAll)
        top.add(selectNone)
        top.add(JLabel("Shift+click Test to range-select · Unique = rotate value each request"))
        top.add(summary)

        model.addTableModelListener { refreshSummary() }

        add(top, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        refreshSummary()
    }

    private fun applyFilter() {
        val q = search.text.trim()
        sorter.rowFilter =
            if (q.isEmpty()) null else RowFilter.regexFilter("(?i)" + Regex.escape(q), COL_NAME)
        anchorViewRow = -1 // anchor may now be hidden; restart the range from the next plain click
        refreshSummary()
    }

    /** Toggle the clicked Test checkbox; with Shift held, set every VISIBLE row between anchor and it. */
    private fun onTestColumnClick(e: MouseEvent) {
        val viewRow = table.rowAtPoint(e.point)
        val viewCol = table.columnAtPoint(e.point)
        if (viewRow < 0 || viewCol < 0) return
        if (table.convertColumnIndexToModel(viewCol) != COL_TEST) return // Unique/others: default editor
        val modelRow = table.convertRowIndexToModel(viewRow)
        if (modelRow !in model.points.indices) return

        val target = !model.points[modelRow].selected
        if (e.isShiftDown && anchorViewRow in 0 until table.rowCount) {
            val lo = minOf(anchorViewRow, viewRow)
            val hi = maxOf(anchorViewRow, viewRow)
            for (v in lo..hi) {
                val m = table.convertRowIndexToModel(v)
                if (m in model.points.indices) model.points[m].selected = target
            }
            model.refreshRows()
        } else {
            model.setRowSelected(modelRow, target)
            anchorViewRow = viewRow
        }
        e.consume()
    }

    /** Set the Test flag on every currently-visible row (respects the active search filter). */
    private fun setVisibleTest(value: Boolean) {
        for (v in 0 until table.rowCount) {
            val m = table.convertRowIndexToModel(v)
            if (m in model.points.indices) model.points[m].selected = value
        }
        model.refreshRows()
        refreshSummary()
    }

    fun setPoints(points: List<InjectionPoint>) {
        model.setPoints(points)
        anchorViewRow = -1
        refreshSummary()
    }

    fun selectedPoints(): List<InjectionPoint> = model.points.filter { it.selected }

    fun allPoints(): List<InjectionPoint> = model.points

    private fun refreshSummary() {
        val total = model.points.size
        val selected = model.points.count { it.selected }
        val unique = model.points.count { it.unique }
        val uniqueNote = if (unique > 0) " · $unique unique" else ""
        val shownNote = if (sorter.rowFilter != null) " · ${table.rowCount} shown" else ""
        summary.text = "  $selected of $total injection points selected$uniqueNote$shownNote"
    }

    companion object {
        const val COL_TEST = 0
        const val COL_UNIQUE = 1
        const val COL_LOCATION = 2
        const val COL_NAME = 3
        const val COL_VALUE = 4
        const val COL_TYPE = 5
    }
}

private class TargetsTableModel : AbstractTableModel() {
    var points: List<InjectionPoint> = emptyList()
        private set

    private val columns = arrayOf("Test", "Unique", "Location", "Name / Path", "Original value", "Type")

    fun setPoints(points: List<InjectionPoint>) {
        this.points = points
        fireTableDataChanged()
    }

    fun setRowSelected(row: Int, value: Boolean) {
        if (row !in points.indices) return
        points[row].selected = value
        fireTableRowsUpdated(row, row)
    }

    /** Re-fire all rows after a batch of direct flag edits (range select, select-visible). */
    fun refreshRows() {
        if (points.isNotEmpty()) fireTableRowsUpdated(0, points.size - 1)
    }

    override fun getRowCount(): Int = points.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        TargetsPanel.COL_TEST, TargetsPanel.COL_UNIQUE -> Boolean::class.javaObjectType
        else -> String::class.java
    }

    // Test (col 0) is driven by the mouse listener (Shift+click range), so it is NOT cell-editable.
    // Unique (col 1) is a normal editable checkbox.
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == TargetsPanel.COL_UNIQUE

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val p = points[rowIndex]
        return when (columnIndex) {
            TargetsPanel.COL_TEST -> p.selected
            TargetsPanel.COL_UNIQUE -> p.unique
            TargetsPanel.COL_LOCATION -> p.locationType.label
            TargetsPanel.COL_NAME -> p.name
            TargetsPanel.COL_VALUE -> truncate(p.originalValue)
            TargetsPanel.COL_TYPE -> p.valueType.name.lowercase()
            else -> ""
        }
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex == TargetsPanel.COL_UNIQUE) {
            points[rowIndex].unique = value as? Boolean ?: false
            fireTableRowsUpdated(rowIndex, rowIndex)
        }
    }

    private fun truncate(s: String): String {
        val flat = s.replace("\n", " ").replace("\r", " ")
        return if (flat.length > 90) flat.take(90) + "…" else flat
    }
}
