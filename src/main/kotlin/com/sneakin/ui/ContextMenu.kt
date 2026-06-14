package com.sneakin.ui

import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import java.awt.Component
import javax.swing.JMenuItem

/** Adds "Send to SneakIn" to request context menus (Proxy history, Repeater, Target/Site map). */
class ContextMenu(private val mainPanel: MainPanel) : ContextMenuItemsProvider {

    override fun provideMenuItems(event: ContextMenuEvent): List<Component> {
        val seed = selectedRequestResponse(event) ?: return emptyList()
        val item = JMenuItem("Send to SneakIn")
        item.addActionListener { mainPanel.addScanTab(seed) }
        return listOf(item)
    }

    /** Prefer the message-editor request/response, fall back to the first selected row. */
    private fun selectedRequestResponse(event: ContextMenuEvent) =
        event.messageEditorRequestResponse()
            .map { it.requestResponse() }
            .orElseGet { event.selectedRequestResponses().firstOrNull() }
}
