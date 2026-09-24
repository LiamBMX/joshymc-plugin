package com.liam.joshymc.gui.bounty

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.SkullMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * Single bounty detail view opened from [BountyListGui] (issue #494). Always
 * re-fetches the bounty from the database rather than trusting the caller's
 * cached copy, so a bounty cancelled/claimed by someone else between opening
 * the list and clicking an entry can't show stale data.
 *
 * Cancelling is only ever offered here (never on the list itself, to avoid
 * accidental clicks) and is re-validated server-side against
 * `joshymc.bounty.cancel` even though the button is already hidden for
 * players without it — the client can never be trusted to enforce this.
 */
object BountyDetailGui {

    /** Which menu "Back"/post-cancel navigation should return to (issue #556). */
    enum class Origin { ACTIVE, MINE }

    private val dateFormat = SimpleDateFormat("MM/dd/yyyy")

    fun open(plugin: Joshymc, player: Player, bountyId: Int, backPage: Int, origin: Origin = Origin.ACTIVE) {
        val bounty = plugin.teamManager.getBounty(bountyId)
        if (bounty == null) {
            plugin.commsManager.send(player, Component.text("That bounty is no longer active.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            returnToOrigin(plugin, player, backPage, origin)
            return
        }

        val gui = CustomGui(Component.text("Bounty #${bounty.id}", NamedTextColor.GOLD), 27)
        for (slot in 0..26) gui.setItem(slot, BountyGuiUtil.filler())

        val target = Bukkit.getOfflinePlayer(UUID.fromString(bounty.targetUuid))
        val head = org.bukkit.inventory.ItemStack(Material.PLAYER_HEAD)
        head.editMeta { meta ->
            (meta as? SkullMeta)?.owningPlayer = target
            meta.displayName(Component.text(bounty.targetName, NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
            meta.lore(
                listOf(
                    Component.empty(),
                    Component.text("Bounty: ", NamedTextColor.GRAY).append(Component.text("$${plugin.economyManager.formatShort(bounty.amount)}", NamedTextColor.GREEN)),
                    Component.text("Placed By: ", NamedTextColor.GRAY).append(Component.text(bounty.placedByName, NamedTextColor.WHITE)),
                    Component.text("Bounty ID: ", NamedTextColor.GRAY).append(Component.text("#${bounty.id}", NamedTextColor.WHITE)),
                    Component.text("Placed: ", NamedTextColor.GRAY).append(Component.text(dateFormat.format(Date(bounty.placedAt)), NamedTextColor.WHITE))
                ).map { it.decoration(TextDecoration.ITALIC, false) }
            )
        }
        gui.setItem(13, head)

        val backLabel = if (origin == Origin.MINE) "Back to My Bounties" else "Back to Bounty List"
        gui.setItem(18, BountyGuiUtil.item(Material.ARROW, Component.text(backLabel, NamedTextColor.YELLOW))) { p, _ ->
            returnToOrigin(plugin, p, backPage, origin)
        }

        if (player.hasPermission("joshymc.bounty.cancel")) {
            gui.setItem(
                26,
                BountyGuiUtil.item(
                    Material.BARRIER,
                    Component.text("Cancel Bounty", NamedTextColor.RED),
                    listOf(Component.empty(), Component.text("Click to cancel and refund this bounty.", NamedTextColor.GRAY))
                )
            ) { p, _ -> handleCancelClick(plugin, p, bounty.id, backPage, origin) }
        }

        plugin.guiManager.open(player, gui)
    }

    private fun handleCancelClick(plugin: Joshymc, player: Player, bountyId: Int, backPage: Int, origin: Origin) {
        // Server-side re-check: the button is hidden without this permission,
        // but never trust the client to have actually enforced that.
        if (!player.hasPermission("joshymc.bounty.cancel")) {
            plugin.commsManager.send(player, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.cancelBounty(bountyId)) {
            plugin.commsManager.send(
                player,
                Component.text("Bounty #$bountyId cancelled. Money refunded.", NamedTextColor.GREEN),
                CommunicationsManager.Category.DEFAULT
            )
        } else {
            plugin.commsManager.send(player, Component.text("That bounty is no longer active.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }

        returnToOrigin(plugin, player, backPage, origin)
    }

    private fun returnToOrigin(plugin: Joshymc, player: Player, backPage: Int, origin: Origin) {
        when (origin) {
            Origin.ACTIVE -> BountyListGui.open(plugin, player, backPage)
            Origin.MINE -> BountyMyGui.open(plugin, player, backPage)
        }
    }
}
