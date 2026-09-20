package com.liam.joshymc.command

import com.destroystokyo.paper.profile.PlayerProfile
import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

class SkullCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED))
            return true
        }
        if (!sender.isOp && !sender.hasPermission("joshymc.skull")) {
            sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED))
            return true
        }
        if (args.size != 1) {
            sender.sendMessage(Component.text("Usage: /skull <player>", NamedTextColor.YELLOW))
            return true
        }

        val targetName = args[0]

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val profile = try {
                Bukkit.createProfile(targetName).takeIf { it.complete(true) }
            } catch (_: Exception) {
                null
            }

            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (!sender.isOnline) return@Runnable
                giveSkull(sender, targetName, profile)
            })
        })

        return true
    }

    private fun giveSkull(player: Player, targetName: String, profile: PlayerProfile?) {
        val offline: OfflinePlayer = Bukkit.getOfflinePlayer(targetName)
        if (profile == null && !offline.hasPlayedBefore() && !offline.isOnline) {
            player.sendMessage(Component.text("Could not find a player named '$targetName'.", NamedTextColor.RED))
            return
        }

        val resolvedName = profile?.name ?: offline.name ?: targetName
        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { meta ->
            if (profile != null) {
                meta.playerProfile = profile
            } else {
                meta.owningPlayer = offline
            }
            meta.displayName(
                Component.text("$resolvedName's Head", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
            )
        }

        val overflow = when {
            plugin.modModeManager.isModMode(player) -> plugin.modModeManager.addItemToBackup(player.uniqueId, head)
            plugin.traineeModeManager.isTraineeMode(player) -> plugin.traineeModeManager.addItemToBackup(player.uniqueId, head)
            else -> player.inventory.addItem(head).values.firstOrNull()
        }
        if (overflow != null) {
            player.world.dropItemNaturally(player.location, overflow)
            player.sendMessage(
                Component.text("Your inventory is full — ", NamedTextColor.YELLOW)
                    .append(Component.text("$resolvedName's Head", NamedTextColor.GOLD))
                    .append(Component.text(" was dropped at your feet.", NamedTextColor.YELLOW))
            )
        } else {
            player.sendMessage(
                Component.text("Gave you ", NamedTextColor.GREEN)
                    .append(Component.text("$resolvedName's Head", NamedTextColor.YELLOW))
                    .append(Component.text(".", NamedTextColor.GREEN))
            )
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.isOp && !sender.hasPermission("joshymc.skull")) return emptyList()
        return if (args.size == 1) {
            Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        } else {
            emptyList()
        }
    }
}
