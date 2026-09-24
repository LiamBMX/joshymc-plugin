package com.liam.joshymc.gui.team

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player

/** Team Bank sub-GUI for the /team GUI (issue #523) — routes into /team deposit and /team withdraw. */
object TeamBankGui {

    fun open(plugin: Joshymc, player: Player) {
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            TeamMainGui.open(plugin, player)
            return
        }

        val role = plugin.teamManager.getPlayerRole(player.uniqueId)
        val canWithdraw = role == "owner" || role == "admin"
        val balance = plugin.teamManager.getTeamBalance(teamName)

        val gui = CustomGui(Component.text("Team Bank", NamedTextColor.GOLD), 27)
        for (slot in 0..26) gui.setItem(slot, TeamGuiUtil.filler())

        gui.setItem(13, TeamGuiUtil.item(
            Material.GOLD_INGOT,
            Component.text("Team Balance", NamedTextColor.YELLOW),
            listOf(Component.empty(), Component.text(plugin.economyManager.format(balance), NamedTextColor.GREEN))
        ))

        gui.setItem(11, TeamGuiUtil.item(
            Material.LIME_DYE,
            Component.text("Deposit", NamedTextColor.GREEN),
            listOf(Component.empty(), Component.text("Click to deposit money from your wallet.", NamedTextColor.GRAY))
        )) { p, _ ->
            TeamGuiInput.prompt(
                plugin, p,
                Component.text("Type the amount to deposit in chat.", NamedTextColor.YELLOW),
                validate = { input ->
                    if (plugin.economyManager.parseAmount(input) == null) Component.text("Invalid amount.", NamedTextColor.RED) else null
                },
                command = { input -> "team deposit $input" },
                andThen = { pl, viewer -> open(pl, viewer) }
            )
        }

        if (canWithdraw) {
            gui.setItem(15, TeamGuiUtil.item(
                Material.RED_DYE,
                Component.text("Withdraw", NamedTextColor.RED),
                listOf(Component.empty(), Component.text("Click to withdraw money to your wallet.", NamedTextColor.GRAY))
            )) { p, _ ->
                TeamGuiInput.prompt(
                    plugin, p,
                    Component.text("Type the amount to withdraw in chat.", NamedTextColor.YELLOW),
                    validate = { input ->
                        if (plugin.economyManager.parseAmount(input) == null) Component.text("Invalid amount.", NamedTextColor.RED) else null
                    },
                    command = { input -> "team withdraw $input" },
                    andThen = { pl, viewer -> open(pl, viewer) }
                )
            }
        } else {
            gui.setItem(15, TeamGuiUtil.item(
                Material.GRAY_DYE,
                Component.text("Withdraw", NamedTextColor.DARK_GRAY),
                listOf(Component.empty(), Component.text("You do not have permission to use this.", NamedTextColor.RED))
            ))
        }

        gui.setItem(22, TeamGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> TeamMainGui.open(plugin, p) }

        plugin.guiManager.open(player, gui)
    }
}
