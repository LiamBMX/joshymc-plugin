package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility
import com.liam.joshymc.staff.AdminAudit
import com.liam.joshymc.util.Msg
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
        if (sender !is Player) { Msg.playersOnly(sender); return true }
        if (!sender.hasPermission("joshymc.enchant")) {
            Msg.noPermission(sender)
            return true
        }

        if (args.isEmpty()) {
            Msg.usage(sender, "/enchant <enchantment> [level]")
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

        val rawLevel = args.getOrNull(1)
        val level = if (rawLevel == null) 1 else rawLevel.toIntOrNull() ?: 0
        val displayName = enchant.key.key.replace("_", " ").replaceFirstChar { it.uppercase() }

        // Issue #1487: vanilla limits (max level, item, conflicts) unless `joshymc.enchant.unsafe`.
        // Re-applying an enchantment the item already has replaces it, so it isn't a conflict.
        val conflict = item.enchantments.keys.firstOrNull { it != enchant && it.conflictsWith(enchant) }
            ?.let { it.key.key.replace("_", " ").replaceFirstChar { c -> c.uppercase() } }
        val decision = CompetitiveEligibility.enchantRequest(
            name = displayName,
            level = level,
            maxLevel = enchant.maxLevel,
            applies = enchant.canEnchantItem(item),
            conflict = conflict,
            unsafePermitted = sender.hasPermission(UNSAFE_PERMISSION),
        )
        val unsafe = when (decision) {
            is CompetitiveEligibility.EnchantRequest.Refused -> {
                plugin.commsManager.send(sender, Component.text(decision.message, NamedTextColor.RED))
                return true
            }
            is CompetitiveEligibility.EnchantRequest.Allowed -> decision.unsafe
        }

        val meta = item.itemMeta ?: return true
        meta.addEnchant(enchant, level, unsafe)
        item.itemMeta = meta
        if (unsafe) {
            AdminAudit.log(
                plugin, sender, "ENCHANT_UNSAFE", sender.uniqueId, sender.name,
                "enchant=${enchant.key} level=$level max=${enchant.maxLevel} item=${item.type.key}",
            )
            plugin.commsManager.send(
                sender,
                Component.text("Unsafe enchantment: items above vanilla limits earn no Lifesteal hearts, bounties or coins.", NamedTextColor.GRAY),
            )
        }

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
            2 -> {
                val max = findEnchantment(args[0].lowercase())?.maxLevel ?: 5
                val levels = (1..max).map { it.toString() } +
                    if (sender.hasPermission(UNSAFE_PERMISSION)) listOf("10", "50", "100", "255") else emptyList()
                levels.distinct().filter { it.startsWith(args[1]) }
            }
            else -> emptyList()
        }
    }

    companion object {
        /** Issue #1487: past vanilla limits (level, item, conflicts). Default false: grant it explicitly. */
        const val UNSAFE_PERMISSION = "joshymc.enchant.unsafe"
    }
}
