package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * `/jmc-ecview <uuid>` — hidden bridge command run by the clickable
 * `[enderchest]`/`[ec]` chat token (see [com.liam.joshymc.listener.EnderchestPreviewListener]).
 * Not meant to be typed by hand: it resolves the owner's ender chest at
 * click-time and opens a read-only, cloned-item preview GUI for the clicker.
 * Never opens the live inventory, so nothing typed here can ever modify it.
 */
class EnderchestPreviewCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) return true
        if (!sender.hasPermission("joshymc.enderchestpreview")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val uuid = args.getOrNull(0)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (uuid == null) return true

        val onlineOwner = Bukkit.getPlayer(uuid)
        val ownerName = onlineOwner?.name ?: Bukkit.getOfflinePlayer(uuid).name

        if (ownerName == null) {
            plugin.commsManager.send(sender, Component.text("That player's Ender Chest could not be found.", NamedTextColor.RED))
            return true
        }

        val items: Array<ItemStack?> = if (onlineOwner != null) {
            onlineOwner.enderChest.contents.map { it?.clone() }.toTypedArray()
        } else {
            plugin.adminManager.getCachedEnderchestItems(uuid) ?: run {
                plugin.commsManager.send(sender, Component.text("$ownerName's Ender Chest isn't available for preview right now.", NamedTextColor.RED))
                return true
            }
        }

        val gui = CustomGui(
            Component.text("$ownerName's Ender Chest", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false),
            54
        )
        for (i in items.indices) {
            if (i < 27) items[i]?.let { gui.inventory.setItem(i, it) }
        }
        for ((slot, item) in plugin.enderChestManager.snapshotExtra(uuid)) {
            gui.inventory.setItem(27 + slot, item.clone())
        }
        plugin.guiManager.open(sender, gui)
        return true
    }
}
