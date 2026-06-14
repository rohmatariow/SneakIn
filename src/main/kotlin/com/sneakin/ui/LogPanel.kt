package com.sneakin.ui

import java.awt.BorderLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JToolBar
import javax.swing.SwingUtilities

/** Plain-text log of engine activity (requests sent, errors, skips). */
class LogPanel : JPanel(BorderLayout()) {

    private val area = JTextArea()
    private val timeFmt = SimpleDateFormat("HH:mm:ss")

    init {
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = true

        val toolbar = JToolBar()
        toolbar.isFloatable = false
        val clear = JButton("Clear")
        clear.addActionListener { area.text = "" }
        toolbar.add(clear)

        add(toolbar, BorderLayout.NORTH)
        add(JScrollPane(area), BorderLayout.CENTER)
    }

    /** Append a timestamped line. Safe to call from any thread. */
    fun log(message: String) {
        val line = "[${timeFmt.format(Date())}] $message\n"
        SwingUtilities.invokeLater {
            area.append(line)
            area.caretPosition = area.document.length
        }
    }

    fun clear() {
        SwingUtilities.invokeLater { area.text = "" }
    }
}
