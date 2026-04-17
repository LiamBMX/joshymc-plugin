package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.TalismanRarity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class TalismanCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            // Allow admin subcommands from console
            if (args.isNotEmpty() && args[0].equals("give", ignoreCase = true)) {
                handleGive(sender, args)
                return true
            }
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.talisman")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            plugin.talismanManager.openTalismanMenu(sender)
            return true
        }

        when (args[0].lowercase()) {
            "equip" -> handleEquip(sender, args)
            "unequip" -> handleUnequip(sender)
            "info" -> handleInfo(sender)
            "list" -> handleList(sender)
            "give" -> handleGive(sender, args)
            "grant" -> handleGrant(sender, args)
            "revoke" -> handleRevoke(sender, args)
            "talismans" -> plugin.talismanManager.openTalismanGui(sender)
            "relics" -> plugin.talismanManager.openRelicGui(sender)
            else -> sendUsage(sender)
        }
        return true
    }

    private fun handleEquip(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /talisman equip <id>", NamedTextColor.RED))
            return
        }

        val id = args[1].lowercase()
        val def = plugin.talismanManager.getTalisman(id)
        if (def == null) {
            plugin.commsManager.send(player, Component.text("Unknown talisman: $id", NamedTextColor.RED))
            return
        }

        if (!plugin.talismanManager.canEquip(player, def)) {
            plugin.commsManager.send(player,
                Component.text("This relic is already claimed by another player.", NamedTextColor.RED))
            return
        }

        if (plugin.talismanManager.equipTalisman(player, id)) {
            val nameColor = if (def.unique) NamedTextColor.GOLD else NamedTextColor.YELLOW
            plugin.commsManager.send(player,
                Component.text("Equipped ", NamedTextColor.GREEN)
                    .append(Component.text(def.name, nameColor)))

            if (def.unique) {
                val message = plugin.commsManager.parseLegacy(
                    "&6&l${player.name} has claimed the ${def.rarity.color}${def.name}&6&l!"
                )
                Bukkit.getOnlinePlayers().forEach { it.sendMessage(message) }
            }
        } else {
            plugin.commsManager.send(player, Component.text("Failed to equip talisman.", NamedTextColor.RED))
        }
    }

    private fun handleUnequip(player: Player) {
        val current = plugin.talismanManager.getPlayerTalisman(player.uniqueId)
        if (current == null) {
            plugin.commsManager.send(player, Component.text("You don't have a talisman equipped.", NamedTextColor.RED))
            return
        }

        plugin.talismanManager.unequipTalisman(player)
        plugin.commsManager.send(player,
            Component.text("Unequipped ", NamedTextColor.YELLOW)
                .append(Component.text(current.name, NamedTextColor.GRAY)))
    }

    private fun handleInfo(player: Player) {
        val def = plugin.talismanManager.getPlayerTalisman(player.uniqueId)
        if (def == null) {
            plugin.commsManager.send(player, Component.text("You don't have a talisman equipped.", NamedTextColor.GRAY))
            plugin.commsManager.send(player,
                Component.text("Use ", NamedTextColor.GRAY)
                    .append(Component.text("/talisman", NamedTextColor.YELLOW))
                    .append(Component.text(" to browse and equip one.", NamedTextColor.GRAY)))
            return
        }

        val nameColor = if (def.unique) NamedTextColor.GOLD else NamedTextColor.YELLOW
        plugin.commsManager.send(player,
            Component.text("Equipped: ", NamedTextColor.GRAY)
                .append(Component.text(def.name, nameColor).decoration(TextDecoration.BOLD, def.unique)))
        plugin.commsManager.send(player,
            Component.text("Type: ", NamedTextColor.GRAY)
                .append(Component.text(def.rarity.displayName, if (def.unique) NamedTextColor.GOLD else NamedTextColor.YELLOW)))
        plugin.commsManager.send(player,
            Component.text(def.description, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true))

        plugin.commsManager.send(player, Component.text("Effects:", NamedTextColor.WHITE))
        for (effect in def.effects) {
            plugin.commsManager.send(player,
                Component.text(" \u2022 ${describeEffect(effect)}", NamedTextColor.GREEN))
        }
    }

    private fun handleList(sender: CommandSender) {
        val all = plugin.talismanManager.getAllTalismans()
        val talismans = all.filter { !it.unique }
        val relics = all.filter { it.unique }

        sender.sendMessage(Component.text("--- Talismans ---", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
        for (def in talismans) {
            sender.sendMessage(Component.text(" ${def.id}", NamedTextColor.WHITE)
                .append(Component.text(" - ${def.name}", NamedTextColor.YELLOW)))
        }

        sender.sendMessage(Component.empty())
        sender.sendMessage(Component.text("--- Relics ---", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
        for (def in relics) {
            val owner = plugin.talismanManager.getRelicOwner(def.id)
            val status = if (owner != null) {
                val name = Bukkit.getOfflinePlayer(owner).name ?: "Unknown"
                Component.text(" [Owned by $name]", NamedTextColor.RED)
            } else {
                Component.text(" [UNCLAIMED]", NamedTextColor.GREEN)
            }
            sender.sendMessage(Component.text(" ${def.id}", NamedTextColor.WHITE)
                .append(Component.text(" - ${def.name}", NamedTextColor.GOLD))
                .append(status))
        }
    }

    private fun handleGive(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.talisman.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return
        }

        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /talisman give <player> <id>", NamedTextColor.RED))
            return
        }

        val target = Bukkit.getPlayer(args[1])
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: ${args[1]}", NamedTextColor.RED))
            return
        }

        val id = args[2].lowercase()
        val def = plugin.talismanManager.getTalisman(id)
        if (def == null) {
            sender.sendMessage(Component.text("Unknown talisman: $id", NamedTextColor.RED))
            return
        }

        // Admin give bypasses relic ownership — force unequip current owner if relic
        if (def.unique) {
            val currentOwner = plugin.talismanManager.getRelicOwner(id)
            if (currentOwner != null && currentOwner != target.uniqueId) {
                val ownerPlayer = Bukkit.getPlayer(currentOwner)
                if (ownerPlayer != null) {
                    plugin.talismanManager.unequipTalisman(ownerPlayer)
                    plugin.commsManager.send(ownerPlayer,
                        Component.text("Your relic has been reassigned by an admin.", NamedTextColor.RED))
                } else {
                    // Offline — remove from DB directly
                    plugin.databaseManager.execute(
                        "DELETE FROM player_talismans WHERE uuid = ?", currentOwner.toString()
                    )
                }
            }
        }

        if (plugin.talismanManager.equipTalisman(target, id)) {
            val nameColor = if (def.unique) NamedTextColor.GOLD else NamedTextColor.YELLOW
            sender.sendMessage(Component.text("Gave ", NamedTextColor.GREEN)
                .append(Component.text(def.name, nameColor))
                .append(Component.text(" to ${target.name}", NamedTextColor.GREEN)))
            plugin.commsManager.send(target,
                Component.text("An admin gave you ", NamedTextColor.GREEN)
                    .append(Component.text(def.name, nameColor)))
        } else {
            sender.sendMessage(Component.text("Failed to equip talisman.", NamedTextColor.RED))
        }
    }

    private fun handleGrant(sender: Player, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.talisman.admin")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED)); return
        }
        if (args.size < 3) {
            plugin.commsManager.send(sender, Component.text("Usage: /talisman grant <player> <id>", NamedTextColor.RED)); return
        }
        val target = Bukkit.getOfflinePlayer(args[1])
        val def = plugin.talismanManager.getTalisman(args[2].lowercase())
        if (def == null) { plugin.commsManager.send(sender, Component.text("Unknown talisman.", NamedTextColor.RED)); return }
        plugin.talismanManager.grantAccess(target.uniqueId, def.id)
        plugin.commsManager.send(sender, Component.text("Granted ${target.name} access to ${def.name}.", NamedTextColor.GREEN))
    }

    private fun handleRevoke(sender: Player, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.talisman.admin")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED)); return
        }
        if (args.size < 3) {
            plugin.commsManager.send(sender, Component.text("Usage: /talisman revoke <player> <id>", NamedTextColor.RED)); return
        }
        val target = Bukkit.getOfflinePlayer(args[1])
        val def = plugin.talismanManager.getTalisman(args[2].lowercase())
        if (def == null) { plugin.commsManager.send(sender, Component.text("Unknown talisman.", NamedTextColor.RED)); return }
        plugin.talismanManager.revokeAccess(target.uniqueId, def.id)
        plugin.commsManager.send(sender, Component.text("Revoked ${target.name}'s access to ${def.name}.", NamedTextColor.RED))
    }

    private fun sendUsage(player: Player) {
        plugin.commsManager.send(player, Component.text("Usage:", NamedTextColor.YELLOW))
        val usages = listOf(
            "/talisman" to "Open the talisman menu",
            "/talisman equip <id>" to "Equip a talisman",
            "/talisman unequip" to "Unequip your talisman",
            "/talisman info" to "View your current talisman",
            "/talisman list" to "List all talismans"
        )
        for ((cmd, desc) in usages) {
            player.sendMessage(Component.text(" $cmd ", NamedTextColor.WHITE)
                .append(Component.text("- $desc", NamedTextColor.GRAY)))
        }
    }

    private fun describeEffect(effect: com.liam.joshymc.manager.TalismanEffect): String {
        return when (effect) {
            is com.liam.joshymc.manager.TalismanEffect.DamageBonus -> {
                val suffix = when {
                    effect.mobsOnly -> " (mobs)"
                    effect.pvpOnly -> " (PvP)"
                    else -> ""
                }
                "+${effect.percent.toInt()}% damage$suffix"
            }
            is com.liam.joshymc.manager.TalismanEffect.DamageReduction -> "-${effect.percent.toInt()}% damage taken"
            is com.liam.joshymc.manager.TalismanEffect.DropMultiplier -> "+${effect.percent.toInt()}% drop rate"
            is com.liam.joshymc.manager.TalismanEffect.SellBonus -> "+${effect.percent.toInt()}% sell prices"
            is com.liam.joshymc.manager.TalismanEffect.XpBonus -> "+${effect.percent.toInt()}% XP"
            is com.liam.joshymc.manager.TalismanEffect.SpeedBonus -> "+${effect.percent.toInt()}% speed"
            is com.liam.joshymc.manager.TalismanEffect.ExtraHearts -> "+${effect.hearts} extra hearts"
            is com.liam.joshymc.manager.TalismanEffect.PotionBuff -> {
                val name = effect.effectType.key.key.replace("_", " ").replaceFirstChar { it.uppercase() }
                val level = effect.amplifier + 1
                "$name ${"I".repeat(level)}"
            }
            is com.liam.joshymc.manager.TalismanEffect.FallDamageReduction -> "No fall damage under ${effect.maxBlocks} blocks"
            is com.liam.joshymc.manager.TalismanEffect.TransactionTax -> "${effect.percent}% tax on transactions"
            is com.liam.joshymc.manager.TalismanEffect.AutoSmelt -> "Auto-smelt ores"
            is com.liam.joshymc.manager.TalismanEffect.AutoReplant -> "Auto-replant crops"
            is com.liam.joshymc.manager.TalismanEffect.FishingSpeed -> "+${effect.percent.toInt()}% fishing speed"
            is com.liam.joshymc.manager.TalismanEffect.PotionDuration -> "+${effect.percent.toInt()}% potion duration"
            is com.liam.joshymc.manager.TalismanEffect.FlightInClaims -> "Flight in your claims"
            is com.liam.joshymc.manager.TalismanEffect.MiningSpeed -> "+${effect.percent.toInt()}% mining speed"
            is com.liam.joshymc.manager.TalismanEffect.DoubleDropChance -> "${effect.percent.toInt()}% double drop chance"
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val subs = mutableListOf("equip", "unequip", "info", "list", "talismans", "relics")
            if (sender.hasPermission("joshymc.talisman.admin")) { subs.add("give"); subs.add("grant"); subs.add("revoke") }
            return subs.filter { it.startsWith(args[0], ignoreCase = true) }
        }

        when (args[0].lowercase()) {
            "equip" -> {
                if (args.size == 2) {
                    return plugin.talismanManager.getAllTalismans()
                        .map { it.id }
                        .filter { it.startsWith(args[1], ignoreCase = true) }
                }
            }
            "give" -> {
                if (!sender.hasPermission("joshymc.talisman.admin")) return emptyList()
                if (args.size == 2) {
                    return Bukkit.getOnlinePlayers()
                        .map { it.name }
                        .filter { it.startsWith(args[1], ignoreCase = true) }
                }
                if (args.size == 3) {
                    return plugin.talismanManager.getAllTalismans()
                        .map { it.id }
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                }
            }
        }
        return emptyList()
    }
}
