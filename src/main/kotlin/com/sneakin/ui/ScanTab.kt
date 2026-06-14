package com.sneakin.ui

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.HttpRequestResponse
import com.sneakin.core.InjectionPointEnumerator
import com.sneakin.core.PayloadLibrary
import com.sneakin.core.ScanCallbacks
import com.sneakin.core.ScanEngine
import com.sneakin.model.Finding
import com.sneakin.model.GlobalConfig
import com.sneakin.model.InjectionPoint
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

/**
 * One scan: independent config, baseline, targets and results.
 * Header strip = Start/Stop + progress + live counters; body = Config | Targets | Results | Log.
 */
class ScanTab(
    private val api: MontoyaApi,
    val seed: HttpRequestResponse,
    global: GlobalConfig,
) : JPanel(BorderLayout()) {

    val config = global.newScanConfig()
    val payloadLibrary = PayloadLibrary()

    val logPanel = LogPanel()
    private val configPanel = ConfigPanel(config, global, payloadLibrary)
    private val targetsPanel = TargetsPanel()
    private val resultsPanel = ResultsPanel(api)
    private val combinePanel = CombinePanel(api, { resultsPanel.findings() }, { seed.request() })

    var injectionPoints: List<InjectionPoint> = emptyList()
        private set

    private val startButton = JButton("Start")
    private val stopButton = JButton("Stop")
    private val progressBar = JProgressBar()
    private val counterLabel = JLabel("0 requests · 0 vulnerable · 0 review · 0 rejected")

    @Volatile private var engine: ScanEngine? = null

    init {
        add(buildHeader(), BorderLayout.NORTH)

        val body = JTabbedPane()
        body.addTab("Config", configPanel)
        body.addTab("Targets", targetsPanel)
        body.addTab("Results", resultsPanel)
        body.addTab("Combine", combinePanel)
        body.addTab("Log", logPanel)
        // Pull the latest vulnerable findings whenever the user opens the Combine tab.
        body.addChangeListener { if (body.selectedComponent === combinePanel) combinePanel.refresh() }
        add(body, BorderLayout.CENTER)

        stopButton.isEnabled = false
        startButton.addActionListener { start() }
        stopButton.addActionListener { stop() }

        logPanel.log("Seeded from ${runCatching { seed.request().url() }.getOrDefault("request")}")
        enumerateTargets()
    }

    private fun enumerateTargets() {
        val enumerator = InjectionPointEnumerator { logPanel.log(it) }
        injectionPoints = runCatching { enumerator.enumerate(seed.request()) }
            .onFailure { logPanel.log("Enumeration failed: ${it.message}") }
            .getOrDefault(emptyList())
        targetsPanel.setPoints(injectionPoints)
        logPanel.log("Enumerated ${injectionPoints.size} injection point(s).")
    }

    private fun buildHeader(): JPanel {
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        header.add(startButton)
        header.add(stopButton)
        progressBar.isStringPainted = true
        progressBar.preferredSize = Dimension(240, progressBar.preferredSize.height)
        header.add(progressBar)
        header.add(counterLabel)
        return header
    }

    fun start() {
        if (engine != null) return
        val selected = targetsPanel.selectedPoints()
        if (selected.isEmpty()) {
            logPanel.log("No injection points selected — nothing to scan.")
            return
        }
        resultsPanel.clear()
        progressBar.value = 0
        startButton.isEnabled = false
        stopButton.isEnabled = true

        // Unique-flagged fields rotate on every request, whether or not they are themselves tested.
        val unique = targetsPanel.allPoints().filter { it.unique }
        val scan = ScanEngine(api, seed, config, payloadLibrary, selected, unique, callbacks())
        engine = scan
        scan.start()
    }

    fun stop() {
        engine?.stop()
        stopButton.isEnabled = false
    }

    private fun callbacks(): ScanCallbacks = object : ScanCallbacks {
        override fun onStarted(totalPayloads: Int) = SwingUtilities.invokeLater {
            progressBar.maximum = totalPayloads.coerceAtLeast(1)
            progressBar.value = 0
        }

        override fun onLog(message: String) = logPanel.log(message)

        override fun onProgress(sent: Int, total: Int) = SwingUtilities.invokeLater {
            progressBar.maximum = total.coerceAtLeast(1)
            progressBar.value = sent.coerceAtMost(progressBar.maximum)
            progressBar.string = "$sent / $total"
        }

        override fun onCounters(vulnerable: Int, review: Int, rejected: Int, requests: Int) =
            SwingUtilities.invokeLater {
                counterLabel.text =
                    "$requests requests · $vulnerable vulnerable · $review review · $rejected rejected"
            }

        override fun onFinding(finding: Finding) = SwingUtilities.invokeLater {
            resultsPanel.addFinding(finding)
        }

        override fun onFinished(stopped: Boolean) = SwingUtilities.invokeLater {
            startButton.isEnabled = true
            stopButton.isEnabled = false
            engine = null
            combinePanel.refresh()
        }
    }
}
