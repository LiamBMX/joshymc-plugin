package com.liam.joshymc.gui.bounty

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.TeamManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import kotlin.math.ceil

/**
 * GUI-based "Place Bounty" flow (issue #556): select target -> pick amount ->
 * confirm -> create. This is a thin front end over the existing bounty system —
 * [TeamManager.placeBounty] does the actual withdrawal + insert, same as
 * `/bounty set` always has. All rules (self-target, target must be online,
 * amount must be positive/finite, balance must cover it) are re-checked
 * server-side at every step, never trusted from earlier GUI state alone.
 */
object BountyPlaceGui {

    private const val PAGE_SIZE = 36
    private const val FIRST_CONTENT_SLOT = 9

    private val AMOUNT_DELTAS = listOf(
        -1_000_000.0 to 10,
        -100_000.0 to 11,
        -10_000.0 to 12,
        10_000.0 to 14,
        100_000.0 to 15,
        1_000_000.0 to 16
    )

    // ── Player selection ──

    fun openPlayerSelect(plugin: Joshymc, player: Player, page: Int) {
        val session = plugin.teamManager.getOrCreateBountySession(player.uniqueId)
        session.playerSelectPage = page

        val gui = CustomGui(Component.text("Place Bounty", NamedTextColor.GOLD), 54)
        for (slot in 0..8) gui.setItem(slot, BountyGuiUtil.filler())
        for (slot in 45..53) gui.setItem(slot, BountyGuiUtil.filler())
        gui.setItem(4, BountyGuiUtil.item(Material.PLAYER_HEAD, Component.text("Select a Target", NamedTextColor.YELLOW)))

        val targets = Bukkit.getOnlinePlayers().filter { it != player }.sortedBy { it.name }

        if (targets.isEmpty()) {
            gui.setItem(
                22,
                BountyGuiUtil.item(
                    Material.BARRIER,
                    Component.text("No Valid Targets", NamedTextColor.RED),
                    listOf(Component.empty(), Component.text("There are no other players online right now.", NamedTextColor.GRAY))
                )
            )
            gui.setItem(48, BountyGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> BountyMainGui.open(plugin, p) }
            gui.setItem(49, BountyGuiUtil.item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }
            plugin.guiManager.open(player, gui)
            return
        }

        val totalPages = maxOf(1, ceil(targets.size / PAGE_SIZE.toDouble()).toInt())
        val clampedPage = page.coerceIn(0, totalPages - 1)
        val pageTargets = targets.drop(clampedPage * PAGE_SIZE).take(PAGE_SIZE)

        for ((index, target) in pageTargets.withIndex()) {
            gui.setItem(FIRST_CONTENT_SLOT + index, buildTargetIcon(plugin, target)) { p, _ ->
                val s = plugin.teamManager.getOrCreateBountySession(p.uniqueId)
                s.targetUuid = target.uniqueId
                s.targetName = target.name
                openAmountSelect(plugin, p)
            }
        }

        if (clampedPage > 0) {
            gui.setItem(45, BountyGuiUtil.item(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW))) { p, _ ->
                openPlayerSelect(plugin, p, clampedPage - 1)
            }
        }

        gui.setItem(47, BountyGuiUtil.item(Material.PAPER, Component.text("Page ${clampedPage + 1}/$totalPages", NamedTextColor.WHITE)))
        gui.setItem(48, BountyGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> BountyMainGui.open(plugin, p) }
        gui.setItem(49, BountyGuiUtil.item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }

        if (clampedPage < totalPages - 1) {
            gui.setItem(53, BountyGuiUtil.item(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW))) { p, _ ->
                openPlayerSelect(plugin, p, clampedPage + 1)
            }
        }

        gui.onClose = { p -> if (!plugin.teamManager.isAwaitingBountyAmountInput(p.uniqueId)) plugin.teamManager.clearBountySession(p.uniqueId) }
        plugin.guiManager.open(player, gui)
    }

    private fun buildTargetIcon(plugin: Joshymc, target: Player): ItemStack {
        val currentBounty = plugin.teamManager.getTotalBounty(target.uniqueId)

        val item = ItemStack(Material.PLAYER_HEAD)
        item.editMeta { meta ->
            if (meta is SkullMeta) meta.owningPlayer = target
            meta.displayName(Component.text(target.name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
            val lore = mutableListOf(Component.empty())
            if (currentBounty > 0) {
                lore.add(Component.text("Current Bounty: ", NamedTextColor.GRAY).append(Component.text("$${plugin.economyManager.formatShort(currentBounty)}", NamedTextColor.GREEN)))
            } else {
                lore.add(Component.text("No active bounty.", NamedTextColor.DARK_GRAY))
            }
            lore.add(Component.empty())
            lore.add(Component.text("Click to place a bounty.", NamedTextColor.YELLOW))
            meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        }
        return item
    }

    // ── Amount selection ──

    fun openAmountSelect(plugin: Joshymc, player: Player) {
        val session = plugin.teamManager.getBountySession(player.uniqueId)
        val targetUuid = session?.targetUuid
        if (session == null || targetUuid == null) {
            plugin.commsManager.send(player, Component.text("Your bounty session expired — please start again.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            openPlayerSelect(plugin, player, 0)
            return
        }

        val target = Bukkit.getPlayer(targetUuid)
        if (target == null) {
            plugin.commsManager.send(player, Component.text("That player is no longer online.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            openPlayerSelect(plugin, player, session.playerSelectPage)
            return
        }

        session.amount = session.amount.coerceIn(0.0, TeamManager.MAX_BOUNTY_AMOUNT)

        val gui = CustomGui(Component.text("Bounty Amount", NamedTextColor.GOLD), 27)
        for (slot in 0..26) gui.setItem(slot, BountyGuiUtil.filler())

        val display = ItemStack(Material.PLAYER_HEAD)
        display.editMeta { meta ->
            if (meta is SkullMeta) meta.owningPlayer = target
            meta.displayName(Component.text(target.name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
            meta.lore(
                listOf(
                    Component.empty(),
                    Component.text("Bounty Amount: ", NamedTextColor.GRAY).append(Component.text(plugin.economyManager.format(session.amount), NamedTextColor.GREEN)),
                    Component.empty(),
                    Component.text("Use the buttons below to adjust.", NamedTextColor.DARK_GRAY)
                ).map { it.decoration(TextDecoration.ITALIC, false) }
            )
        }
        gui.setItem(13, display)

        for ((delta, slot) in AMOUNT_DELTAS) {
            gui.setItem(
                slot,
                BountyGuiUtil.item(
                    if (delta < 0) Material.RED_DYE else Material.LIME_DYE,
                    Component.text(
                        (if (delta > 0) "+$" else "-$") + plugin.economyManager.formatShort(kotlin.math.abs(delta)),
                        if (delta > 0) NamedTextColor.GREEN else NamedTextColor.RED
                    )
                )
            ) { p, _ ->
                val s = plugin.teamManager.getBountySession(p.uniqueId)
                if (s != null) {
                    val next = s.amount + delta
                    s.amount = if (next.isFinite()) next.coerceIn(0.0, TeamManager.MAX_BOUNTY_AMOUNT) else s.amount
                    openAmountSelect(plugin, p)
                }
            }
        }

        gui.setItem(
            19,
            BountyGuiUtil.item(
                Material.PAPER,
                Component.text("Custom Amount", NamedTextColor.YELLOW),
                listOf(Component.empty(), Component.text("Click to type an amount in chat.", NamedTextColor.GRAY))
            )
        ) { p, _ ->
            plugin.teamManager.beginAwaitingBountyAmountInput(p.uniqueId)
            p.closeInventory()
            plugin.commsManager.send(
                p,
                Component.text("Type the bounty amount in chat (e.g. 100, 10k, 1.5m). Type 'cancel' to abort.", NamedTextColor.YELLOW),
                CommunicationsManager.Category.DEFAULT
            )
        }

        gui.setItem(22, BountyGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.RED))) { p, _ ->
            openPlayerSelect(plugin, p, session.playerSelectPage)
        }

        gui.setItem(25, BountyGuiUtil.item(Material.LIME_STAINED_GLASS_PANE, Component.text("Continue", NamedTextColor.GREEN))) { p, _ ->
            val s = plugin.teamManager.getBountySession(p.uniqueId)
            val amount = s?.amount
            if (s == null || amount == null || !amount.isFinite() || amount <= 0.0) {
                plugin.commsManager.send(p, Component.text("Enter a bounty amount greater than zero first.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return@setItem
            }
            if (!plugin.economyManager.has(p.uniqueId, amount)) {
                plugin.commsManager.send(p, Component.text("You don't have enough money for that amount.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return@setItem
            }
            openConfirm(plugin, p)
        }

        gui.onClose = { p -> if (!plugin.teamManager.isAwaitingBountyAmountInput(p.uniqueId)) plugin.teamManager.clearBountySession(p.uniqueId) }
        plugin.guiManager.open(player, gui)
    }

    // ── Confirmation ──

    private fun openConfirm(plugin: Joshymc, player: Player) {
        val session = plugin.teamManager.getBountySession(player.uniqueId)
        val targetUuid = session?.targetUuid
        if (session == null || targetUuid == null) {
            plugin.commsManager.send(player, Component.text("Your bounty session expired — please start again.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            openPlayerSelect(plugin, player, 0)
            return
        }

        val target = Bukkit.getPlayer(targetUuid)
        if (target == null) {
            plugin.commsManager.send(player, Component.text("That player is no longer online.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            openPlayerSelect(plugin, player, session.playerSelectPage)
            return
        }

        val gui = CustomGui(Component.text("Confirm Bounty", NamedTextColor.GOLD), 27)

        val redGlass = BountyGuiUtil.item(Material.RED_STAINED_GLASS_PANE, Component.text("Cancel", NamedTextColor.RED))
        val greenGlass = BountyGuiUtil.item(Material.LIME_STAINED_GLASS_PANE, Component.text("Confirm Bounty", NamedTextColor.GREEN))

        for (i in 0 until 27) {
            val col = i % 9
            when {
                col < 4 -> gui.setItem(i, redGlass.clone()) { p, _ -> handleCancel(plugin, p) }
                col > 4 -> gui.setItem(i, greenGlass.clone()) { p, _ -> handleConfirm(plugin, p) }
            }
        }

        val display = ItemStack(Material.PLAYER_HEAD)
        display.editMeta { meta ->
            if (meta is SkullMeta) meta.owningPlayer = target
            meta.displayName(Component.text("Target: ${target.name}", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
            meta.lore(
                listOf(
                    Component.empty(),
                    Component.text("Bounty: ", NamedTextColor.GRAY).append(Component.text(plugin.economyManager.format(session.amount), NamedTextColor.GREEN)),
                    Component.empty(),
                    Component.text("Money is withdrawn on confirm.", NamedTextColor.DARK_GRAY)
                ).map { it.decoration(TextDecoration.ITALIC, false) }
            )
        }
        gui.setItem(13, display)

        gui.setItem(18, BountyGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> openAmountSelect(plugin, p) }

        gui.onClose = { p -> if (!plugin.teamManager.isAwaitingBountyAmountInput(p.uniqueId)) plugin.teamManager.clearBountySession(p.uniqueId) }
        plugin.guiManager.open(player, gui)
    }

    private fun handleCancel(plugin: Joshymc, player: Player) {
        plugin.teamManager.clearBountySession(player.uniqueId)
        plugin.commsManager.send(player, Component.text("Bounty placement cancelled.", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
        BountyMainGui.open(plugin, player)
    }

    private fun handleConfirm(plugin: Joshymc, player: Player) {
        val session = plugin.teamManager.getBountySession(player.uniqueId)
        val targetUuid = session?.targetUuid
        val amount = session?.amount
        if (session == null || targetUuid == null || amount == null) {
            plugin.commsManager.send(player, Component.text("Your bounty session expired — please start again.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            plugin.teamManager.clearBountySession(player.uniqueId)
            BountyMainGui.open(plugin, player)
            return
        }

        val target = Bukkit.getPlayer(targetUuid)
        if (target == null || target == player) {
            plugin.commsManager.send(player, Component.text("That player is no longer a valid target.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            plugin.teamManager.clearBountySession(player.uniqueId)
            BountyMainGui.open(plugin, player)
            return
        }

        if (!amount.isFinite() || amount <= 0.0 || amount > TeamManager.MAX_BOUNTY_AMOUNT) {
            plugin.commsManager.send(player, Component.text("Invalid bounty amount.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            plugin.teamManager.clearBountySession(player.uniqueId)
            BountyMainGui.open(plugin, player)
            return
        }

        if (!plugin.economyManager.has(player.uniqueId, amount)) {
            plugin.commsManager.send(player, Component.text("You don't have enough money.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            plugin.teamManager.clearBountySession(player.uniqueId)
            BountyMainGui.open(plugin, player)
            return
        }

        plugin.teamManager.clearBountySession(player.uniqueId)

        if (plugin.teamManager.placeBounty(player.uniqueId, player.name, target.uniqueId, target.name, amount)) {
            player.closeInventory()
            plugin.teamManager.announceBountyPlaced(player, target, amount)
        } else {
            plugin.commsManager.send(player, Component.text("Could not place bounty. Insufficient funds.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            BountyMainGui.open(plugin, player)
        }
    }
}
