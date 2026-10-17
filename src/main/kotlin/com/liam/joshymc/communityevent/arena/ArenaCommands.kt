package com.liam.joshymc.communityevent.arena

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.EventPermissions
import com.liam.joshymc.communityevent.EventResult
import com.liam.joshymc.communityevent.EventSessions
import com.liam.joshymc.communityevent.EventType
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `/event arena ...` and `/event <type> set...` (issue #1183). Called from
 * [com.liam.joshymc.manager.EventManager.EventCommand].
 *
 * Viewing arenas needs host or arena permission, picking one for the event needs host,
 * and creating/editing/deleting needs [EventPermissions.canSetupArenas]. Nothing here
 * teleports anyone or changes blocks.
 */
class ArenaCommands(
    private val plugin: Joshymc,
    private val arenas: EventArenas,
    private val sessions: EventSessions,
    private val selections: RegionSelections,
    private val giveWand: (Player) -> Unit,
    private val openMenu: (Player) -> Unit,
) {
    private val category = CommunicationsManager.Category.EVENT

    private val viewSubs = listOf("list", "info", "show", "menu")
    private val hostSubs = listOf("select", "unselect")
    private val setupSubs = listOf("create", "edit", "delete", "setlobby", "setspawn", "wand", "pos1", "pos2", "unset")

    // ── /event arena ──────────────────────────────────────

    fun handleArena(sender: CommandSender, args: Array<out String>) {
        val sub = args.getOrNull(1)?.lowercase()
        if (sub == null || sub == "help") { if (canView(sender)) arenaHelp(sender) else Msg.noPermission(sender, category); return }
        when (sub) {
            in viewSubs -> if (!canView(sender)) { Msg.noPermission(sender, category); return }
            in hostSubs -> if (!EventPermissions.canHost(sender)) { Msg.noPermission(sender, category); return }
            in setupSubs -> if (!EventPermissions.canSetupArenas(sender)) { Msg.noPermission(sender, category); return }
            else -> { arenaHelp(sender); return }
        }
        when (sub) {
            "list" -> list(sender, args.getOrNull(2))
            "info" -> {
                val arena = args.getOrNull(2)?.let { named(sender, it) ?: return }
                    ?: (sender as? Player)?.let { arenas.editTarget(it) }
                    ?: arenas.selectedFor(sessions.active)
                    ?: run { Msg.usage(sender, "/event arena info <name>", category); return }
                info(sender, arena)
            }
            "show" -> {
                val player = player(sender) ?: return
                val arena = args.getOrNull(2)?.let { named(sender, it) ?: return } ?: target(player) ?: return
                preview(player, arena)
            }
            "menu" -> player(sender)?.let { openMenu(it) }
            "create" -> create(sender, args)
            "select" -> {
                val name = args.getOrNull(2) ?: run { Msg.usage(sender, "/event arena select <name>", category); return }
                val arena = named(sender, name) ?: return
                val result = arenas.select(arena, sender)
                reply(sender, result)
                if (result is EventResult.Success) readiness(sender, arena)
            }
            "unselect" -> reply(sender, sessions.clearArena())
            "edit" -> {
                val player = player(sender) ?: return
                val name = args.getOrNull(2) ?: run { Msg.usage(sender, "/event arena edit <name>", category); return }
                val arena = named(sender, name) ?: return
                arenas.setEditTarget(player, arena)
                Msg.success(sender, "You're now editing '${arena.displayName}' (${arena.type.displayName}).", category)
                readiness(sender, arena)
            }
            "delete" -> delete(sender, args)
            "setlobby" -> {
                val player = player(sender) ?: return
                val arena = target(player) ?: return
                edited(player, arenas.setPoint(player, arena, ArenaField.LOBBY))
            }
            "setspawn" -> {
                val player = player(sender) ?: return
                val arena = target(player) ?: return
                if (!arena.layout.allows(ArenaField.SPAWN)) {
                    val hint = arena.layout.commands.entries.filter { it.value.kind == ArenaFieldKind.POINT }
                        .joinToString(" or ") { "/event ${arena.type.id} ${it.key}" }
                    Msg.error(sender, "${arena.type.displayName} arenas have no general player spawn. Use $hint.", category)
                    return
                }
                edited(player, arenas.setPoint(player, arena, ArenaField.SPAWN))
            }
            "wand" -> player(sender)?.let {
                giveWand(it)
                Msg.success(it, "Selection wand added. Left-click a block for corner 1, right-click for corner 2. It never changes blocks.", category)
            }
            "pos1", "pos2" -> {
                val player = player(sender) ?: return
                val block = player.getTargetBlockExact(8) ?: player.location.block
                if (sub == "pos1") selections.setPos1(player, block.location) else selections.setPos2(player, block.location)
                Msg.success(player, "Corner ${sub.last()} set to (${block.x}, ${block.y}, ${block.z}) in ${block.world.name}.", category)
                selectionSummary(player)
            }
            "unset" -> {
                val player = player(sender) ?: return
                val arena = target(player) ?: return
                val key = args.getOrNull(2) ?: run {
                    Msg.usage(sender, "/event arena unset <${arena.layout.fields.joinToString("|") { it.key }}>", category); return
                }
                val field = ArenaField.fromKey(key)?.takeIf { arena.layout.allows(it) } ?: run {
                    Msg.error(sender, "${arena.type.displayName} arenas have no '$key' setting. Choose: ${arena.layout.fields.joinToString(", ") { it.key }}.", category)
                    return
                }
                edited(player, arenas.unset(player, arena, field))
            }
        }
    }

    private fun create(sender: CommandSender, args: Array<out String>) {
        val a = args.getOrNull(2)
        val b = args.getOrNull(3)
        if (a == null || b == null) {
            Msg.usage(sender, "/event arena create <name> <${EventType.ids.joinToString("|")}>", category); return
        }
        // `<name> <type>` is the documented order; `<type> <name>` (what the GUI suggests) also works.
        val (name, type) = when {
            EventType.fromId(b) != null -> a to EventType.fromId(b)!!
            EventType.fromId(a) != null -> b to EventType.fromId(a)!!
            else -> {
                Msg.error(sender, "Unknown event type '$b'. Choose one of: ${EventType.ids.joinToString(", ")}.", category); return
            }
        }
        val result = arenas.create(name, type, sender)
        reply(sender, result)
        if (result is ArenaResult.Success && result.arena != null) nextSteps(sender, result.arena)
    }

    private fun delete(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(2) ?: run { Msg.usage(sender, "/event arena delete <name> [confirm]", category); return }
        val arena = named(sender, name) ?: return
        if (!args.getOrNull(3).equals("confirm", ignoreCase = true)) {
            Msg.reply(sender,
                Component.text("Delete arena '${arena.displayName}' (${arena.type.displayName})? Only its saved settings are removed. ", NamedTextColor.YELLOW)
                    .append(Component.text("[Confirm]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/event arena delete ${arena.displayName} confirm"))
                        .hoverEvent(HoverEvent.showText(Component.text("Runs /event arena delete ${arena.displayName} confirm", NamedTextColor.GRAY)))),
                category)
            return
        }
        reply(sender, arenas.delete(arena, sender))
    }

    // ── /event <type> ─────────────────────────────────────

    fun handleType(sender: CommandSender, type: EventType, args: Array<out String>) {
        if (!EventPermissions.canSetupArenas(sender)) { Msg.noPermission(sender, category); return }
        val layout = ArenaLayouts.of(type)
        val sub = args.getOrNull(1)?.lowercase()
        val extra = if (type == EventType.MOBS) listOf("removespawn", "clearspawns") else emptyList()
        if (sub == null || (sub !in layout.commands && sub !in extra)) { typeHelp(sender, type); return }
        val player = player(sender) ?: return
        val arena = target(player) ?: return
        if (arena.type != type) {
            Msg.error(sender, "You're editing '${arena.displayName}', a ${arena.type.displayName} arena. Use /event ${arena.type.id} ..., or /event arena edit <name> to switch to a ${type.displayName} arena.", category)
            return
        }
        when (sub) {
            "removespawn" -> { edited(player, arenas.removeLastPoint(player, arena, ArenaField.MOB_SPAWNS)); return }
            "clearspawns" -> { edited(player, arenas.unset(player, arena, ArenaField.MOB_SPAWNS)); return }
        }
        val field = layout.commands.getValue(sub)
        val result = when (field.kind) {
            ArenaFieldKind.POINT -> arenas.setPoint(player, arena, field)
            ArenaFieldKind.POINT_LIST -> arenas.addPoint(player, arena, field)
            ArenaFieldKind.REGION -> arenas.setRegion(player, arena, field, selections.selection(player))
            ArenaFieldKind.HEIGHT -> {
                val raw = args.getOrNull(2)
                val y = if (raw == null) player.location.blockY else raw.toIntOrNull() ?: run {
                    Msg.error(sender, "'$raw' isn't a whole number. Use /event ${type.id} $sub [y], or leave it out to use your current Y.", category); return
                }
                arenas.setHeight(player, arena, field, y)
            }
        }
        edited(player, result)
    }

    // ── Output ────────────────────────────────────────────

    private fun list(sender: CommandSender, typeArg: String?) {
        val type = typeArg?.let { EventType.fromId(it) ?: run { Msg.error(sender, "Unknown event type '$it'.", category); return } }
        val all = if (type != null) arenas.ofType(type) else arenas.all()
        header(sender, if (type != null) "${type.displayName} Arenas" else "Event Arenas")
        if (all.isEmpty()) {
            Msg.reply(sender, Component.text("No arenas saved yet. Create one with /event arena create <name> <type>.", NamedTextColor.GRAY), category)
            return
        }
        val selectedId = sessions.active?.arenaId
        for (arena in all) {
            val report = arenas.report(arena)
            var line = Component.text("${arena.displayName} ", NamedTextColor.WHITE)
                .append(Component.text("(${arena.type.displayName}) ", NamedTextColor.GRAY))
                .append(Component.text(report.status.name, statusColor(report.status)))
            if (arena.id == selectedId) line = line.append(Component.text(" [selected]", NamedTextColor.AQUA))
            Msg.reply(sender, line.clickEvent(ClickEvent.runCommand("/event arena info ${arena.displayName}"))
                .hoverEvent(HoverEvent.showText(Component.text(report.summary(), NamedTextColor.GRAY))), category)
        }
    }

    fun info(sender: CommandSender, arena: EventArena) {
        val report = arenas.report(arena)
        header(sender, "Arena: ${arena.displayName}")
        row(sender, "Type", "${arena.type.displayName} (${arena.type.id})")
        row(sender, "World", arena.world)
        row(sender, "Status", report.status.name, statusColor(report.status))
        row(sender, "Selected for current event", if (arenas.isSelectedByActiveEvent(arena)) "Yes" else "No")
        for (field in arena.layout.fields) {
            val required = arena.layout.isRequired(field)
            val tag = Component.text(if (required) "* " else "  ", NamedTextColor.GOLD)
            val value = describe(arena, field)
            val line = tag.append(Component.text("${field.label}: ", NamedTextColor.GRAY)).append(
                if (value != null) Component.text(value, NamedTextColor.WHITE)
                else Component.text(if (required) "missing" else "not set", if (required) NamedTextColor.RED else NamedTextColor.DARK_GRAY)
                    .append(Component.text(" (${setCommand(arena, field)})", NamedTextColor.DARK_GRAY))
            )
            Msg.reply(sender, line, category)
        }
        report.problems.forEach { Msg.reply(sender, Component.text("Problem: $it", NamedTextColor.RED), category) }
        val zone = (sender as? Player)?.let { plugin.timezoneManager.zoneFor(it) } ?: ZoneId.systemDefault()
        row(sender, "Created", "${time(arena.createdAt, zone)} by ${arena.createdBy}")
        row(sender, "Updated", "${time(arena.updatedAt, zone)} by ${arena.updatedBy}")
        Msg.reply(sender, Component.text("* required", NamedTextColor.GOLD), category)
    }

    /** The stored value of [field] as text, or null when unset. */
    fun describe(arena: EventArena, field: ArenaField): String? = when (field.kind) {
        ArenaFieldKind.POINT -> arena.point(field)?.let { "${it.describe()} (facing ${it.yaw.toInt()}°)" }
        ArenaFieldKind.POINT_LIST -> arena.points(field).takeIf { it.isNotEmpty() }?.let { pts ->
            "${pts.size} point(s): " + pts.take(4).joinToString("; ") { it.describe() } + if (pts.size > 4) "; ..." else ""
        }
        ArenaFieldKind.REGION -> arena.region(field)?.describe()
        ArenaFieldKind.HEIGHT -> arena.height(field)?.let { "Y $it" }
    }

    /** The command that sets [field]. */
    fun setCommand(arena: EventArena, field: ArenaField): String = when (field) {
        ArenaField.LOBBY -> "/event arena setlobby"
        else -> arena.layout.commandFor(field)?.let { "/event ${arena.type.id} $it" } ?: "/event arena menu"
    }

    fun preview(player: Player, arena: EventArena) {
        val regions = arena.values.values.filterIsInstance<ArenaValue.Region>().map { it.region }
        val points = arena.values.values.flatMap {
            when (it) {
                is ArenaValue.Point -> listOf(it.point)
                is ArenaValue.Points -> it.points
                else -> emptyList()
            }
        }
        if (regions.isEmpty() && points.isEmpty()) { Msg.error(player, "'${arena.displayName}' has no locations to show yet.", category); return }
        selections.show(player, regions, points)
        Msg.success(player, "Showing '${arena.displayName}' for a few seconds: orange outlines are regions, cyan markers are positions. Only you can see them.", category)
    }

    private fun readiness(sender: CommandSender, arena: EventArena) {
        val report = arenas.report(arena)
        Msg.reply(sender, Component.text("Arena status: ", NamedTextColor.GRAY)
            .append(Component.text(report.summary(), statusColor(report.status))), category)
        if (report.status == ArenaStatus.INVALID && report.problems.size > 1) {
            report.problems.drop(1).forEach { Msg.reply(sender, Component.text("Problem: $it", NamedTextColor.RED), category) }
        }
    }

    private fun nextSteps(sender: CommandSender, arena: EventArena) {
        val steps = arena.layout.required.joinToString(", ") { setCommand(arena, it) }
        Msg.reply(sender, Component.text("Next: $steps. Optional: /event arena setlobby.", NamedTextColor.GRAY), category)
    }

    private fun edited(player: Player, result: ArenaResult) {
        reply(player, result)
        if (result is ArenaResult.Success) {
            result.warnings.forEach { Msg.reply(player, Component.text("Warning: $it", NamedTextColor.YELLOW), category) }
            result.arena?.let { readiness(player, it) }
        }
    }

    private fun selectionSummary(player: Player) {
        when (val sel = selections.selection(player)) {
            is RegionSelections.Selection.Ok -> {
                val problem = ArenaValidator.checkRegion(sel.region, sel.region.world, null)
                if (problem != null) Msg.error(player, "Selection ${sel.region.sizeX}x${sel.region.sizeY}x${sel.region.sizeZ}: it $problem.", category)
                else Msg.reply(player, Component.text("Selection: ${sel.region.describe()}. Save it with the region command (e.g. /event spleef setfloor).", NamedTextColor.GRAY), category)
            }
            is RegionSelections.Selection.Problem -> if (selections.pos1(player.uniqueId) != null && selections.pos2(player.uniqueId) != null) {
                Msg.error(player, sel.message, category)
            }
        }
    }

    private fun arenaHelp(sender: CommandSender) {
        val lines = mutableListOf("", "&6&lEvent Arenas:")
        lines += "&e/event arena list [type] &7- Saved arenas and their status"
        lines += "&e/event arena info [name] &7- Locations and missing settings"
        lines += "&e/event arena show [name] &7- Outline an arena with particles"
        if (EventPermissions.canHost(sender)) {
            lines += "&e/event arena select <name> &7- Use an arena for the current event"
            lines += "&e/event arena unselect &7- Clear the current event's arena"
        }
        if (EventPermissions.canSetupArenas(sender)) {
            lines += "&e/event arena create <name> <type> &7- New arena (you start editing it)"
            lines += "&e/event arena edit <name> &7- Choose the arena your set-commands change"
            lines += "&e/event arena delete <name> [confirm] &7- Delete an arena's settings"
            lines += "&e/event arena setlobby &7- Lobby at your position"
            lines += "&e/event arena setspawn &7- Player spawn at your position (spleef, mobs)"
            lines += "&e/event arena wand &7- Selection wand; or &e/event arena pos1|pos2"
            lines += "&e/event arena unset <setting> &7- Clear one setting"
            lines += "&e/event <${EventType.ids.joinToString("|")}> &7- Type-specific setup commands"
        }
        lines += ""
        lines.forEach { sender.sendMessage(plugin.commsManager.parseLegacy(it)) }
    }

    private fun typeHelp(sender: CommandSender, type: EventType) {
        val layout = ArenaLayouts.of(type)
        val lines = mutableListOf("", "&6&l${type.displayName} Arena Setup:")
        layout.commands.forEach { (sub, field) ->
            val how = when (field.kind) {
                ArenaFieldKind.POINT -> "at your position"
                ArenaFieldKind.POINT_LIST -> "add one at your position"
                ArenaFieldKind.REGION -> "from your wand selection"
                ArenaFieldKind.HEIGHT -> "[y] - your Y if left out"
            }
            lines += "&e/event ${type.id} $sub &7- ${field.label} ($how)"
        }
        if (type == EventType.MOBS) {
            lines += "&e/event mobs removespawn &7- Remove the last mob spawn point"
            lines += "&e/event mobs clearspawns &7- Remove every mob spawn point"
        }
        lines += "&7Edits apply to the arena you're editing (&e/event arena edit <name>&7)."
        lines += ""
        lines.forEach { sender.sendMessage(plugin.commsManager.parseLegacy(it)) }
    }

    // ── Tab completion ────────────────────────────────────

    fun tabArena(sender: CommandSender, args: Array<out String>): List<String> {
        val view = canView(sender)
        val host = EventPermissions.canHost(sender)
        val setup = EventPermissions.canSetupArenas(sender)
        return when (args.size) {
            2 -> buildList {
                if (view) addAll(viewSubs)
                if (host) addAll(hostSubs)
                if (setup) addAll(setupSubs)
            }
            3 -> when (args[1].lowercase()) {
                "info", "show" -> if (view) names() else emptyList()
                "select" -> if (host) sessions.active?.let { s -> arenas.ofType(s.type).map { it.displayName } } ?: names() else emptyList()
                "edit", "delete" -> if (setup) names() else emptyList()
                "list" -> if (view) EventType.ids else emptyList()
                "create" -> if (setup) EventType.ids else emptyList()
                "unset" -> if (setup) (sender as? Player)?.let { arenas.editTarget(it) }?.layout?.fields?.map { it.key } ?: emptyList() else emptyList()
                else -> emptyList()
            }
            4 -> when (args[1].lowercase()) {
                "create" -> if (setup && EventType.fromId(args[2]) == null) EventType.ids else emptyList()
                "delete" -> if (setup) listOf("confirm") else emptyList()
                else -> emptyList()
            }
            else -> emptyList()
        }.filter { it.lowercase().startsWith(args.last().lowercase()) }
    }

    fun tabType(sender: CommandSender, type: EventType, args: Array<out String>): List<String> {
        if (!EventPermissions.canSetupArenas(sender) || args.size != 2) return emptyList()
        val subs = ArenaLayouts.of(type).commands.keys.toList() +
            if (type == EventType.MOBS) listOf("removespawn", "clearspawns") else emptyList()
        return subs.filter { it.startsWith(args[1].lowercase()) }
    }

    /** Lines for the main `/event` help. */
    fun usageLines(sender: CommandSender): List<String> = buildList {
        if (canView(sender)) add("&e/event arena &7- Arena setup and saved arenas")
    }

    // ── Helpers ───────────────────────────────────────────

    fun canView(sender: CommandSender): Boolean = EventPermissions.canHost(sender) || EventPermissions.canSetupArenas(sender)

    private fun names() = arenas.all().map { it.displayName }

    private fun named(sender: CommandSender, name: String): EventArena? =
        arenas.get(name) ?: run { Msg.error(sender, "No arena named '$name'. See /event arena list.", category); null }

    private fun player(sender: CommandSender): Player? = sender as? Player ?: run { Msg.playersOnly(sender); null }

    private fun target(player: Player): EventArena? = arenas.editTarget(player) ?: run {
        Msg.error(player, "You aren't editing an arena. Use /event arena edit <name> or /event arena create <name> <type>.", category)
        null
    }

    private fun reply(sender: CommandSender, result: ArenaResult) = when (result) {
        is ArenaResult.Success -> Msg.success(sender, result.message, category)
        is ArenaResult.Failure -> Msg.error(sender, result.message, category)
    }

    private fun reply(sender: CommandSender, result: EventResult) = when (result) {
        is EventResult.Success -> Msg.success(sender, result.message, category)
        is EventResult.Failure, is EventResult.NeedsConfirmation -> Msg.error(sender, result.message, category)
    }

    private fun header(sender: CommandSender, text: String) =
        Msg.reply(sender, Component.text("=== $text ===", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true), category)

    private fun row(sender: CommandSender, label: String, value: String, color: TextColor = NamedTextColor.WHITE) =
        Msg.reply(sender, Component.text("$label: ", NamedTextColor.GRAY).append(Component.text(value, color)), category)

    private fun time(at: Long, zone: ZoneId): String = TIME.withZone(zone).format(Instant.ofEpochMilli(at))

    companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")

        fun statusColor(status: ArenaStatus): NamedTextColor = when (status) {
            ArenaStatus.READY -> NamedTextColor.GREEN
            ArenaStatus.INCOMPLETE -> NamedTextColor.YELLOW
            ArenaStatus.INVALID -> NamedTextColor.RED
        }
    }
}
