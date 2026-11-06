package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.RankLimits
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class SetHomeCommand(private val plugin: Joshymc) : CommandExecutor {

    companion object {
        private val NAME_REGEX = Regex("^[a-zA-Z0-9_]{1,20}$")
    }

    // Returns -1 for unlimited, otherwise the highest home limit across this player's ranks and
    // their donor tier (issue #1381: tiers live in LuckPerms, so getPlayerRankIds never has them).
    private fun homeLimit(player: org.bukkit.entity.Player): Int = RankLimits.resolve(
        plugin.rankManager.getPlayerRankIds(player.uniqueId),
        plugin.rankManager.currentDonorTier(player.uniqueId),
        { rankId -> plugin.config.getInt("homes.limits-by-rank.$rankId", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } },
        plugin.config.getInt("homes.max-per-player", 3)
    )

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }

        val name = (args.getOrNull(0) ?: "home").lowercase()
        val uuid = sender.uniqueId.toString()

        if (!NAME_REGEX.matches(name)) {
            plugin.commsManager.send(sender, Component.text("Home names must be alphanumeric/underscores, max 20 characters.", NamedTextColor.RED), CommunicationsManager.Category.HOME)
            return true
        }

        val claim = plugin.claimManager.getClaimAt(sender.location)
        if (claim != null && !plugin.claimManager.canAccess(sender, sender.location)) {
            plugin.commsManager.send(sender, Component.text("You cannot set a home inside someone else's claim.", NamedTextColor.RED), CommunicationsManager.Category.HOME)
            return true
        }

        if (!sender.hasPermission("joshymc.sethome.unlimited")) {
            val max = homeLimit(sender)
            if (max != -1) {
                val current = plugin.warpManager.getHomeCount(uuid)
                val isOverwrite = plugin.warpManager.getHome(uuid, name) != null
                if (current >= max && !isOverwrite) {
                    plugin.commsManager.send(sender, Component.text("You have reached the home limit ($max).", NamedTextColor.RED), CommunicationsManager.Category.HOME)
                    return true
                }
            }
        }

        plugin.warpManager.setHome(uuid, name, sender.location)
        plugin.commsManager.send(sender, Component.text("Home '$name' set.", NamedTextColor.GREEN), CommunicationsManager.Category.HOME)
        return true
    }
}
