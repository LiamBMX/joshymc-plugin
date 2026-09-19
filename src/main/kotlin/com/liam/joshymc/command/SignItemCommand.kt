package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType

/**
 * /signitem — autograph the item in your main hand, once per player per 7 days.
 *
 * Only ever touches a single item: if the held stack has more than one item,
 * one is split off, signed, and handed back — the rest of the stack is
 * untouched (mirrors CrateManager.consumeOneKey's split-one-from-stack shape).
 */
class SignItemCommand(private val plugin: Joshymc) : CommandExecutor {

    private val signedKey = NamespacedKey(plugin, "signed")
    private val signerUuidKey = NamespacedKey(plugin, "signer_uuid")
    private val signerNameKey = NamespacedKey(plugin, "signer_name")
    private val signedAtKey = NamespacedKey(plugin, "signed_at")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.sign.item")) {
            plugin.commsManager.send(sender, Component.text("You don't have permission to use /signitem.", NamedTextColor.RED))
            return true
        }

        val itemInHand = sender.inventory.itemInMainHand
        if (itemInHand.type == Material.AIR) {
            plugin.commsManager.send(sender, Component.text("You must be holding an item to sign it.", NamedTextColor.RED))
            return true
        }

        if (itemInHand.itemMeta?.persistentDataContainer?.has(signedKey, PersistentDataType.BYTE) == true) {
            plugin.commsManager.send(sender, Component.text("This item has already been signed.", NamedTextColor.RED))
            return true
        }

        val remaining = getCooldownRemaining(sender)
        if (remaining > 0) {
            plugin.commsManager.send(
                sender,
                Component.text("You can sign another item in ${formatCooldown(remaining)}.", NamedTextColor.RED)
            )
            return true
        }

        val signedItem = itemInHand.clone()
        signedItem.amount = 1
        signedItem.editMeta { meta ->
            val pdc = meta.persistentDataContainer
            pdc.set(signedKey, PersistentDataType.BYTE, 1)
            pdc.set(signerUuidKey, PersistentDataType.STRING, sender.uniqueId.toString())
            pdc.set(signerNameKey, PersistentDataType.STRING, sender.name)
            pdc.set(signedAtKey, PersistentDataType.LONG, System.currentTimeMillis())

            val newLore = (meta.lore() ?: mutableListOf()).toMutableList()
            newLore.add(Component.empty())
            newLore.add(
                Component.text("Signed by ${sender.name}", NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, true)
            )
            meta.lore(newLore)
        }

        if (itemInHand.amount > 1) {
            itemInHand.amount -= 1
            val leftover = sender.inventory.addItem(signedItem)
            for ((_, remainingItem) in leftover) {
                sender.world.dropItemNaturally(sender.location, remainingItem)
            }
        } else {
            sender.inventory.setItemInMainHand(signedItem)
        }

        plugin.databaseManager.execute(
            "INSERT INTO signitem_cooldowns (uuid, last_signed_at) VALUES (?, ?) " +
                "ON CONFLICT(uuid) DO UPDATE SET last_signed_at = excluded.last_signed_at",
            sender.uniqueId.toString(), System.currentTimeMillis()
        )

        plugin.commsManager.send(sender, Component.text("You signed your item!", NamedTextColor.GREEN))
        return true
    }

    private fun getCooldownRemaining(player: Player): Long {
        if (player.hasPermission("joshymc.sign.item.bypass")) return 0L

        val lastSignedAt = plugin.databaseManager.queryFirst(
            "SELECT last_signed_at FROM signitem_cooldowns WHERE uuid = ?",
            player.uniqueId.toString()
        ) { rs -> rs.getLong("last_signed_at") } ?: return 0L

        val elapsed = System.currentTimeMillis() - lastSignedAt
        val remaining = COOLDOWN_MS - elapsed
        return if (remaining > 0) remaining else 0L
    }

    private fun formatCooldown(millis: Long): String {
        val totalMinutes = millis / 60_000
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    companion object {
        private const val COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000

        fun createTable(plugin: Joshymc) {
            plugin.databaseManager.createTable(
                "CREATE TABLE IF NOT EXISTS signitem_cooldowns (uuid TEXT PRIMARY KEY, last_signed_at INTEGER NOT NULL)"
            )
        }
    }
}
