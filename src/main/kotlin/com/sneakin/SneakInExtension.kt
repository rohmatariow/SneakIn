package com.sneakin

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import com.sneakin.ui.ContextMenu
import com.sneakin.ui.MainPanel
import javax.swing.SwingUtilities

/**
 * Entry point for the SneakIn Burp extension.
 *
 * SneakIn detects improper input validation through differential analysis: it seeds a request,
 * sends a baseline, then mutates one parameter at a time with deliberately-illegal payloads and
 * flags any parameter where the server accepts input it should have rejected.
 */
class SneakInExtension : BurpExtension {

    override fun initialize(api: MontoyaApi) {
        api.extension().setName("SneakIn")

        val mainPanel = MainPanel(api)

        // Suite tab — the SneakIn workspace hosting Repeater-style scan tabs.
        api.userInterface().registerSuiteTab("SneakIn", mainPanel)

        // The only entry path: right-click a request -> "Send to SneakIn" -> new scan tab.
        api.userInterface().registerContextMenuItemsProvider(ContextMenu(mainPanel))

        api.logging().logToOutput(
            "SneakIn loaded (by @rohmatariow). Right-click a request and choose 'Send to SneakIn'."
        )

        // Tidy shutdown of the shared executor when the extension is unloaded.
        api.extension().registerUnloadingHandler {
            SwingUtilities.invokeLater { mainPanel.shutdown() }
        }
    }
}
