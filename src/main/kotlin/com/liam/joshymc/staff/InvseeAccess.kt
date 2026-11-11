package com.liam.joshymc.staff

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.PlayerInventory
import java.util.UUID

/**
 * Bukkit side of [InvseeRules] (issue #1353): the one way staff open an online player's
 * inventory (`/invsee` and ModMode's Invsee tool), plus [InvseeGuardListener].
 */
object InvseeAccess {

    /** True while [uuid] is held by a Lifesteal ranked match or waits for its snapshot restore. */
    fun rankedHeld(plugin: Joshymc, uuid: UUID): Boolean = try {
        plugin.lifestealRankedManager.holds(uuid) || plugin.lifestealRankedManager.hasPendingRestore(uuid)
    } catch (_: UninitializedPropertyAccessException) {
        false
    }

    /**
     * Opens [target]'s inventory for [viewer]: live only when [InvseeRules.decide] allows it,
     * else the read-only copy (`AdminManager.openInvsee`, every click cancelled). Writes one
     * [auditAction] row with the mode.
     */
    fun open(plugin: Joshymc, viewer: Player, target: Player, canEdit: Boolean, auditAction: String, restrictedCopy: Boolean = true): InvseeRules.Decision {
        val self = viewer.uniqueId == target.uniqueId
        val protectionRefused = !self && plugin.staffRankManager.protectionProblem(viewer, target.uniqueId) != null
        val decision = InvseeRules.decide(canEdit, rankedHeld(plugin, target.uniqueId), protectionRefused, self)
        if (decision.mode == InvseeRules.Mode.LIVE) {
            viewer.openInventory(target.inventory)
        } else {
            plugin.adminManager.openInvsee(viewer, target, restricted = restrictedCopy)
            decision.reason.message?.let { plugin.commsManager.send(viewer, Component.text(it, NamedTextColor.YELLOW)) }
        }
        AdminAudit.log(plugin, viewer, auditAction, target.uniqueId, target.name,
            "mode=${decision.mode.name.lowercase()} reason=${decision.reason.name.lowercase()}")
        return decision
    }
}

/**
 * Cancels clicks and drags by anyone else in a live view of a player's inventory while that
 * player is held by a ranked match (issue #1353): a live `/invsee` opened before the match
 * started must not take items the match snapshot will give back.
 */
class InvseeGuardListener(private val plugin: Joshymc) : Listener {

    private fun refused(view: InventoryView, viewer: Player): Boolean {
        val top = view.topInventory as? PlayerInventory ?: return false
        val holder = top.holder as? Player ?: return false
        return InvseeRules.liveClickRefused(holder.uniqueId == viewer.uniqueId, InvseeAccess.rankedHeld(plugin, holder.uniqueId))
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onClick(event: InventoryClickEvent) {
        val viewer = event.whoClicked as? Player ?: return
        if (!refused(event.view, viewer)) return
        event.isCancelled = true
        plugin.server.scheduler.runTask(plugin, Runnable { viewer.closeInventory() })
        plugin.commsManager.send(viewer, Component.text(InvseeRules.Reason.RANKED.message!!, NamedTextColor.YELLOW))
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrag(event: InventoryDragEvent) {
        val viewer = event.whoClicked as? Player ?: return
        if (refused(event.view, viewer)) event.isCancelled = true
    }
}
