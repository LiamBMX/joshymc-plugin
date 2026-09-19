package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.FakeBaseManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class SpawnStashCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Players only.")
            return true
        }
        if (!sender.hasPermission("joshymc.spawnstash")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        when (val result = plugin.fakeBaseManager.pasteTemplate(sender)) {
            is FakeBaseManager.PasteResult.Success ->
                plugin.commsManager.send(sender, Component.text("Fake base spawned.", NamedTextColor.GREEN))
            is FakeBaseManager.PasteResult.NoTemplate ->
                plugin.commsManager.send(sender, Component.text("No fake base template has been saved yet.", NamedTextColor.RED))
            is FakeBaseManager.PasteResult.Failure ->
                plugin.commsManager.send(sender, Component.text(result.reason, NamedTextColor.RED))
        }
        return true
    }
}
