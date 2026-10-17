package com.liam.joshymc.communityevent.game

import com.liam.joshymc.communityevent.EventPermissions
import com.liam.joshymc.communityevent.EventResult
import com.liam.joshymc.communityevent.EventState
import com.liam.joshymc.communityevent.EventType
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

/**
 * "Game Controls" in the Event Manager (issue #1187): Spleef and Sumo controls that follow
 * the game's state. Every click re-checks [EventPermissions.canHost] and the game state, so a
 * stale menu can't start two matches or reset a floor mid-round.
 */
class GameMenu(private val games: HostedGames, private val back: (Player) -> Unit) {

    private val plugin get() = games.plugin
    private val category = CommunicationsManager.Category.EVENT

    companion object {
        private const val SIZE = 45
        private const val SLOT_STATUS = 4
        private const val SLOT_PRIMARY = 20
        private const val SLOT_SECOND = 21
        private const val SLOT_THIRD = 22
        private const val SLOT_FOURTH = 23
        private const val SLOT_END = 24
        private const val LIST_SIZE = 54
        private const val PER_PAGE = 45
    }

    fun open(player: Player) {
        if (!guard(player)) return
        val session = games.sessions.active
        val type = session?.type
        if (session == null || (type != EventType.SPLEEF && type != EventType.SUMO)) {
            Msg.error(player, "Game controls are for Spleef and Sumo events. Create one with /event create spleef|sumo.", category)
            return
        }
        val gui = CustomGui(Component.text("${type.displayName} Controls", NamedTextColor.DARK_GRAY), SIZE)
        gui.border(GuiStyle.filler())
        val game = games.gameFor(type)!!
        val running = session.state == EventState.RUNNING && game.isRunning
        val lore = if (running) game.statusLines() else listOf(Component.text("The event is ${session.state.label.lowercase()}. /event start begins it.", NamedTextColor.GRAY))
        gui.setItem(SLOT_STATUS, GuiStyle.item(type.icon, Component.text("${type.displayName} Status", NamedTextColor.GOLD), lore))

        if (type == EventType.SPLEEF) spleefButtons(gui, running) else sumoButtons(gui, running)

        button(gui, SLOT_END, Material.RED_CONCRETE, "End Event", "Stops ${type.displayName} for everyone. Asks to confirm.", null) { p ->
            val target = games.sessions.active ?: return@button
            confirm(p, "End ${target.type.displayName}?", listOf("Ends the event now.", "Nobody is awarded anything.")) {
                if (games.sessions.active?.id != target.id) EventResult.Failure("That event changed while this menu was open. Nothing was done.")
                else games.sessions.stop()
            }
        }

        gui.setItem(GuiStyle.backSlot(SIZE), GuiStyle.back("Back to Event Manager")) { p, _ -> GuiStyle.clickSound(p); back(p) }
        gui.setItem(GuiStyle.closeSlot(SIZE), GuiStyle.close()) { p, _ -> p.closeInventory() }
        gui.fill(GuiStyle.filler())
        plugin.guiManager.open(player, gui)
    }

    private fun spleefButtons(gui: CustomGui, running: Boolean) {
        val game = games.spleef
        val state = game.round.state
        button(
            gui, SLOT_PRIMARY, Material.DIAMOND_SHOVEL, "Start Round", "Captures the floor and starts the next round.",
            when {
                !running -> "The event isn't running."
                state.inProgress -> "A round is in progress."
                state == RoundState.COMPLETE -> "Spleef is complete."
                else -> game.currentArena()?.let { a ->
                    when {
                        games.floors.isRestoring(a.id) -> "The floor is being restored."
                        games.floors.isDamaged(a.id) -> "Reset the floor first."
                        else -> null
                    }
                }
            },
        ) { p -> report(p, games.startRoundCommand()); open(p) }

        val arena = game.currentArena() ?: games.arenas.selectedFor(games.sessions.active)
        button(
            gui, SLOT_SECOND, Material.SNOW_BLOCK, "Reset Floor", "Restores the saved floor blocks. Asks to confirm.",
            when {
                arena == null -> "No arena is selected."
                running && state.inProgress -> "Not while a round is in progress."
                games.floors.isRestoring(arena.id) -> "Already restoring."
                !games.floors.isDamaged(arena.id) -> "The floor is intact."
                else -> null
            },
        ) { p ->
            confirm(p, "Reset the Spleef floor?", listOf("Restores the saved floor blocks.", "Anyone on the floor is moved off.")) {
                games.spleef.resetFloor(null, p) ?: EventResult.Success(games.sessions.active ?: return@confirm EventResult.Failure("There is no event."), "Restoring the floor.")
            }
        }

        listButton(gui, SLOT_THIRD, Material.PLAYER_HEAD, "Remaining Players",
            if (state.inProgress) "Still standing this round." else "Registered players.") { game.remaining() }
        listButton(gui, SLOT_FOURTH, Material.SKELETON_SKULL, "Eliminated Players", "Out of the current round, in order.") { game.eliminatedThisRound() }
    }

    private fun sumoButtons(gui: CustomGui, running: Boolean) {
        val game = games.sumo
        val state = game.round.state
        button(
            gui, SLOT_PRIMARY, Material.LIME_CONCRETE, "Start Next Match", "Teleports the next pair and starts the countdown.",
            when {
                !running -> "The event isn't running."
                state.inProgress -> "A match is in progress."
                state == RoundState.COMPLETE -> "The tournament is over."
                else -> null
            },
        ) { p -> report(p, games.nextMatchCommand()); open(p) }

        val match = game.currentMatch()
        gui.setItem(
            SLOT_SECOND,
            GuiStyle.item(
                Material.SLIME_BALL, Component.text("Current Match", NamedTextColor.GOLD),
                if (match != null) listOf(
                    Component.text("${game.nameOf(match.first)} vs ${game.nameOf(match.second)}", NamedTextColor.WHITE),
                    Component.text("State: ${state.label}", NamedTextColor.GRAY),
                ) else listOf(Component.text("No match in progress.", NamedTextColor.GRAY)),
            ),
        )
        listButton(gui, SLOT_THIRD, Material.PAPER, "Tournament Queue", "Waiting to play this bracket round, in order.") { game.bracket?.queue ?: emptyList() }
        listButton(gui, SLOT_FOURTH, Material.PLAYER_HEAD, "Remaining Competitors", "Everyone still in the tournament.") { game.bracket?.remaining ?: emptyList() }
    }

    // ── Player lists ──────────────────────────────────────

    private fun listButton(gui: CustomGui, slot: Int, material: Material, name: String, description: String, players: () -> List<UUID>) {
        val count = players().size
        gui.setItem(slot, GuiStyle.item(material, Component.text(name, NamedTextColor.GREEN), listOf(
            Component.text(description, NamedTextColor.GRAY),
            Component.text("$count player(s)", NamedTextColor.WHITE),
            Component.empty(),
            Component.text("Click to view", NamedTextColor.YELLOW),
        ))) { p, _ ->
            if (!guard(p)) return@setItem
            GuiStyle.clickSound(p)
            openList(p, name, players, 0)
        }
    }

    private fun openList(player: Player, title: String, players: () -> List<UUID>, page: Int) {
        if (!guard(player)) return
        val all = players()
        val game = games.sessions.active?.type?.let { games.gameFor(it) }
        val pages = ((all.size + PER_PAGE - 1) / PER_PAGE).coerceAtLeast(1)
        val current = page.coerceIn(0, pages - 1)
        val gui = CustomGui(Component.text(title, NamedTextColor.DARK_GRAY), LIST_SIZE)
        all.drop(current * PER_PAGE).take(PER_PAGE).forEachIndexed { i, uuid ->
            val name = game?.nameOf(uuid) ?: Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString().take(8)
            val online = Bukkit.getPlayer(uuid) != null
            gui.setItem(i, GuiStyle.skull(
                Bukkit.getOfflinePlayer(uuid), Component.text(name, NamedTextColor.WHITE),
                listOf(Component.text(if (online) "Online" else "Offline", if (online) NamedTextColor.GREEN else NamedTextColor.RED)),
            ))
        }
        if (all.isEmpty()) gui.setItem(22, GuiStyle.item(Material.BARRIER, Component.text("Nobody here", NamedTextColor.GRAY)))
        if (current > 0) gui.setItem(GuiStyle.prevSlot(LIST_SIZE), GuiStyle.previousPage(current + 1, pages)) { p, _ -> GuiStyle.pageSound(p); openList(p, title, players, current - 1) }
        if (current < pages - 1) gui.setItem(GuiStyle.nextSlot(LIST_SIZE), GuiStyle.nextPage(current + 1, pages)) { p, _ -> GuiStyle.pageSound(p); openList(p, title, players, current + 1) }
        gui.setItem(GuiStyle.backSlot(LIST_SIZE), GuiStyle.back("Back to Game Controls")) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.setItem(GuiStyle.closeSlot(LIST_SIZE), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Helpers ───────────────────────────────────────────

    private fun button(gui: CustomGui, slot: Int, material: Material, name: String, description: String, unavailable: String?, onUse: (Player) -> Unit) {
        val item = if (unavailable == null) {
            GuiStyle.item(material, Component.text(name, NamedTextColor.GREEN), listOf(
                Component.text(description, NamedTextColor.GRAY), Component.empty(), Component.text("Click to use", NamedTextColor.YELLOW),
            ))
        } else {
            GuiStyle.item(material, Component.text(name, NamedTextColor.DARK_GRAY).decoration(TextDecoration.STRIKETHROUGH, true), listOf(
                Component.text(description, NamedTextColor.GRAY), Component.empty(), GuiStyle.error("Unavailable: $unavailable"),
            ))
        }
        // Disabled buttons still route through the action, which re-validates and explains why.
        gui.setItem(slot, item) { p, _ ->
            if (!guard(p)) return@setItem
            GuiStyle.clickSound(p)
            onUse(p)
        }
    }

    private fun confirm(player: Player, question: String, details: List<String>, action: () -> EventResult) {
        if (!guard(player)) return
        val gui = CustomGui(Component.text("Confirm", NamedTextColor.DARK_GRAY), 27)
        gui.setItem(13, GuiStyle.item(Material.PAPER, Component.text(question, NamedTextColor.YELLOW), details.map { Component.text(it, NamedTextColor.GRAY) }))
        gui.setItem(11, GuiStyle.item(Material.LIME_CONCRETE, Component.text("Confirm", NamedTextColor.GREEN))) { p, _ ->
            if (!guard(p)) return@setItem
            report(p, action())
            if (games.sessions.active != null) open(p) else back(p)
        }
        gui.setItem(15, GuiStyle.item(Material.RED_CONCRETE, Component.text("Cancel", NamedTextColor.RED))) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.fill(GuiStyle.filler())
        plugin.guiManager.open(player, gui)
    }

    private fun report(player: Player, result: EventResult) {
        when (result) {
            is EventResult.Success -> { GuiStyle.successSound(player); Msg.success(player, result.message, category) }
            else -> { GuiStyle.errorSound(player); Msg.error(player, result.message, category) }
        }
    }

    private fun guard(player: Player): Boolean {
        if (EventPermissions.canHost(player)) return true
        player.closeInventory()
        Msg.noPermission(player, category)
        return false
    }
}
