package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class CondenseCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        /** resource material -> its vanilla 9-item storage block. */
        private val CONDENSE_MAP: Map<Material, Material> = linkedMapOf(
            Material.COAL to Material.COAL_BLOCK,
            Material.RAW_IRON to Material.RAW_IRON_BLOCK,
            Material.RAW_GOLD to Material.RAW_GOLD_BLOCK,
            Material.RAW_COPPER to Material.RAW_COPPER_BLOCK,
            Material.IRON_INGOT to Material.IRON_BLOCK,
            Material.GOLD_INGOT to Material.GOLD_BLOCK,
            Material.COPPER_INGOT to Material.COPPER_BLOCK,
            Material.DIAMOND to Material.DIAMOND_BLOCK,
            Material.EMERALD to Material.EMERALD_BLOCK,
            Material.LAPIS_LAZULI to Material.LAPIS_BLOCK,
            Material.REDSTONE to Material.REDSTONE_BLOCK,
            Material.NETHERITE_INGOT to Material.NETHERITE_BLOCK,
        )

        /** Only plain, unmodified vanilla stacks are eligible — never JoshyMC custom items or enchanted/renamed items. */
        private fun isPlainVanilla(plugin: Joshymc, stack: ItemStack): Boolean {
            if (plugin.itemManager.getCustomItemId(stack) != null) return false
            val meta = stack.itemMeta ?: return true
            if (meta.hasDisplayName()) return false
            if (meta.hasLore()) return false
            if (meta.hasEnchants()) return false
            if (meta.hasAttributeModifiers()) return false
            @Suppress("DEPRECATION")
            if (meta.hasCustomModelData()) return false
            if (!meta.persistentDataContainer.keys.isEmpty()) return false
            return true
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.condense")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.MINING)
            return true
        }

        val working = sender.inventory.storageContents.map { it?.clone() }.toTypedArray()

        var totalItemsCondensed = 0
        var totalBlocksCreated = 0
        val perMaterialSummary = mutableListOf<Pair<Material, Int>>()

        for ((resource, block) in CONDENSE_MAP) {
            val total = working.filterNotNull()
                .filter { it.type == resource && isPlainVanilla(plugin, it) }
                .sumOf { it.amount }

            val blocks = total / 9
            if (blocks <= 0) continue

            val toRemove = blocks * 9
            val snapshot = working.map { it?.clone() }.toTypedArray()

            if (!removeAmount(working, resource, toRemove) || !addAmount(working, block, blocks)) {
                // Couldn't safely remove/fit the result — revert this material and skip it.
                snapshot.copyInto(working)
                continue
            }

            totalItemsCondensed += toRemove
            totalBlocksCreated += blocks
            perMaterialSummary.add(resource to blocks)
        }

        if (totalBlocksCreated <= 0) {
            plugin.commsManager.send(
                sender,
                Component.text("You don't have enough materials to condense.", NamedTextColor.RED),
                CommunicationsManager.Category.MINING
            )
            return true
        }

        sender.inventory.storageContents = working

        val message = if (perMaterialSummary.size == 1) {
            val (resource, blocks) = perMaterialSummary[0]
            Component.text("Condensed ", NamedTextColor.GRAY)
                .append(Component.text("$totalItemsCondensed ${resource.displayName()}", NamedTextColor.AQUA))
                .append(Component.text(" into ", NamedTextColor.GRAY))
                .append(Component.text("$blocks ${CONDENSE_MAP.getValue(resource).displayName()}.", NamedTextColor.AQUA))
        } else {
            Component.text("Condensed ", NamedTextColor.GRAY)
                .append(Component.text("$totalItemsCondensed items", NamedTextColor.AQUA))
                .append(Component.text(" into ", NamedTextColor.GRAY))
                .append(Component.text("$totalBlocksCreated storage blocks.", NamedTextColor.AQUA))
        }
        plugin.commsManager.send(sender, message, CommunicationsManager.Category.MINING)
        return true
    }

    /** Removes [amount] plain-vanilla [material] items from [contents], preferring to fully empty stacks. Returns false if not enough was found. */
    private fun removeAmount(contents: Array<ItemStack?>, material: Material, amount: Int): Boolean {
        var remaining = amount
        for (i in contents.indices) {
            if (remaining <= 0) break
            val stack = contents[i] ?: continue
            if (stack.type != material || !isPlainVanilla(plugin, stack)) continue

            val take = minOf(stack.amount, remaining)
            stack.amount -= take
            remaining -= take
            if (stack.amount <= 0) contents[i] = null
        }
        return remaining <= 0
    }

    /** Adds [amount] of [material] into [contents], merging into existing stacks first, then empty slots. Returns false if it doesn't all fit. */
    private fun addAmount(contents: Array<ItemStack?>, material: Material, amount: Int): Boolean {
        var remaining = amount
        val maxStack = material.maxStackSize

        for (i in contents.indices) {
            if (remaining <= 0) break
            val stack = contents[i] ?: continue
            if (stack.type != material) continue
            val space = maxStack - stack.amount
            if (space <= 0) continue
            val add = minOf(space, remaining)
            stack.amount += add
            remaining -= add
        }

        for (i in contents.indices) {
            if (remaining <= 0) break
            if (contents[i] != null) continue
            val add = minOf(maxStack, remaining)
            contents[i] = ItemStack(material, add)
            remaining -= add
        }

        return remaining <= 0
    }

    private fun Material.displayName(): String {
        return name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return emptyList()
    }
}
