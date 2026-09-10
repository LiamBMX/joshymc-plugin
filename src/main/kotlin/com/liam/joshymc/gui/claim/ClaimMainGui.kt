package com.liam.joshymc.gui.claim

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Main /claim GUI (issue #612). Bare `/claim` with no subcommand opens this.
 * Every button routes into the existing claim manager/command logic instead
 * of duplicating protection, persistence, or permission checks. Context-aware:
 * if the player is standing in a claim they manage, that claim's controls are
 * one click away; existing chat subcommands keep working unchanged.
 */
object ClaimMainGui {

    fun open(plugin: Joshymc, player: Player) {
        val claim = plugin.claimManager.getClaimAt(player.location)
        val myClaims = plugin.claimManager.getClaimsByPlayer(player.uniqueId)
        val canManage = claim != null && plugin.claimManager.canManageClaim(player, claim)

        val gui = CustomGui(Component.text("Claim Panel", NamedTextColor.GOLD), 45)
        for (slot in 0..44) gui.setItem(slot, ClaimGuiUtil.filler())

        val total = plugin.claimManager.getTotalBlocks(player.uniqueId)
        val used = plugin.claimManager.getUsedBlocks(player.uniqueId)
        gui.setItem(4, ClaimGuiUtil.item(
            Material.GRASS_BLOCK,
            Component.text("Claim Blocks", NamedTextColor.GOLD),
            listOf(
                Component.empty(),
                Component.text("Available: ", NamedTextColor.GRAY).append(Component.text("${total - used}", NamedTextColor.GREEN)),
                Component.text("Used: ", NamedTextColor.GRAY).append(Component.text("$used", NamedTextColor.YELLOW)),
                Component.text("Total: ", NamedTextColor.GRAY).append(Component.text("$total", NamedTextColor.WHITE)),
                Component.empty(),
                Component.text("Your Claims: ", NamedTextColor.GRAY).append(Component.text("${myClaims.size}", NamedTextColor.WHITE))
            )
        ))

        when {
            claim != null && canManage -> gui.setItem(10, ClaimGuiUtil.item(
                Material.GOLDEN_SHOVEL,
                Component.text("Current Claim", NamedTextColor.GREEN),
                listOf(
                    Component.empty(),
                    Component.text("You're standing in a claim you manage.", NamedTextColor.GRAY),
                    Component.text("Click to manage it directly.", NamedTextColor.YELLOW)
                )
            )) { p, _ -> ClaimManagementGui.open(plugin, p, claim.id) }

            claim != null -> gui.setItem(10, ClaimGuiUtil.item(
                Material.MAP,
                Component.text("This Land", NamedTextColor.YELLOW),
                listOf(
                    Component.empty(),
                    Component.text("Owner: ", NamedTextColor.GRAY).append(Component.text(Bukkit.getOfflinePlayer(claim.ownerUuid).name ?: "Unknown", NamedTextColor.WHITE)),
                    Component.text("You do not manage this claim.", NamedTextColor.RED),
                    Component.text("Click for read-only info.", NamedTextColor.GRAY)
                )
            )) { p, _ -> ClaimInfoGui.open(plugin, p, claim.id) { pl, viewer -> open(pl, viewer) } }

            else -> gui.setItem(10, ClaimGuiUtil.item(
                Material.BARRIER,
                Component.text("Not In A Claim", NamedTextColor.DARK_GRAY),
                listOf(Component.empty(), Component.text("You are not standing in any claim.", NamedTextColor.GRAY))
            ))
        }

        gui.setItem(12, ClaimGuiUtil.item(
            Material.GOLDEN_SHOVEL,
            Component.text("Create Claim", NamedTextColor.GREEN),
            listOf(
                Component.empty(),
                Component.text("Get a claim wand.", NamedTextColor.GRAY),
                Component.text("Right-click two corners to claim land.", NamedTextColor.GRAY),
                Component.text("Claim is created automatically!", NamedTextColor.YELLOW)
            )
        )) { p, _ ->
            p.closeInventory()
            Bukkit.dispatchCommand(p, "claim wand")
        }

        gui.setItem(14, ClaimGuiUtil.item(
            Material.CHEST,
            Component.text("My Claims", NamedTextColor.AQUA),
            if (myClaims.isEmpty())
                listOf(Component.empty(), Component.text("You have no claims yet.", NamedTextColor.GRAY))
            else
                listOf(
                    Component.empty(),
                    Component.text("You own ${myClaims.size} claim(s).", NamedTextColor.GRAY),
                    Component.text("Click to view & manage.", NamedTextColor.YELLOW)
                )
        )) { p, _ -> ClaimListGui.open(plugin, p) }

        gui.setItem(16, ClaimGuiUtil.item(
            Material.SPYGLASS,
            Component.text("Toggle Claim Borders", NamedTextColor.LIGHT_PURPLE),
            listOf(Component.empty(), Component.text("Show/hide nearby claim borders.", NamedTextColor.GRAY))
        )) { p, _ ->
            Bukkit.dispatchCommand(p, "claim show")
            open(plugin, p)
        }

        gui.setItem(22, ClaimGuiUtil.item(
            Material.WRITTEN_BOOK,
            Component.text("Help", NamedTextColor.WHITE),
            listOf(Component.empty(), Component.text("View all /claim commands in chat.", NamedTextColor.GRAY))
        )) { p, _ ->
            p.closeInventory()
            Bukkit.dispatchCommand(p, "claim help")
        }

        gui.setItem(40, ClaimGuiUtil.item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
    }
}
