package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Registry
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player

class EnchantCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.enchant")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("Usage: /enchant <enchantment> [level]", NamedTextColor.RED))
            return true
        }

        val item = sender.inventory.itemInMainHand
        if (item.type.isAir) {
            plugin.commsManager.send(sender, Component.text("Hold an item first.", NamedTextColor.RED))
            return true
        }

        val enchantName = args[0].lowercase()
        val enchant = findEnchantment(enchantName)
        if (enchant == null) {
            plugin.commsManager.send(sender, Component.text("Unknown enchantment: ${args[0]}", NamedTextColor.RED))
            return true
        }

        val level = args.getOrNull(1)?.toIntOrNull() ?: 1
        if (level < 1 || level > 255) {
            plugin.commsManager.send(sender, Component.text("Level must be 1-255.", NamedTextColor.RED))
            return true
        }

        // Force add enchantment bypassing restrictions
        val meta = item.itemMeta ?: return true
        meta.addEnchant(enchant, level, true) // true = ignore level restrictions
        item.itemMeta = meta

        val displayName = enchant.key.key.replace("_", " ").replaceFirstChar { it.uppercase() }
        plugin.commsManager.send(sender,
            Component.text("Applied ", NamedTextColor.GREEN)
                .append(Component.text("$displayName $level", NamedTextColor.AQUA))
                .append(Component.text(" to held item.", NamedTextColor.GREEN))
        )
        return true
    }

    private fun findEnchantment(name: String): Enchantment? {
        // Try exact key match first
        for (enchant in Registry.ENCHANTMENT) {
            if (enchant.key.key.equals(name, ignoreCase = true)) return enchant
        }
        // Try partial/alias match
        val cleaned = name.replace("_", "").replace("-", "").replace(" ", "").lowercase()
        for (enchant in Registry.ENCHANTMENT) {
            val key = enchant.key.key.replace("_", "").lowercase()
            if (key == cleaned) return enchant
        }
        // Common aliases
        val aliases = mapOf(
            "sharp" to "sharpness", "sharp5" to "sharpness",
            "prot" to "protection", "prot4" to "protection",
            "eff" to "efficiency", "eff5" to "efficiency",
            "unb" to "unbreaking", "unb3" to "unbreaking",
            "fort" to "fortune", "fort3" to "fortune",
            "silk" to "silk_touch", "silktouch" to "silk_touch",
            "fa" to "fire_aspect", "fireaspect" to "fire_aspect",
            "kb" to "knockback",
            "loot" to "looting",
            "pow" to "power",
            "inf" to "infinity",
            "flame" to "flame",
            "punch" to "punch",
            "resp" to "respiration",
            "aqua" to "aqua_affinity", "aquaaff" to "aqua_affinity",
            "depth" to "depth_strider", "ds" to "depth_strider",
            "frost" to "frost_walker", "fw" to "frost_walker",
            "mend" to "mending",
            "thorns" to "thorns",
            "bane" to "bane_of_arthropods", "boa" to "bane_of_arthropods",
            "smite" to "smite",
            "sweep" to "sweeping_edge", "sweeping" to "sweeping_edge",
            "loyalty" to "loyalty",
            "riptide" to "riptide",
            "channel" to "channeling", "chan" to "channeling",
            "multi" to "multishot",
            "pierce" to "piercing",
            "quick" to "quick_charge", "qc" to "quick_charge",
            "soul" to "soul_speed", "ss" to "soul_speed",
            "swift" to "swift_sneak",
        )
        val aliasKey = aliases[cleaned]
        if (aliasKey != null) {
            for (enchant in Registry.ENCHANTMENT) {
                if (enchant.key.key.equals(aliasKey, ignoreCase = true)) return enchant
            }
        }
        return null
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> {
                val enchants = mutableListOf<String>()
                for (enchant in Registry.ENCHANTMENT) {
                    enchants.add(enchant.key.key)
                }
                enchants.filter { it.startsWith(args[0].lowercase()) }.take(30)
            }
            2 -> listOf("1", "5", "10", "50", "100", "255").filter { it.startsWith(args[1]) }
            else -> emptyList()
        }
    }
}
