package com.sneakin.ui

import com.sneakin.core.PayloadLibrary
import com.sneakin.model.PayloadEntry
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/**
 * Option A payload editor: three editable groups (Symbol, Bad Format, Negative). Edits mutate the
 * scan tab's [PayloadLibrary] directly and apply to subsequent scans. Disabled entries are skipped.
 */
class PayloadEditorPanel(private val library: PayloadLibrary) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(buildSymbolGroup())
        add(buildBadFormatGroup())
        add(buildNegativeGroup())
    }

    private fun buildSymbolGroup(): JPanel {
        val entries = PayloadEntryPanel(library.symbols)
        return titled("Symbol — literal strings, applied to every field") {
            add(entries, BorderLayout.CENTER)
            add(resetBar("Reset symbols to default") {
                library.resetSymbols(); entries.refresh()
            }, BorderLayout.SOUTH)
        }
    }

    private fun buildNegativeGroup(): JPanel {
        val entries = PayloadEntryPanel(library.negatives)
        return titled("Negative — numbers, applied only to numeric fields") {
            add(entries, BorderLayout.CENTER)
            add(resetBar("Reset negatives to default") {
                library.resetNegatives(); entries.refresh()
            }, BorderLayout.SOUTH)
        }
    }

    private fun buildBadFormatGroup(): JPanel {
        val suffixes = PayloadEntryPanel(library.appendSuffixes)

        val toggles = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
        val append = JCheckBox("append (valid value + junk)", library.modeAppend)
        val malform = JCheckBox("malform (broken variant, e.g. 2026-13-45)", library.modeMalform)
        val truncate = JCheckBox("truncate (cut a valid value, e.g. 2026-01)", library.modeTruncate)
        append.addActionListener { library.modeAppend = append.isSelected }
        malform.addActionListener { library.modeMalform = malform.isSelected }
        truncate.addActionListener { library.modeTruncate = truncate.isSelected }
        toggles.add(append); toggles.add(malform); toggles.add(truncate)

        val detectors = JLabel("Format detectors (read-only): date · email · UUID · numeric")

        val north = JPanel()
        north.layout = BoxLayout(north, BoxLayout.Y_AXIS)
        north.add(JLabel("Append suffixes:"))
        north.add(suffixes)
        north.add(toggles)
        north.add(detectors)

        return titled("Bad Format — generative rules, applied when a format is detected") {
            add(north, BorderLayout.CENTER)
            add(resetBar("Reset bad-format rules to default") {
                library.resetBadFormat()
                suffixes.refresh()
                append.isSelected = library.modeAppend
                malform.isSelected = library.modeMalform
                truncate.isSelected = library.modeTruncate
            }, BorderLayout.SOUTH)
        }
    }

    private fun resetBar(label: String, action: () -> Unit): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        val button = JButton(label)
        button.addActionListener { action() }
        bar.add(button)
        return bar
    }

    private fun titled(title: String, build: JPanel.() -> Unit): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder(title)
        panel.build()
        return panel
    }
}

/** An add/edit/delete/enable table over a mutable list of [PayloadEntry]. */
private class PayloadEntryPanel(private val entries: MutableList<PayloadEntry>) : JPanel(BorderLayout()) {

    private val model = PayloadEntryTableModel(entries)
    private val table = JTable(model)

    init {
        table.columnModel.getColumn(0).maxWidth = 40
        val scroll = JScrollPane(table)
        scroll.preferredSize = Dimension(420, 90)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        val add = JButton("Add")
        val delete = JButton("Delete")
        add.addActionListener {
            entries.add(PayloadEntry("", true))
            model.fireTableRowsInserted(entries.size - 1, entries.size - 1)
            val row = entries.size - 1
            table.editCellAt(row, 1)
            table.changeSelection(row, 1, false, false)
        }
        delete.addActionListener {
            val rows = table.selectedRows.sortedDescending()
            if (table.isEditing) table.cellEditor?.stopCellEditing()
            for (r in rows) if (r in entries.indices) entries.removeAt(r)
            model.fireTableDataChanged()
        }
        buttons.add(add)
        buttons.add(delete)

        add(scroll, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }

    fun refresh() = model.fireTableDataChanged()
}

private class PayloadEntryTableModel(private val entries: List<PayloadEntry>) : AbstractTableModel() {
    private val columns = arrayOf("On", "Value")
    override fun getRowCount() = entries.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]
    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex == 0) Boolean::class.javaObjectType else String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = true

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
        if (columnIndex == 0) entries[rowIndex].enabled else entries[rowIndex].value

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        val entry = entries[rowIndex]
        if (columnIndex == 0) entry.enabled = value as? Boolean ?: true
        else entry.value = value?.toString() ?: ""
        fireTableRowsUpdated(rowIndex, rowIndex)
    }
}
