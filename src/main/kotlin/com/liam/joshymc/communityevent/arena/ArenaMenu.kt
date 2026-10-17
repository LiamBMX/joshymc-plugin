package com.liam.joshymc.communityevent.arena

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.EventPermissions
import com.liam.joshymc.communityevent.EventResult
import com.liam.joshymc.communityevent.EventSessions
import com.liam.joshymc.communityevent.EventState
import com.liam.joshymc.communityevent.EventType
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * The "Arena Setup" section of the Event Manager GUI (issue #1183): saved arenas, their
 * readiness, per-setting editing, selection for the current event and confirmed deletion.
 *
 * Built on [CustomGui]/[GuiStyle], so GuiManager blocks item theft, shift-clicks, number
 * keys and drags. Every click re-checks permission and re-reads the arena by id, so a stale
 * menu can't act on an arena that changed or was deleted.
 */
class ArenaMenu(
    private val plugin: Joshymc,
    private val arenas: EventArenas,
    private val sessions: EventSessions,
    private val selections: RegionSelections,
    private val commands: ArenaCommands,
    private val giveWand: (Player) -> Unit,
    private val openMain: (Player) -> Unit,
) {
    private val category = CommunicationsManager.Category.EVENT

    private companion object {
        const val LIST_SIZE = 54
        const val DETAIL_SIZE = 54
        const val SMALL_SIZE = 27
        val CONTENT_SLOTS: List<Int> = (1..4).flatMap { row -> (1..7).map { col -> row * 9 + col } }
        const val SLOT_HEADER = 4
        const val SLOT_FILTER = 8
        const val SLOT_CREATE = 53
        const val SLOT_SELECT = 37
        const val SLOT_WAND = 39
        const val SLOT_SHOW = 41
        const val SLOT_DELETE = 43
        const val SLOT_CHAT = 51
        val TYPE_SLOTS = intArrayOf(11, 12, 13, 14, 15)
    }

    // ── Arena list ────────────────────────────────────────

    fun openList(player: Player, page: Int = 0, filter: EventType? = null) {
        if (!canView(player)) return
        val shown = if (filter != null) arenas.ofType(filter) else arenas.all()
        val pages = maxOf(1, (shown.size + CONTENT_SLOTS.size - 1) / CONTENT_SLOTS.size)
        val p = page.coerceIn(0, pages - 1)
        val gui = CustomGui(title(if (pages > 1) "Arenas (${p + 1}/$pages)" else "Arena Setup"), LIST_SIZE)
        gui.border(GuiStyle.filler())

        val session = sessions.active
        gui.setItem(SLOT_HEADER, GuiStyle.item(Material.MAP, Component.text("Arena Setup", NamedTextColor.GOLD), buildList {
            add(Component.text("Saved arenas are reusable event presets.", NamedTextColor.GRAY))
            add(Component.text("Set them up once, then select one per event.", NamedTextColor.GRAY))
            add(Component.empty())
            if (session != null) {
                add(line("Current event", session.type.displayName, NamedTextColor.WHITE))
                add(line("Selected arena", arenas.selectedFor(session)?.displayName ?: "none", NamedTextColor.AQUA))
            } else add(Component.text("No event is active.", NamedTextColor.DARK_GRAY))
        }))

        gui.setItem(SLOT_FILTER, GuiStyle.item(filter?.icon ?: Material.HOPPER, Component.text("Filter: ${filter?.displayName ?: "All types"}", NamedTextColor.YELLOW),
            listOf(Component.text("Click to cycle event types.", NamedTextColor.GRAY)))) { pl, _ ->
            GuiStyle.clickSound(pl)
            val next = when (filter) {
                null -> EventType.entries.first()
                EventType.entries.last() -> null
                else -> EventType.entries[filter.ordinal + 1]
            }
            openList(pl, 0, next)
        }

        if (shown.isEmpty()) {
            gui.setItem(22, GuiStyle.item(Material.BARRIER, Component.text("No arenas yet", NamedTextColor.GRAY), listOf(
                Component.text("Create one with the Create Arena button", NamedTextColor.GRAY),
                Component.text("or /event arena create <name> <type>.", NamedTextColor.GRAY),
            )))
        }
        shown.drop(p * CONTENT_SLOTS.size).take(CONTENT_SLOTS.size).forEachIndexed { i, arena ->
            gui.setItem(CONTENT_SLOTS[i], arenaIcon(arena)) { pl, _ ->
                GuiStyle.clickSound(pl)
                openDetail(pl, arena.id)
            }
        }

        gui.setItem(GuiStyle.backSlot(LIST_SIZE), GuiStyle.back("Back to Event Manager")) { pl, _ -> GuiStyle.clickSound(pl); back(pl) }
        if (p > 0) gui.setItem(GuiStyle.prevSlot(LIST_SIZE), GuiStyle.previousPage(p, pages)) { pl, _ -> GuiStyle.pageSound(pl); openList(pl, p - 1, filter) }
        if (p < pages - 1) gui.setItem(GuiStyle.nextSlot(LIST_SIZE), GuiStyle.nextPage(p, pages)) { pl, _ -> GuiStyle.pageSound(pl); openList(pl, p + 1, filter) }
        gui.setItem(GuiStyle.closeSlot(LIST_SIZE), GuiStyle.close()) { pl, _ -> pl.closeInventory() }
        if (EventPermissions.canSetupArenas(player)) {
            gui.setItem(SLOT_CREATE, GuiStyle.item(Material.NETHER_STAR, Component.text("Create Arena", NamedTextColor.GREEN), listOf(
                Component.text("Pick an event type, then name it in chat.", NamedTextColor.GRAY),
            ))) { pl, _ ->
                if (!canSetup(pl)) return@setItem
                GuiStyle.clickSound(pl)
                openCreate(pl)
            }
        }
        gui.fill(GuiStyle.filler())
        plugin.guiManager.open(player, gui)
    }

    private fun arenaIcon(arena: EventArena): ItemStack {
        val report = arenas.report(arena)
        val lore = mutableListOf<Component>(
            line("Type", arena.type.displayName, NamedTextColor.WHITE),
            line("Status", report.status.name, ArenaCommands.statusColor(report.status)),
        )
        if (report.missing.isNotEmpty()) lore += GuiStyle.warning("Missing: ${report.missing.joinToString(", ") { it.label }}")
        report.problems.take(2).forEach { lore += GuiStyle.error(it) }
        if (arenas.isSelectedByActiveEvent(arena)) lore += Component.text("Selected for the current event", NamedTextColor.AQUA)
        lore += Component.empty()
        lore += Component.text("Click to open", NamedTextColor.YELLOW)
        return GuiStyle.item(arena.type.icon, Component.text(arena.displayName, NamedTextColor.GOLD), lore)
    }

    // ── Create ────────────────────────────────────────────

    private fun openCreate(player: Player) {
        if (!canSetup(player)) return
        val gui = CustomGui(title("Create Arena"), SMALL_SIZE)
        EventType.entries.forEachIndexed { i, type ->
            val layout = ArenaLayouts.of(type)
            gui.setItem(TYPE_SLOTS[i], GuiStyle.item(type.icon, Component.text(type.displayName, NamedTextColor.GOLD), buildList {
                add(Component.text("Needs:", NamedTextColor.GRAY))
                layout.required.forEach { add(Component.text(" - ${it.label}", NamedTextColor.WHITE)) }
                add(Component.empty())
                add(Component.text("Click, then type the name in chat", NamedTextColor.YELLOW))
            })) { pl, _ ->
                if (!canSetup(pl)) return@setItem
                GuiStyle.clickSound(pl)
                pl.closeInventory()
                val command = "/event arena create ${type.id} "
                Msg.reply(pl, Component.text("Name your ${type.displayName} arena: ", NamedTextColor.YELLOW)
                    .append(Component.text("[Click here]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.suggestCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text("Fills in $command - add a name (2-32 letters, numbers, _ or -)", NamedTextColor.GRAY))))
                    .append(Component.text(" then add the name and press Enter.", NamedTextColor.YELLOW)), category)
            }
        }
        gui.setItem(GuiStyle.backSlot(SMALL_SIZE), GuiStyle.back("Back to Arenas")) { pl, _ -> GuiStyle.clickSound(pl); openList(pl) }
        gui.setItem(GuiStyle.closeSlot(SMALL_SIZE), GuiStyle.close()) { pl, _ -> pl.closeInventory() }
        gui.fill(GuiStyle.filler())
        plugin.guiManager.open(player, gui)
    }

    // ── Arena detail ──────────────────────────────────────

    fun openDetail(player: Player, arenaId: String) {
        if (!canView(player)) return
        val arena = arenas.get(arenaId) ?: run { gone(player, arenaId); openList(player); return }
        val report = arenas.report(arena)
        val gui = CustomGui(title(arena.displayName.take(24)), DETAIL_SIZE)
        gui.border(GuiStyle.filler())

        gui.setItem(SLOT_HEADER, GuiStyle.item(arena.type.icon, Component.text(arena.displayName, NamedTextColor.GOLD), buildList {
            add(line("Type", arena.type.displayName, NamedTextColor.WHITE))
            add(line("World", arena.world, NamedTextColor.WHITE))
            add(line("Status", report.status.name, ArenaCommands.statusColor(report.status)))
            if (report.missing.isNotEmpty()) add(GuiStyle.warning("Missing: ${report.missing.joinToString(", ") { it.label }}"))
            report.problems.take(4).forEach { add(GuiStyle.error(it)) }
            if (report.problems.size > 4) add(GuiStyle.error("...and ${report.problems.size - 4} more (see Details in Chat)"))
            add(Component.empty())
            add(line("Created by", arena.createdBy, NamedTextColor.WHITE))
            add(line("Last edited by", arena.updatedBy, NamedTextColor.WHITE))
        }))

        val fields = arena.layout.fields
        val slots = when (fields.size) {
            1 -> listOf(22); 2 -> listOf(21, 23); 3 -> listOf(20, 22, 24); 4 -> listOf(19, 21, 23, 25)
            else -> (19..25).toList()
        }
        fields.zip(slots).forEach { (field, slot) ->
            gui.setItem(slot, fieldItem(arena, field)) { pl, event ->
                if (!canSetup(pl)) return@setItem
                val fresh = arenas.get(arenaId) ?: run { gone(pl, arenaId); openList(pl); return@setItem }
                arenas.setEditTarget(pl, fresh)
                val result = when {
                    event.isShiftClick && event.isRightClick -> arenas.unset(pl, fresh, field)
                    event.isRightClick -> if (field.kind == ArenaFieldKind.POINT_LIST) arenas.removeLastPoint(pl, fresh, field) else null
                    else -> when (field.kind) {
                        ArenaFieldKind.POINT -> arenas.setPoint(pl, fresh, field)
                        ArenaFieldKind.POINT_LIST -> arenas.addPoint(pl, fresh, field)
                        ArenaFieldKind.REGION -> arenas.setRegion(pl, fresh, field, selections.selection(pl))
                        ArenaFieldKind.HEIGHT -> arenas.setHeight(pl, fresh, field, pl.location.blockY)
                    }
                } ?: return@setItem
                report(pl, result)
                openDetail(pl, arenaId)
            }
        }

        // Select for the current event (hosts).
        val session = sessions.active
        val selectProblem = when {
            !EventPermissions.canHost(player) -> "Only event hosts can select arenas."
            session == null -> "Create an event first."
            session.state == EventState.RUNNING -> "The event is running."
            session.type != arena.type -> "The current event is ${session.type.displayName}."
            session.arenaId == arena.id -> "Already selected."
            else -> null
        }
        gui.setItem(SLOT_SELECT, button(Material.LIME_DYE, "Select for Current Event",
            "Use this arena for the active event. Doesn't start it.", selectProblem)) { pl, _ ->
            if (!EventPermissions.canHost(pl)) { deny(pl); return@setItem }
            val fresh = arenas.get(arenaId) ?: run { gone(pl, arenaId); openList(pl); return@setItem }
            when (val result = arenas.select(fresh, pl)) {
                is EventResult.Success -> {
                    GuiStyle.successSound(pl); Msg.success(pl, result.message, category)
                    val r = arenas.report(fresh)
                    if (!r.isReady) Msg.reply(pl, Component.text("Arena status: ${r.summary()}. The event can't start until it's READY.", NamedTextColor.YELLOW), category)
                }
                else -> { GuiStyle.errorSound(pl); Msg.error(pl, result.message, category) }
            }
            openDetail(pl, arenaId)
        }

        gui.setItem(SLOT_WAND, button(Material.BLAZE_ROD, "Selection Wand",
            "Left-click a block for corner 1, right-click for corner 2, then click a region setting here.",
            if (EventPermissions.canSetupArenas(player)) null else "Needs arena setup permission.")) { pl, _ ->
            if (!canSetup(pl)) return@setItem
            GuiStyle.successSound(pl)
            giveWand(pl)
            Msg.success(pl, "Selection wand added. It only records corners; it never changes blocks.", category)
        }

        gui.setItem(SLOT_SHOW, button(Material.SPYGLASS, "Show in World",
            "Outline regions and mark positions with particles only you can see.", null)) { pl, _ ->
            if (!canView(pl)) return@setItem
            val fresh = arenas.get(arenaId) ?: run { gone(pl, arenaId); return@setItem }
            GuiStyle.clickSound(pl)
            pl.closeInventory()
            commands.preview(pl, fresh)
        }

        val deleteProblem = when {
            !EventPermissions.canSetupArenas(player) -> "Needs arena setup permission."
            arenas.isSelectedByActiveEvent(arena) -> "It's selected for the current event."
            else -> null
        }
        gui.setItem(SLOT_DELETE, button(Material.LAVA_BUCKET, "Delete Arena",
            "Removes only this arena's saved settings. Asks to confirm.", deleteProblem)) { pl, _ ->
            if (!canSetup(pl)) return@setItem
            GuiStyle.clickSound(pl)
            openDelete(pl, arenaId)
        }

        gui.setItem(SLOT_CHAT, GuiStyle.item(Material.WRITABLE_BOOK, Component.text("Details in Chat", NamedTextColor.YELLOW),
            listOf(Component.text("Print every setting and problem.", NamedTextColor.GRAY)))) { pl, _ ->
            if (!canView(pl)) return@setItem
            val fresh = arenas.get(arenaId) ?: run { gone(pl, arenaId); return@setItem }
            GuiStyle.clickSound(pl)
            pl.closeInventory()
            commands.info(pl, fresh)
        }

        gui.setItem(GuiStyle.backSlot(DETAIL_SIZE), GuiStyle.back("Back to Arenas")) { pl, _ -> GuiStyle.clickSound(pl); openList(pl) }
        gui.setItem(GuiStyle.closeSlot(DETAIL_SIZE), GuiStyle.close()) { pl, _ -> pl.closeInventory() }
        gui.fill(GuiStyle.filler())
        plugin.guiManager.open(player, gui)
    }

    private fun fieldItem(arena: EventArena, field: ArenaField): ItemStack {
        val set = arena.isSet(field)
        val required = arena.layout.isRequired(field)
        val value = commands.describe(arena, field)
        val lore = mutableListOf<Component>(
            Component.text(field.description, NamedTextColor.GRAY),
            Component.text(if (required) "Required" else "Optional", if (required) NamedTextColor.GOLD else NamedTextColor.DARK_GRAY),
            Component.empty(),
            if (value != null) Component.text(value, NamedTextColor.WHITE)
            else Component.text(if (required) "Missing" else "Not set", if (required) NamedTextColor.RED else NamedTextColor.DARK_GRAY),
            Component.empty(),
        )
        lore += Component.text(when (field.kind) {
            ArenaFieldKind.POINT -> "Left-click: set to your position"
            ArenaFieldKind.POINT_LIST -> "Left-click: add a point at your position"
            ArenaFieldKind.REGION -> "Left-click: save your wand selection"
            ArenaFieldKind.HEIGHT -> "Left-click: set to your current Y"
        }, NamedTextColor.YELLOW)
        if (field.kind == ArenaFieldKind.POINT_LIST) lore += Component.text("Right-click: remove the last point", NamedTextColor.YELLOW)
        if (set) lore += Component.text("Shift + right-click: clear", NamedTextColor.YELLOW)
        lore += Component.text(commands.setCommand(arena, field), NamedTextColor.DARK_GRAY)
        val color = when {
            set -> NamedTextColor.GREEN
            required -> NamedTextColor.RED
            else -> NamedTextColor.GRAY
        }
        return GuiStyle.item(fieldIcon(field), Component.text(field.label, color), lore)
    }

    private fun fieldIcon(field: ArenaField): Material = when (field) {
        ArenaField.LOBBY -> Material.RED_BED
        ArenaField.SPAWN -> Material.COMPASS
        ArenaField.FLOOR -> Material.SNOW_BLOCK
        ArenaField.LOSE_HEIGHT -> Material.LADDER
        ArenaField.SHARK_SPAWN -> Material.PRISMARINE_SHARD
        ArenaField.MINNOW_SPAWN -> Material.COD
        ArenaField.FINISH -> Material.LIGHT_WEIGHTED_PRESSURE_PLATE
        ArenaField.SPAWN_1 -> Material.RED_BANNER
        ArenaField.SPAWN_2 -> Material.BLUE_BANNER
        ArenaField.REGION -> Material.IRON_BARS
        ArenaField.MOB_SPAWNS -> Material.SPAWNER
    }

    // ── Delete confirmation ───────────────────────────────

    private fun openDelete(player: Player, arenaId: String) {
        if (!canSetup(player)) return
        val arena = arenas.get(arenaId) ?: run { gone(player, arenaId); openList(player); return }
        val gui = CustomGui(title("Delete Arena?"), SMALL_SIZE)
        gui.setItem(13, GuiStyle.item(Material.PAPER, Component.text("Delete '${arena.displayName}'?", NamedTextColor.YELLOW), listOf(
            Component.text("Type: ${arena.type.displayName}", NamedTextColor.GRAY),
            Component.text("Removes only its saved settings.", NamedTextColor.GRAY),
            Component.text("No blocks, claims or regions are changed.", NamedTextColor.GRAY),
        )))
        gui.setItem(11, GuiStyle.item(Material.LIME_CONCRETE, Component.text("Confirm Delete", NamedTextColor.GREEN))) { pl, _ ->
            if (!canSetup(pl)) return@setItem
            val fresh = arenas.get(arenaId) ?: run { gone(pl, arenaId); openList(pl); return@setItem }
            // Pinned to the arena as it was shown: if it was edited meanwhile, show it again instead.
            if (fresh.updatedAt != arena.updatedAt) {
                GuiStyle.errorSound(pl)
                Msg.error(pl, "'${fresh.displayName}' changed while this menu was open. Nothing was deleted.", category)
                openDetail(pl, arenaId)
                return@setItem
            }
            val result = arenas.delete(fresh, pl)
            report(pl, result)
            if (result is ArenaResult.Success) openList(pl) else openDetail(pl, arenaId)
        }
        gui.setItem(15, GuiStyle.item(Material.RED_CONCRETE, Component.text("Cancel", NamedTextColor.RED))) { pl, _ ->
            GuiStyle.clickSound(pl); openDetail(pl, arenaId)
        }
        gui.fill(GuiStyle.filler())
        plugin.guiManager.open(player, gui)
    }

    // ── Helpers ───────────────────────────────────────────

    private fun back(player: Player) {
        if (EventPermissions.canHost(player)) openMain(player) else player.closeInventory()
    }

    private fun button(material: Material, name: String, description: String, unavailable: String?): ItemStack =
        if (unavailable == null) {
            GuiStyle.item(material, Component.text(name, NamedTextColor.GREEN), listOf(
                Component.text(description, NamedTextColor.GRAY), Component.empty(),
                Component.text("Click to use", NamedTextColor.YELLOW),
            ))
        } else {
            GuiStyle.item(material, Component.text(name, NamedTextColor.DARK_GRAY).decoration(TextDecoration.STRIKETHROUGH, true), listOf(
                Component.text(description, NamedTextColor.GRAY), Component.empty(),
                GuiStyle.error("Unavailable: $unavailable"),
            ))
        }

    private fun report(player: Player, result: ArenaResult) {
        when (result) {
            is ArenaResult.Success -> {
                GuiStyle.successSound(player)
                Msg.success(player, result.message, category)
                result.warnings.forEach { Msg.reply(player, Component.text("Warning: $it", NamedTextColor.YELLOW), category) }
            }
            is ArenaResult.Failure -> {
                GuiStyle.errorSound(player)
                Msg.error(player, result.message, category)
            }
        }
    }

    private fun canView(player: Player): Boolean {
        if (commands.canView(player)) return true
        player.closeInventory()
        deny(player)
        return false
    }

    private fun canSetup(player: Player): Boolean {
        if (EventPermissions.canSetupArenas(player)) return true
        deny(player)
        return false
    }

    private fun deny(player: Player) {
        GuiStyle.errorSound(player)
        Msg.noPermission(player, category)
    }

    private fun gone(player: Player, arenaId: String) {
        GuiStyle.errorSound(player)
        Msg.error(player, "Arena '$arenaId' no longer exists.", category)
    }

    private fun title(text: String) = Component.text(text, NamedTextColor.DARK_GRAY)

    private fun line(label: String, value: String, color: NamedTextColor) =
        Component.text("$label: ", NamedTextColor.GRAY).append(Component.text(value, color))
}
