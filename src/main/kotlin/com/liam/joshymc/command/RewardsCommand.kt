package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class RewardsCommand(private val plugin: Joshymc) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }

        val unclaimed = plugin.questManager.getUnclaimedCount(sender.uniqueId)
        if (unclaimed == 0) {
            plugin.commsManager.send(sender, Component.text("No rewards to claim. Complete quests to earn rewards!", NamedTextColor.GRAY))
            return true
        }

        val claimed = plugin.questManager.claimAllRewards(sender)
        plugin.commsManager.send(sender, Component.text("Claimed $claimed reward${if (claimed != 1) "s" else ""}!", NamedTextColor.GREEN))
        return true
    }
}
