package com.liam.joshymc.lifesteal.bounty

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.lifesteal.bounty.BountyMenuLayout as L
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The Lifesteal bounty menus (issue #1401): the paginated active list, the contribution amount
 * and confirmation screens, and the two hunter leaderboards. Built on the shared [CustomGui] /
 * [GuiStyle], so GuiManager cancels every click on menu slots (items can't be taken, moved or
 * duplicated) and rate-limits clicks.
 *
 * Menus never move coins themselves: confirming calls [LifestealBountyManager.contribute], the
 * same path as `/bounty add`, with the bounty id the player saw, so a bounty that was claimed or
 * expired while the menu was open is refused as stale instead of silently starting a new one.
 * No screen shows who contributed or how much anyone else gave.
 */
class LifestealBountyMenu(private val plugin: Joshymc, private val manager: LifestealBountyManager) {

    private class ListView(val gui: CustomGui, var page: Int)
    private val lists = ConcurrentHashMap<UUID, ListView>()

    /** Chat prompts (AsyncChatEvent arrives off the main thread). */
    sealed interface Input {
        val at: Long
        data class TargetName(override val at: Long) : Input
        data class Amount(val target: UUID, val name: String, val expectedBountyId: String?, override val at: Long) : Input
    }
    private val inputs = ConcurrentHashMap<UUID, Input>()

    private val settings: BountySettings get() = manager.settings ?: BountySettings.DEFAULTS

    private fun title(legacy: String): Component = plugin.commsManager.parseLegacy(legacy)

    private fun text(s: String, color: NamedTextColor = NamedTextColor.GRAY): Component = Component.text(s, color)

    private fun nameOf(uuid: UUID, stored: String): String =
        Bukkit.getPlayer(uuid)?.name ?: plugin.playerResolver.lastKnownName(uuid) ?: stored

    private fun refuseUnavailable(player: Player): Boolean {
        val reason = manager.unavailableReason(player) ?: return false
        manager.send(player, "&c$reason")
        return true
    }

    // ── Active bounty list ──────────────────────────────────

    fun openList(player: Player, page: Int = 0) {
        if (refuseUnavailable(player)) return
        val gui = CustomGui(title(settings.gui.listTitle), L.LIST_SIZE)
        val view = ListView(gui, page)
        render(player, view)
        lists[player.uniqueId] = view
        gui.onClose = { p -> lists.remove(p.uniqueId, view) }
        plugin.guiManager.open(player, gui)
    }

    /** Redraws every open list in place after a change (contribution, claim, expiration). */
    fun refreshOpenLists() {
        for ((uuid, view) in lists) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || plugin.guiManager.getOpenGui(player) !== view.gui) {
                lists.remove(uuid, view)
                continue
            }
            render(player, view)
        }
    }

    private fun render(player: Player, view: ListView) {
        val gui = view.gui
        gui.inventory.clear()
        gui.clickHandlers.clear()
        val now = System.currentTimeMillis()
        val all = manager.activeBounties.filter { it.expiresAt > now }.sortedWith(BountyRules.ORDER)
        val pages = BountyRules.pageCount(all.size, L.PER_PAGE)
        view.page = BountyRules.clampPage(view.page, all.size, L.PER_PAGE)
        val page = view.page

        if (all.isEmpty()) {
            gui.setItem(L.EMPTY_SLOT, GuiStyle.item(Material.PAPER, text("No active bounties", NamedTextColor.YELLOW), listOf(
                text("Nobody has a bounty right now."),
                text("Use \"Place a Bounty\" below to start one."),
            )))
        }
        BountyRules.page(all, page, L.PER_PAGE).forEachIndexed { index, bounty ->
            val name = nameOf(bounty.target, bounty.targetName)
            val own = bounty.target == player.uniqueId
            val lore = mutableListOf(
                text("Bounty: ", NamedTextColor.GRAY).append(text(BountyMoney.format(bounty.totalCents), NamedTextColor.GOLD)),
                text("Expires in: ", NamedTextColor.GRAY).append(text(BountyRules.formatRemaining(bounty.expiresAt - now), NamedTextColor.WHITE)),
                text(if (Bukkit.getPlayer(bounty.target) != null) "Online" else "Offline", if (Bukkit.getPlayer(bounty.target) != null) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY),
                Component.empty(),
                if (own) text("This bounty is on you.", NamedTextColor.RED) else text("Click to contribute", NamedTextColor.YELLOW),
            )
            val head = GuiStyle.skull(Bukkit.getOfflinePlayer(bounty.target), text(name, NamedTextColor.RED), lore)
            gui.setItem(index, head) { p, _ ->
                val current = manager.activeFor(bounty.target)
                when {
                    current == null || current.id != bounty.id -> {
                        GuiStyle.errorSound(p)
                        manager.send(p, "&cThat bounty has ended. The list has been refreshed.")
                        render(p, view)
                    }
                    current.target == p.uniqueId -> {
                        GuiStyle.errorSound(p)
                        manager.send(p, "&c${BountyStore.Refusal.SELF.message}")
                    }
                    else -> {
                        GuiStyle.clickSound(p)
                        openContribute(p, current.target, name, current.id)
                    }
                }
            }
        }

        if (page > 0) gui.setItem(L.PREV_SLOT, GuiStyle.previousPage(page, pages)) { p, _ ->
            GuiStyle.pageSound(p); view.page = page - 1; render(p, view)
        }
        if (page < pages - 1) gui.setItem(L.NEXT_SLOT, GuiStyle.nextPage(page, pages)) { p, _ ->
            GuiStyle.pageSound(p); view.page = page + 1; render(p, view)
        }
        gui.setItem(L.CLOSE_SLOT, GuiStyle.close()) { p, _ -> p.closeInventory() }
        gui.setItem(L.LEADERBOARDS_SLOT, GuiStyle.item(Material.GOLD_INGOT, text("Bounty Hunter Leaderboards", NamedTextColor.GOLD), listOf(
            text("Richest and most successful hunters."), text("Click to view", NamedTextColor.YELLOW),
        ))) { p, _ -> GuiStyle.clickSound(p); openLeaderboard(p, BountyStore.Board.EARNED, 0) }
        gui.setItem(L.STATS_SLOT, GuiStyle.item(Material.BOOK, text("Your Bounty Stats", NamedTextColor.AQUA), listOf(
            text("Your claims, earnings and contributions."), text("Click to view in chat", NamedTextColor.YELLOW),
        ))) { p, _ -> GuiStyle.clickSound(p); p.closeInventory(); LifestealBountyCommand.sendStats(plugin, manager, p) }
        gui.setItem(L.PLACE_SLOT, GuiStyle.item(Material.NAME_TAG, text("Place a Bounty", NamedTextColor.GREEN), listOf(
            text("Put a bounty on any Lifesteal player,"), text("or add to an existing one."),
            text("Minimum: ${BountyMoney.format(settings.minimumContributionCents)}"),
            text("Click, then type the player's name", NamedTextColor.YELLOW),
        ))) { p, _ -> GuiStyle.clickSound(p); askTargetName(p) }
        gui.setItem(L.INFO_SLOT, GuiStyle.item(Material.PAPER, text("How Bounties Work", NamedTextColor.YELLOW), listOf(
            text("Contributions stack and are anonymous."),
            text("Minimum contribution: ${BountyMoney.format(settings.minimumContributionCents)}"),
            text("Bounties expire ${settings.expirationMillis / 86_400_000L} days after they're created;"),
            text("then every contributor is refunded."),
            text("Kill the target in normal Lifesteal PvP"),
            text("to claim 100% of the bounty."),
            text("No claims in Ranked, events or on teammates."),
            text("Contributions can't be cancelled.", NamedTextColor.RED),
        )))
    }

    // ── Contribution ────────────────────────────────────────

    /** Amount buttons for [target]; [expectedBountyId] null = a new bounty (or one placed meanwhile). */
    fun openContribute(player: Player, target: UUID, name: String, expectedBountyId: String?) {
        if (refuseUnavailable(player)) return
        val s = settings
        val gui = CustomGui(title(s.gui.contributeTitle), L.CONTRIBUTE_SIZE)
        val current = manager.activeFor(target)
        val headLore = if (current != null) listOf(
            text("Current bounty: ", NamedTextColor.GRAY).append(text(BountyMoney.format(current.totalCents), NamedTextColor.GOLD)),
            text("Expires in: ", NamedTextColor.GRAY).append(text(BountyRules.formatRemaining(current.expiresAt - System.currentTimeMillis()), NamedTextColor.WHITE)),
        ) else listOf(text("No bounty yet: yours will create it."), text("It expires ${s.expirationMillis / 86_400_000L} days after creation."))
        gui.setItem(L.TARGET_SLOT, GuiStyle.skull(Bukkit.getOfflinePlayer(target), text(name, NamedTextColor.RED), headLore))
        val presets = L.presets(s.gui.presetCents, s.minimumContributionCents)
        val expected = expectedBountyId ?: current?.id
        L.presetSlots(presets.size).zip(presets).forEach { (slot, cents) ->
            gui.setItem(slot, GuiStyle.item(Material.GOLD_NUGGET, text(BountyMoney.format(cents), NamedTextColor.GOLD), listOf(text("Click to review", NamedTextColor.YELLOW)))) { p, _ ->
                GuiStyle.clickSound(p)
                openConfirm(p, target, name, expected, cents)
            }
        }
        gui.setItem(L.CUSTOM_SLOT, GuiStyle.item(Material.OAK_SIGN, text("Custom Amount", NamedTextColor.GREEN), listOf(
            text("Type any amount in chat"), text("(minimum ${BountyMoney.format(s.minimumContributionCents)}, e.g. 75k or 1.5m)."),
        ))) { p, _ ->
            GuiStyle.clickSound(p)
            inputs[p.uniqueId] = Input.Amount(target, name, expected, System.currentTimeMillis())
            p.closeInventory()
            manager.send(p, "&eType the amount to contribute to the bounty on &c$name &ein chat, or &fcancel&e.")
        }
        gui.setItem(L.CONTRIBUTE_BACK_SLOT, GuiStyle.back("Back to Bounties")) { p, _ -> openList(p) }
        gui.setItem(L.CONTRIBUTE_CLOSE_SLOT, GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    /** Shows the amount and the player's balance before anything is taken. */
    fun openConfirm(player: Player, target: UUID, name: String, expectedBountyId: String?, cents: Long) {
        if (refuseUnavailable(player)) return
        BountyRules.amountProblem(cents, settings.minimumContributionCents)?.let { manager.send(player, "&c$it"); return }
        val uuid = player.uniqueId
        manager.read("balance $uuid", { plugin.economyManager.getBalance(uuid) }) { balance ->
            val p = Bukkit.getPlayer(uuid) ?: return@read
            renderConfirm(p, target, name, expectedBountyId, cents, balance)
        }
    }

    private fun renderConfirm(player: Player, target: UUID, name: String, expectedBountyId: String?, cents: Long, balance: Double?) {
        val gui = CustomGui(title(settings.gui.confirmTitle), L.CONFIRM_SIZE)
        // One transaction id per confirmation screen: a double click is recorded once.
        val transactionId = UUID.randomUUID().toString()
        var used = false
        val enough = balance != null && balance >= BountyMoney.toCoins(cents)
        gui.setItem(L.SUMMARY_SLOT, GuiStyle.skull(Bukkit.getOfflinePlayer(target), text("Bounty on $name", NamedTextColor.RED), listOfNotNull(
            text("Contribution: ", NamedTextColor.GRAY).append(text(BountyMoney.format(cents), NamedTextColor.GOLD)),
            balance?.let { text("Your balance: ", NamedTextColor.GRAY).append(text(plugin.economyManager.format(it), if (enough) NamedTextColor.GREEN else NamedTextColor.RED)) },
            Component.empty(),
            text("Contributions are anonymous and", NamedTextColor.GRAY),
            text("can't be cancelled or withdrawn.", NamedTextColor.RED),
        )))
        gui.setItem(L.CONFIRM_SLOT, GuiStyle.item(Material.LIME_DYE, text("Confirm ${BountyMoney.format(cents)}", NamedTextColor.GREEN), listOf(
            text("Takes ${BountyMoney.format(cents)} from your balance."),
        ))) { p, _ ->
            if (used) return@setItem
            used = true
            p.closeInventory()
            manager.contribute(p, target, name, cents, transactionId, expectedBountyId, "gui") { outcome ->
                val done = (outcome as? LifestealBountyManager.ContributeOutcome.Done)?.result
                val online = Bukkit.getPlayer(p.uniqueId) ?: return@contribute
                if (done is BountyStore.ContributeResult.Contributed) { GuiStyle.successSound(online); openList(online) }
                else GuiStyle.errorSound(online)
            }
        }
        gui.setItem(L.CANCEL_SLOT, GuiStyle.item(Material.RED_DYE, text("Cancel", NamedTextColor.RED), listOf(text("Back to the amounts")))) { p, _ ->
            GuiStyle.clickSound(p)
            openContribute(p, target, name, expectedBountyId)
        }
        gui.setItem(L.CONFIRM_CLOSE_SLOT, GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Chat input ──────────────────────────────────────────

    private fun askTargetName(player: Player) {
        inputs[player.uniqueId] = Input.TargetName(System.currentTimeMillis())
        player.closeInventory()
        manager.send(player, "&eType the exact name of the player to place a bounty on, or &fcancel&e.")
    }

    fun isAwaiting(uuid: UUID): Boolean {
        val input = inputs[uuid] ?: return false
        if (System.currentTimeMillis() - input.at > INPUT_TIMEOUT_MILLIS) {
            inputs.remove(uuid, input)
            return false
        }
        return true
    }

    /** Main thread, from the chat listener. */
    fun handleInput(player: Player, raw: String) {
        val input = inputs.remove(player.uniqueId) ?: return
        val text = raw.trim()
        if (text.equals("cancel", ignoreCase = true)) {
            manager.send(player, "&7Cancelled.")
            return
        }
        if (refuseUnavailable(player)) return
        when (input) {
            is Input.TargetName -> {
                // Exact name only (case-insensitive), never a prefix match: online, Paper's cache, then JoshyMC's name table.
                val resolved = if (text.matches(NAME)) plugin.playerResolver.resolve(text) else null
                if (resolved == null) {
                    manager.send(player, "&cNo player called &f$text &chas played here. Check the spelling and try again.")
                    return
                }
                val uuid = resolved.uniqueId
                if (uuid == player.uniqueId) {
                    manager.send(player, "&c${BountyStore.Refusal.SELF.message}")
                    return
                }
                openContribute(player, uuid, resolved.name ?: text, manager.activeFor(uuid)?.id)
            }
            is Input.Amount -> {
                val parsed = BountyMoney.parse(text)
                BountyRules.parseProblem(parsed, settings.minimumContributionCents)?.let {
                    manager.send(player, "&c$it")
                    inputs[player.uniqueId] = input.copy(at = System.currentTimeMillis())
                    manager.send(player, "&eType another amount, or &fcancel&e.")
                    return
                }
                openConfirm(player, input.target, input.name, input.expectedBountyId, (parsed as BountyMoney.Parsed.Ok).cents)
            }
        }
    }

    // ── Leaderboards ────────────────────────────────────────

    fun openLeaderboard(player: Player, board: BountyStore.Board, page: Int) {
        if (!manager.processing) { manager.send(player, "&c${manager.unavailableReason(player) ?: "Bounties are unavailable."}"); return }
        val uuid = player.uniqueId
        manager.loadBoard(board, page, L.PER_PAGE) { loaded ->
            val p = Bukkit.getPlayer(uuid) ?: return@loadBoard
            if (loaded == null) { manager.send(p, "&cThe leaderboard couldn't be loaded. Please try again."); return@loadBoard }
            renderBoard(p, loaded)
        }
    }

    private fun renderBoard(player: Player, data: LifestealBountyManager.BoardPage) {
        val gui = CustomGui(title(settings.gui.leaderboardTitle), L.LIST_SIZE)
        if (data.entries.isEmpty()) {
            gui.setItem(L.EMPTY_SLOT, GuiStyle.item(Material.PAPER, text("No bounty hunters yet", NamedTextColor.YELLOW), listOf(text("Claim a bounty to be the first!"))))
        }
        data.entries.forEachIndexed { index, entry ->
            val name = nameOf(entry.uuid, entry.uuid.toString().take(8))
            gui.setItem(index, GuiStyle.skull(Bukkit.getOfflinePlayer(entry.uuid), text("#${entry.position} $name", NamedTextColor.GOLD), listOf(
                text("Coins earned: ", NamedTextColor.GRAY).append(text(BountyMoney.format(entry.earnedCents), NamedTextColor.GOLD)),
                text("Bounties claimed: ", NamedTextColor.GRAY).append(text(entry.claims.toString(), NamedTextColor.WHITE)),
            )))
        }
        fun selector(board: BountyStore.Board, material: Material, label: String, lore: String): ItemStack {
            val selected = data.board == board
            return GuiStyle.item(material, text(label, if (selected) NamedTextColor.GREEN else NamedTextColor.YELLOW), listOf(
                text(lore), if (selected) text("Showing", NamedTextColor.GREEN) else text("Click to show", NamedTextColor.YELLOW),
            ))
        }
        gui.setItem(L.BOARD_EARNED_SLOT, selector(BountyStore.Board.EARNED, Material.GOLD_INGOT, "Richest Bounty Hunters", "Ranked by coins earned from bounties.")) { p, _ ->
            if (data.board != BountyStore.Board.EARNED) { GuiStyle.clickSound(p); openLeaderboard(p, BountyStore.Board.EARNED, 0) }
        }
        gui.setItem(L.BOARD_CLAIMS_SLOT, selector(BountyStore.Board.CLAIMS, Material.IRON_SWORD, "Most Successful Bounty Hunters", "Ranked by bounties claimed.")) { p, _ ->
            if (data.board != BountyStore.Board.CLAIMS) { GuiStyle.clickSound(p); openLeaderboard(p, BountyStore.Board.CLAIMS, 0) }
        }
        if (data.page > 0) gui.setItem(L.PREV_SLOT, GuiStyle.previousPage(data.page, data.pages)) { p, _ -> GuiStyle.pageSound(p); openLeaderboard(p, data.board, data.page - 1) }
        if (data.page < data.pages - 1) gui.setItem(L.NEXT_SLOT, GuiStyle.nextPage(data.page, data.pages)) { p, _ -> GuiStyle.pageSound(p); openLeaderboard(p, data.board, data.page + 1) }
        gui.setItem(L.BOARD_BACK_SLOT, GuiStyle.back("Back to Bounties")) { p, _ -> openList(p) }
        gui.setItem(L.CLOSE_SLOT, GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Cleanup ─────────────────────────────────────────────

    fun forget(uuid: UUID) {
        lists.remove(uuid)
        inputs.remove(uuid)
    }

    fun clear() {
        lists.clear()
        inputs.clear()
    }

    companion object {
        const val INPUT_TIMEOUT_MILLIS = 60_000L
        /** Java names, plus Floodgate's prefixed Bedrock names (e.g. `.Steve`). */
        private val NAME = Regex("^[.*]?[A-Za-z0-9_]{1,16}$")
    }
}
