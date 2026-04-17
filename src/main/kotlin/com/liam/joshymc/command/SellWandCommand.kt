package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class SellWandCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        private const val PDC_MULTIPLIER = "sellwand_multiplier"
        private const val PDC_USES = "sellwand_uses"
        private const val PDC_MAX_USES = "sellwand_max_uses"

        fun createSellWand(plugin: Joshymc, multiplier: Double, uses: Int): ItemStack {
            val item = ItemStack(Material.BLAZE_ROD)
            item.editMeta { meta ->
                meta.displayName(
                    Component.text("Sell Wand", TextColor.color(0xFFAA00))
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )

                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(
                    Component.text("  Multiplier: ", NamedTextColor.GRAY)
                        .append(Component.text("${multiplier}x", NamedTextColor.GOLD))
                        .decoration(TextDecoration.ITALIC, false)
                )
                lore.add(
                    Component.text("  Uses: ", NamedTextColor.GRAY)
                        .append(Component.text("$uses", NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false)
                )
                lore.add(Component.empty())
                lore.add(
                    Component.text("  Right-click a chest to sell its contents.", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(lore)

                meta.setEnchantmentGlintOverride(true)

                // Store data in PDC
                val pdc = meta.persistentDataContainer
                pdc.set(NamespacedKey(plugin, "custom_item_id"), PersistentDataType.STRING, "sell_wand")
                pdc.set(NamespacedKey(plugin, PDC_MULTIPLIER), PersistentDataType.DOUBLE, multiplier)
                pdc.set(NamespacedKey(plugin, PDC_USES), PersistentDataType.INTEGER, uses)
                pdc.set(NamespacedKey(plugin, PDC_MAX_USES), PersistentDataType.INTEGER, uses)
            }
            return item
        }

        fun getMultiplier(plugin: Joshymc, item: ItemStack): Double {
            val meta = item.itemMeta ?: return 1.0
            return meta.persistentDataContainer.getOrDefault(
                NamespacedKey(plugin, PDC_MULTIPLIER), PersistentDataType.DOUBLE, 1.0
            )
        }

        fun getUses(plugin: Joshymc, item: ItemStack): Int {
            val meta = item.itemMeta ?: return 0
            return meta.persistentDataContainer.getOrDefault(
                NamespacedKey(plugin, PDC_USES), PersistentDataType.INTEGER, 0
            )
        }

        fun decrementUses(plugin: Joshymc, item: ItemStack): Int {
            val meta = item.itemMeta ?: return 0
            val current = meta.persistentDataContainer.getOrDefault(
                NamespacedKey(plugin, PDC_USES), PersistentDataType.INTEGER, 0
            )
            val remaining = current - 1

            if (remaining <= 0) return 0

            // Update uses in PDC
            meta.persistentDataContainer.set(
                NamespacedKey(plugin, PDC_USES), PersistentDataType.INTEGER, remaining
            )

            // Update lore to show new uses count
            val maxUses = meta.persistentDataContainer.getOrDefault(
                NamespacedKey(plugin, PDC_MAX_USES), PersistentDataType.INTEGER, current
            )
            val multiplier = meta.persistentDataContainer.getOrDefault(
                NamespacedKey(plugin, PDC_MULTIPLIER), PersistentDataType.DOUBLE, 1.0
            )

            val lore = mutableListOf<Component>()
            lore.add(Component.empty())
            lore.add(
                Component.text("  Multiplier: ", NamedTextColor.GRAY)
                    .append(Component.text("${multiplier}x", NamedTextColor.GOLD))
                    .decoration(TextDecoration.ITALIC, false)
            )
            lore.add(
                Component.text("  Uses: ", NamedTextColor.GRAY)
                    .append(Component.text("$remaining/$maxUses", NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false)
            )
            lore.add(Component.empty())
            lore.add(
                Component.text("  Right-click a chest to sell its contents.", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(lore)

            item.itemMeta = meta
            return remaining
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.sellwand.give")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        // /sellwand <multiplier> <uses> [player]
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /sellwand <multiplier> <uses> [player]", NamedTextColor.RED))
            return true
        }

        val multiplier = args[0].toDoubleOrNull()
        if (multiplier == null || multiplier <= 0) {
            sender.sendMessage(Component.text("Multiplier must be a positive number (e.g., 1.5, 2, 3).", NamedTextColor.RED))
            return true
        }

        val uses = args[1].toIntOrNull()
        if (uses == null || uses <= 0) {
            sender.sendMessage(Component.text("Uses must be a positive integer.", NamedTextColor.RED))
            return true
        }

        val target: Player = if (args.size >= 3) {
            Bukkit.getPlayer(args[2]) ?: run {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                return true
            }
        } else if (sender is Player) {
            sender
        } else {
            sender.sendMessage(Component.text("Specify a player: /sellwand <multiplier> <uses> <player>", NamedTextColor.RED))
            return true
        }

        val wand = createSellWand(plugin, multiplier, uses)
        val leftover = target.inventory.addItem(wand)
        for ((_, drop) in leftover) {
            target.world.dropItemNaturally(target.location, drop)
        }

        if (sender is Player) {
            plugin.commsManager.send(
                sender,
                Component.text("Gave ", NamedTextColor.GREEN)
                    .append(Component.text("${multiplier}x", NamedTextColor.GOLD))
                    .append(Component.text(" Sell Wand (${uses} uses) to ", NamedTextColor.GREEN))
                    .append(Component.text(target.name, NamedTextColor.WHITE)),
                CommunicationsManager.Category.ECONOMY
            )
        }

        if (target != sender) {
            plugin.commsManager.send(
                target,
                Component.text("You received a ", NamedTextColor.GREEN)
                    .append(Component.text("${multiplier}x", NamedTextColor.GOLD))
                    .append(Component.text(" Sell Wand (${uses} uses)!", NamedTextColor.GREEN)),
                CommunicationsManager.Category.ECONOMY
            )
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("1", "1.5", "2", "3", "5").filter { it.startsWith(args[0]) }
            2 -> listOf("1", "5", "10", "25", "50", "100").filter { it.startsWith(args[1]) }
            3 -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
            else -> emptyList()
        }
    }
}
