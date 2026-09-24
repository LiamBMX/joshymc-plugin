package com.liam.joshymc.gui.claim

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player

/** Generic yes/no confirmation screen for destructive /claim GUI actions (issue #612). */
object ClaimConfirmGui {

    fun open(
        plugin: Joshymc,
        player: Player,
        question: Component,
        details: List<Component> = emptyList(),
        onConfirm: (Joshymc, Player) -> Unit,
        onCancel: (Joshymc, Player) -> Unit = { p, viewer -> ClaimMainGui.open(p, viewer) }
    ) {
        val gui = CustomGui(Component.text("Are You Sure?", NamedTextColor.RED), 27)
        for (slot in 0..26) gui.setItem(slot, ClaimGuiUtil.filler())

        gui.setItem(13, ClaimGuiUtil.item(Material.PAPER, question, details))
        gui.setItem(11, ClaimGuiUtil.item(Material.LIME_DYE, Component.text("Confirm", NamedTextColor.GREEN))) { p, _ ->
            onConfirm(plugin, p)
        }
        gui.setItem(15, ClaimGuiUtil.item(Material.RED_DYE, Component.text("Cancel", NamedTextColor.RED))) { p, _ ->
            onCancel(plugin, p)
        }

        plugin.guiManager.open(player, gui)
    }
}
