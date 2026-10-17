package com.liam.joshymc.communityevent

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.arena.ArenaCommands
import com.liam.joshymc.communityevent.arena.ArenaMenu
import com.liam.joshymc.communityevent.arena.EventArenas
import com.liam.joshymc.communityevent.game.GameMenu
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
import java.time.Duration
import java.util.UUID

/**
 * The staff "Event Manager" GUI opened by `/event` (issue #1180).
 *
 * Every click re-checks [EventPermissions.HOST] and the session state, so a stale menu or a
 * player who lost the permission can't act. Open main menus are re-rendered in place (no
 * reopen) whenever the session changes.
 */
class EventManagerMenu(
    private val plugin: Joshymc,
    private val sessions: EventSessions,
    private val arenas: EventArenas,
    private val settingsLines: () -> List<Component>,
) {

    /** The Arena Setup section (issue #1183); set by EventManager once the arena commands exist. */
    lateinit var arenaMenu: ArenaMenu

    /** Spleef/Sumo game controls (issue #1187); set by EventManager. */
    lateinit var gameMenu: GameMenu

    private val comms get() = plugin.commsManager

    /** Main menus currently open, so lifecycle changes can refresh them. */
    private val mainMenus = HashMap<UUID, CustomGui>()

    companion object {
        private const val MAIN_SIZE = 45
        private const val SLOT_STATUS = 4
        private const val SLOT_CREATE = 19
        private const val SLOT_OPEN = 20
        private const val SLOT_START = 21
        private const val SLOT_GAME = 22
        private const val SLOT_STOP = 23
        private const val SLOT_RESET = 24
        private const val SLOT_SETTINGS = 25
        private const val SLOT_ARENAS = 31

        private const val SUB_SIZE = 27
        private val TYPE_SLOTS = intArrayOf(11, 12, 13, 14, 15)
    }

    // ── Main menu ─────────────────────────────────────────

    fun open(player: Player) {
        if (!EventPermissions.canHost(player)) { Msg.noPermission(player); return }
        val gui = CustomGui(title("Event Manager"), MAIN_SIZE)
        renderMain(gui)
        gui.onClose = { p -> if (mainMenus[p.uniqueId] === gui) mainMenus.remove(p.uniqueId) }
        plugin.guiManager.open(player, gui)
        mainMenus[player.uniqueId] = gui
    }

    /** Re-renders every open main menu in place after a session change. */
    fun refreshOpenMenus() {
        val iterator = mainMenus.entries.iterator()
        while (iterator.hasNext()) {
            val (uuid, gui) = iterator.next()
            val viewer = Bukkit.getPlayer(uuid)
            if (viewer == null || plugin.guiManager.getOpenGui(viewer) !== gui) { iterator.remove(); continue }
            renderMain(gui)
        }
    }

    private fun renderMain(gui: CustomGui) {
        val session = sessions.current
        val active = sessions.active
        val state = active?.state

        gui.inventory.clear()
        gui.clickHandlers.clear()
        gui.border(GuiStyle.filler())

        gui.setItem(SLOT_STATUS, statusItem(session))

        actionButton(
            gui, SLOT_CREATE, Material.NETHER_STAR, "Create Event",
            "Pick one of the five event types.",
            unavailable = active?.let { "A ${it.type.displayName} event is already active." },
        ) { p -> openCreate(p) }

        actionButton(
            gui, SLOT_OPEN, Material.WRITABLE_BOOK, "Open Registration",
            "Announce the event and let players /event join.",
            unavailable = when (state) {
                null -> "Create an event first."
                EventState.CREATED -> null
                EventState.OPEN -> "Registration is already open."
                else -> "The event is already running."
            },
        ) { p -> act(p) { sessions.open() } }

        actionButton(
            gui, SLOT_START, Material.LIME_CONCRETE, "Start Event",
            "Close registration and start the event.",
            unavailable = when (state) {
                null -> "Create an event first."
                EventState.CREATED -> "Open registration first."
                EventState.OPEN -> if ((active?.participantCount ?: 0) == 0) "Nobody has registered yet."
                    else sessions.startCheck?.invoke(active)
                else -> "The event is already running."
            },
        ) { p -> act(p) { sessions.start() } }

        actionButton(
            gui, SLOT_STOP, Material.RED_CONCRETE, "Stop Event",
            "End the event for everyone. Asks to confirm.",
            unavailable = if (active == null) "No event is active." else null,
        ) { p ->
            val target = sessions.active ?: run { act(p) { sessions.stop() }; return@actionButton }
            openConfirm(
                p, "Stop ${target.type.displayName}?",
                listOf(
                    "Ends the event and closes registration.",
                    "Registered players: ${target.participantCount}",
                ),
                sessionId = target.id,
            ) { sessions.stop() }
        }

        actionButton(
            gui, SLOT_RESET, Material.CLOCK, "Reset Event",
            "Back to Created: clears registrations and tasks. No blocks are touched.",
            unavailable = when {
                session == null -> "There is no event to reset."
                session.state == EventState.RUNNING -> "Stop the event before resetting."
                else -> null
            },
        ) { p ->
            val target = sessions.current
            if (target != null && target.state != EventState.RUNNING && sessions.resetIsDisruptive()) {
                openConfirm(
                    p, "Reset ${target.type.displayName}?",
                    listOf(
                        "Clears ${target.participantCount} registration(s).",
                        "Cancels the event's active tasks.",
                    ),
                    sessionId = target.id,
                ) { sessions.reset(confirmed = true) }
            } else {
                act(p) { sessions.reset(confirmed = false) }
            }
        }

        val gameType = active?.type
        actionButton(
            gui, SLOT_GAME, gameType?.icon ?: Material.DIAMOND_SHOVEL, "Game Controls",
            "Rounds, matches, crossings, brackets, waves and arena resets.",
            unavailable = if (gameType == null) "Create an event first." else null,
        ) { p -> gameMenu.open(p) }

        gui.setItem(
            SLOT_SETTINGS,
            GuiStyle.item(
                Material.COMPARATOR, Component.text("Event Settings", NamedTextColor.GOLD),
                listOf(Component.text("View the event type and world settings.", NamedTextColor.GRAY)),
            ),
        ) { p, _ -> GuiStyle.clickSound(p); openSettings(p) }

        gui.setItem(
            SLOT_ARENAS,
            GuiStyle.item(
                Material.MAP, Component.text("Arena Setup", NamedTextColor.GOLD),
                listOf(
                    Component.text("Saved arenas: create, configure, select", NamedTextColor.GRAY),
                    Component.text("and check what's missing.", NamedTextColor.GRAY),
                    Component.empty(),
                    Component.text("Click to open", NamedTextColor.YELLOW),
                ),
            ),
        ) { p, _ -> GuiStyle.clickSound(p); arenaMenu.openList(p, filter = sessions.active?.type) }

        gui.setItem(GuiStyle.closeSlot(MAIN_SIZE), GuiStyle.close()) { p, _ -> p.closeInventory() }
        gui.fill(GuiStyle.filler())
    }

    private fun statusItem(session: EventSession?): org.bukkit.inventory.ItemStack {
        if (session == null) {
            return GuiStyle.item(
                Material.BOOK, Component.text("Event Status", NamedTextColor.GOLD),
                listOf(Component.text("No event is active.", NamedTextColor.GRAY)),
            )
        }
        val lore = mutableListOf<Component>(
            line("Type", session.type.displayName, NamedTextColor.WHITE),
            line("State", session.state.label, session.state.color),
            line("Host", session.hostName, NamedTextColor.WHITE),
            line("Registration", if (session.state == EventState.OPEN) "Open" else "Closed",
                if (session.state == EventState.OPEN) NamedTextColor.GREEN else NamedTextColor.RED),
            line("Registered", session.participantCount.toString(), NamedTextColor.WHITE),
            line("Created", ago(session.createdAt), NamedTextColor.WHITE),
        )
        lore += arenaLine(session)
        if (!session.state.isActive) lore += Component.text("No event is active.", NamedTextColor.GRAY)
        return GuiStyle.item(session.type.icon, Component.text("Event Status", NamedTextColor.GOLD), lore)
    }

    // ── Create menu ───────────────────────────────────────

    private fun openCreate(player: Player) {
        if (!guard(player)) return
        val gui = CustomGui(title("Create Event"), SUB_SIZE)
        EventType.entries.forEachIndexed { i, type ->
            gui.setItem(
                TYPE_SLOTS[i],
                GuiStyle.item(
                    type.icon, Component.text(type.displayName, NamedTextColor.GOLD),
                    listOf(
                        Component.text(type.description, NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("ID: ${type.id}", NamedTextColor.DARK_GRAY),
                        Component.text("Click to create", NamedTextColor.YELLOW),
                    ),
                ),
            ) { p, _ ->
                if (!guard(p)) return@setItem
                if (report(p, sessions.create(type, p))) open(p)
            }
        }
        gui.setItem(GuiStyle.backSlot(SUB_SIZE), GuiStyle.back("Back to Event Manager")) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.setItem(GuiStyle.closeSlot(SUB_SIZE), GuiStyle.close()) { p, _ -> p.closeInventory() }
        gui.fill(GuiStyle.filler())
        plugin.guiManager.open(player, gui)
    }

    // ── Settings view ─────────────────────────────────────

    private fun openSettings(player: Player) {
        if (!guard(player)) return
        val gui = CustomGui(title("Event Settings"), SUB_SIZE)
        val session = sessions.active
        gui.setItem(
            11,
            GuiStyle.item(
                session?.type?.icon ?: Material.BARRIER, Component.text("Selected Event", NamedTextColor.GOLD),
                listOf(
                    if (session != null) Component.text(session.type.displayName, NamedTextColor.WHITE)
                    else Component.text("No event is active.", NamedTextColor.GRAY),
                ),
            ),
        )
        gui.setItem(
            15,
            GuiStyle.item(
                Material.GRASS_BLOCK, Component.text("Event World", NamedTextColor.GOLD),
                settingsLines() + listOf(
                    Component.empty(),
                    Component.text("Admins change these with /event pvp|build|break|setworld.", NamedTextColor.DARK_GRAY),
                ),
            ),
        )
        gui.setItem(GuiStyle.backSlot(SUB_SIZE), GuiStyle.back("Back to Event Manager")) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.setItem(GuiStyle.closeSlot(SUB_SIZE), GuiStyle.close()) { p, _ -> p.closeInventory() }
        gui.fill(GuiStyle.filler())
        plugin.guiManager.open(player, gui)
    }

    // ── Confirmation ──────────────────────────────────────

    /**
     * Confirm/cancel prompt. [sessionId] pins the session it was opened for: if that session was
     * replaced in the meantime, Confirm refuses instead of acting on the new one.
     */
    private fun openConfirm(player: Player, question: String, details: List<String>, sessionId: UUID, action: () -> EventResult) {
        if (!guard(player)) return
        val gui = CustomGui(title("Confirm"), SUB_SIZE)
        gui.setItem(
            13,
            GuiStyle.item(Material.PAPER, Component.text(question, NamedTextColor.YELLOW),
                details.map { Component.text(it, NamedTextColor.GRAY) }),
        )
        gui.setItem(11, GuiStyle.item(Material.LIME_CONCRETE, Component.text("Confirm", NamedTextColor.GREEN))) { p, _ ->
            if (!guard(p)) return@setItem
            if (sessions.current?.id != sessionId) {
                GuiStyle.errorSound(p)
                Msg.error(p, "That event changed while this menu was open. Nothing was done.", CommunicationsManager.Category.EVENT)
                open(p)
                return@setItem
            }
            report(p, action())
            open(p)
        }
        gui.setItem(15, GuiStyle.item(Material.RED_CONCRETE, Component.text("Cancel", NamedTextColor.RED))) { p, _ ->
            GuiStyle.clickSound(p); open(p)
        }
        gui.fill(GuiStyle.filler())
        plugin.guiManager.open(player, gui)
    }

    // ── Helpers ───────────────────────────────────────────

    private fun actionButton(
        gui: CustomGui, slot: Int, material: Material, name: String, description: String,
        unavailable: String?, onUse: (Player) -> Unit,
    ) {
        val item = if (unavailable == null) {
            GuiStyle.item(material, Component.text(name, NamedTextColor.GREEN), listOf(
                Component.text(description, NamedTextColor.GRAY),
                Component.empty(),
                Component.text("Click to use", NamedTextColor.YELLOW),
            ))
        } else {
            GuiStyle.item(material, Component.text(name, NamedTextColor.DARK_GRAY).decoration(TextDecoration.STRIKETHROUGH, true), listOf(
                Component.text(description, NamedTextColor.GRAY),
                Component.empty(),
                GuiStyle.error("Unavailable: $unavailable"),
            ))
        }
        // Disabled buttons still route through the action: the session re-validates and explains why.
        gui.setItem(slot, item) { p, _ ->
            if (!guard(p)) return@setItem
            GuiStyle.clickSound(p)
            onUse(p)
        }
    }

    /** Runs a lifecycle action from the main menu and reports it. The menu refreshes through onChange. */
    private fun act(player: Player, action: () -> EventResult) {
        report(player, action())
        if (plugin.guiManager.getOpenGui(player) !== mainMenus[player.uniqueId]) open(player)
    }

    /** Sends [result] to [player] with a matching sound. Returns true on success. */
    private fun report(player: Player, result: EventResult): Boolean {
        when (result) {
            is EventResult.Success -> {
                GuiStyle.successSound(player)
                Msg.success(player, result.message, CommunicationsManager.Category.EVENT)
            }
            is EventResult.Failure, is EventResult.NeedsConfirmation -> {
                GuiStyle.errorSound(player)
                Msg.error(player, result.message, CommunicationsManager.Category.EVENT)
            }
        }
        return result is EventResult.Success
    }

    /** Permission check for every GUI action, not just for opening the menu. */
    private fun guard(player: Player): Boolean {
        if (EventPermissions.canHost(player)) return true
        player.closeInventory()
        Msg.noPermission(player, CommunicationsManager.Category.EVENT)
        return false
    }

    /** "Arena: name (STATUS)" for the status item, or "Arena: none". */
    private fun arenaLine(session: EventSession): Component {
        val id = session.arenaId ?: return line("Arena", "none selected", NamedTextColor.DARK_GRAY)
        val arena = arenas.selectedFor(session) ?: return line("Arena", "$id (deleted)", NamedTextColor.RED)
        val report = arenas.report(arena)
        return Component.text("Arena: ", NamedTextColor.GRAY)
            .append(Component.text(arena.displayName + " ", NamedTextColor.WHITE))
            .append(Component.text(report.status.name, ArenaCommands.statusColor(report.status)))
    }

    private fun title(text: String) = Component.text(text, NamedTextColor.DARK_GRAY)

    private fun line(label: String, value: String, color: NamedTextColor) =
        Component.text("$label: ", NamedTextColor.GRAY).append(Component.text(value, color))

    private fun ago(at: Long): String {
        val d = Duration.ofMillis((System.currentTimeMillis() - at).coerceAtLeast(0))
        return when {
            d.toHours() > 0 -> "${d.toHours()}h ${d.toMinutesPart()}m ago"
            d.toMinutes() > 0 -> "${d.toMinutes()}m ago"
            else -> "just now"
        }
    }
}
