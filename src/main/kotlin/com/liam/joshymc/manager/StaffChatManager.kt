package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.Collections
import java.util.UUID

/**
 * Per-player Staff Chat toggle (redirects the player's own chat into Staff Chat).
 * That mode is in-memory only — resets to OFF on rejoin/restart by design, no
 * table needed. The separate "view" toggle (whether incoming Staff Chat messages
 * are shown at all) is persisted via SettingsManager instead — see [isViewEnabled].
 */
class StaffChatManager(private val plugin: Joshymc) {

    companion object {
        const val PERM = "joshymc.staffchat"
        const val PERM_VIEW = "joshymc.staffchat.view"
        const val VIEW_SETTING_KEY = "staffchat_view"
    }

    // AsyncChatEvent runs off the main thread, so this set needs to be thread-safe.
    private val enabled: MutableSet<UUID> = Collections.synchronizedSet(mutableSetOf())

    /** Also strips the toggle if permission was revoked, so a stale toggle can't linger. */
    fun isEnabled(player: Player): Boolean {
        if (!player.hasPermission(PERM)) {
            enabled.remove(player.uniqueId)
            return false
        }
        return enabled.contains(player.uniqueId)
    }

    fun toggle(player: Player) {
        if (enabled.remove(player.uniqueId)) {
            plugin.commsManager.send(player, Component.text("Staff Chat disabled.", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
        } else {
            enabled.add(player.uniqueId)
            plugin.commsManager.send(player, Component.text("Staff Chat enabled.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        }
    }

    fun handleQuit(uuid: UUID) {
        enabled.remove(uuid)
    }

    /** Whether this staff member currently receives Staff Chat messages. Persisted via SettingsManager, defaults to ON. */
    fun isViewEnabled(player: Player): Boolean = plugin.settingsManager.getSetting(player, VIEW_SETTING_KEY)

    fun setViewEnabled(player: Player, value: Boolean) {
        plugin.settingsManager.setSetting(player, VIEW_SETTING_KEY, value)
    }

    fun sendMessage(sender: Player, plainMessage: String) {
        val format = plugin.config.getString("chat.staffchat-format")
            ?: "&8[&cStaff Chat&8] &f{player}&7: &f{message}"
        val filled = format.replace("{player}", sender.name).replace("{message}", plainMessage)
        val component = plugin.commsManager.parseLegacy(filled)
        for (viewer in Bukkit.getOnlinePlayers()) {
            if (viewer.hasPermission(PERM) && isViewEnabled(viewer)) viewer.sendMessage(component)
        }
    }
}
