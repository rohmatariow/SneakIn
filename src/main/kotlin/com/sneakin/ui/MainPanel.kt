package com.sneakin.ui

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.HttpRequestResponse
import com.sneakin.model.GlobalConfig
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * The SneakIn suite tab. Hosts independent, closeable scan tabs (Repeater-style).
 * Owns the global config shared as the default for new scan tabs.
 */
class MainPanel(private val api: MontoyaApi) : JPanel(BorderLayout()) {

    val globalConfig = GlobalConfig()

    private val tabs = JTabbedPane()
    private var scanCounter = 0

    init {
        tabs.addTab("Welcome", buildWelcome())
        add(tabs, BorderLayout.CENTER)
        api.userInterface().applyThemeToComponent(this)
    }

    /**
     * Welcome panel built from plain Swing components (no HTML markup): Burp's themed L&F does not
     * render `<html>` in labels here, so each line is its own styled [JLabel].
     */
    private fun buildWelcome(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(28, 28, 28, 28)

        val title = JLabel("SneakIn")
        title.font = title.font.deriveFont(Font.BOLD, title.font.size2D + 12f)

        val subtitle = JLabel("Improper input validation tester")
        subtitle.font = subtitle.font.deriveFont(Font.ITALIC, subtitle.font.size2D + 1f)

        val byline = JLabel("by @rohmatariow")
        byline.font = byline.font.deriveFont(Font.ITALIC)

        val tagline = JLabel("Differential analysis, one parameter at a time.")
        val tagline2 = JLabel(
            "Every payload is illegal input the server should reject — if it sneaks through, " +
                "the parameter is flagged VULNERABLE."
        )

        val separator = JSeparator()
        separator.maximumSize = Dimension(480, separator.preferredSize.height)

        val gettingStarted = JLabel("Getting started")
        gettingStarted.font = gettingStarted.font.deriveFont(Font.BOLD, gettingStarted.font.size2D + 2f)

        val step1 = JLabel("1.   Right-click any request in Proxy history, Repeater, or the site map.")
        val step2 = JLabel("2.   Choose “Send to SneakIn” to open a new scan tab.")
        val step3 = JLabel("3.   Pick your targets, then press Start.")

        val note = JLabel("Each scan tab is independent: its own config, baseline, targets and results.")
        note.font = note.font.deriveFont(Font.ITALIC)

        val rows = listOf(
            title, subtitle, byline,
            gap(18), tagline, tagline2,
            gap(16), separator,
            gap(16), gettingStarted,
            gap(10), step1, step2, step3,
            gap(20), note,
        )
        for (row in rows) {
            (row as JComponent).alignmentX = LEFT_ALIGNMENT
            panel.add(row)
        }
        panel.add(Box.createVerticalGlue())
        return panel
    }

    private fun gap(height: Int): Component = Box.createVerticalStrut(height)

    /** Open a new scan tab seeded with [requestResponse]. Safe to call from any thread. */
    fun addScanTab(requestResponse: HttpRequestResponse) {
        SwingUtilities.invokeLater {
            scanCounter++
            val title = scanCounter.toString() // Repeater-style: 1, 2, 3, … (rename via double-click)
            val host = runCatching { requestResponse.httpService().host() }.getOrDefault("scan")
            val scanTab = ScanTab(api, requestResponse, globalConfig)
            tabs.addTab(title, scanTab)
            val index = tabs.indexOfComponent(scanTab)
            tabs.setTabComponentAt(index, closeableTabHeader(title, host, scanTab))
            tabs.selectedIndex = index
            api.userInterface().applyThemeToComponent(scanTab)
        }
    }

    /**
     * Small tab header with a title and a close button so scan tabs can be dismissed. The title
     * starts as a plain number (Repeater-style) and double-clicking it swaps in an inline editor so
     * the user can rename the tab; [tooltip] (the host) hints what the tab targets.
     */
    private fun closeableTabHeader(title: String, tooltip: String, content: JPanel): JPanel {
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        header.isOpaque = false
        val label = JLabel(title)
        label.toolTipText = "$tooltip — double-click to rename"
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) startRename(header, label, content)
            }
        })
        val close = JButton("×")
        close.preferredSize = Dimension(16, 16)
        close.isBorderPainted = false
        close.isContentAreaFilled = false
        close.isFocusable = false
        close.toolTipText = "Close scan tab"
        close.horizontalAlignment = SwingConstants.CENTER
        close.addActionListener {
            val index = tabs.indexOfComponent(content)
            if (index >= 0) {
                (content as? ScanTab)?.stop()
                tabs.remove(index)
            }
        }
        header.add(label)
        header.add(close)
        return header
    }

    /** Replace the tab's title label with an inline text field; commit on Enter or focus loss. */
    private fun startRename(header: JPanel, label: JLabel, content: JPanel) {
        val field = JTextField(label.text, 8)
        field.selectAll()
        var committed = false
        fun commit() {
            if (committed) return
            committed = true
            val text = field.text.trim()
            if (text.isNotEmpty()) {
                label.text = text
                val idx = tabs.indexOfComponent(content)
                if (idx >= 0) tabs.setTitleAt(idx, text)
            }
            header.remove(field)
            header.add(label, 0)
            header.revalidate()
            header.repaint()
        }
        field.addActionListener { commit() }
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = commit()
        })
        header.remove(label)
        header.add(field, 0)
        header.revalidate()
        header.repaint()
        field.requestFocusInWindow()
    }

    /** Stop all running scans. Called on extension unload. */
    fun shutdown() {
        for (i in 0 until tabs.tabCount) {
            (tabs.getComponentAt(i) as? ScanTab)?.stop()
        }
    }
}
