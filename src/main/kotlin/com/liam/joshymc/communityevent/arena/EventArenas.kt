package com.liam.joshymc.communityevent.arena

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.EventResult
import com.liam.joshymc.communityevent.EventSession
import com.liam.joshymc.communityevent.EventSessions
import com.liam.joshymc.communityevent.EventState
import com.liam.joshymc.communityevent.EventType
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/** Outcome of an arena action; [message] is the feedback for whoever asked. */
sealed interface ArenaResult {
    val message: String

    data class Success(val arena: EventArena?, override val message: String, val warnings: List<String> = emptyList()) : ArenaResult
    data class Failure(override val message: String) : ArenaResult
}

/**
 * Saved event arenas (issue #1183): the in-memory registry over [ArenaStore], every edit,
 * and readiness checks. Main thread only.
 *
 * Arenas are configuration only. Nothing here changes blocks, claims or regions, loads a
 * world or chunk, or teleports anyone. Every location must be in the event world, so
 * future event gameplay stays behind the event world's inventory separation.
 */
class EventArenas(
    private val plugin: Joshymc,
    private val sessions: EventSessions,
    private val eventWorld: () -> String,
) {
    private val arenas = LinkedHashMap<String, EventArena>()

    /** The arena each staff member's set-commands edit (picked by create/edit/select). */
    private val editing = HashMap<UUID, String>()

    private var store: ArenaStore? = null

    // ── Loading ───────────────────────────────────────────

    /** Creates the tables and reads every arena. A failure is logged and never stops the plugin. */
    fun load() {
        try {
            val s = ArenaStore(plugin.databaseManager)
            s.createSchema()
            val result = s.loadAll()
            arenas.clear()
            result.arenas.forEach { arenas[it.id] = it }
            result.warnings.forEach { plugin.logger.warning("[Event] $it") }
            store = s
            plugin.logger.info("[Event] Loaded ${arenas.size} event arena(s).")
        } catch (e: Exception) {
            store = null
            plugin.logger.severe("[Event] Couldn't load event arenas; arena setup is disabled until the next restart: ${e.message}")
        }
    }

    // ── Queries ───────────────────────────────────────────

    fun all(): List<EventArena> = arenas.values.sortedWith(compareBy<EventArena>({ it.type.ordinal }, { it.id }))

    fun ofType(type: EventType): List<EventArena> = all().filter { it.type == type }

    fun get(name: String): EventArena? = arenas[EventArena.idOf(name)]

    fun report(arena: EventArena): ArenaReport =
        ArenaValidator.report(arena, eventWorld()) { name -> worldBounds(name) }

    /** The arena picked for [session], or null when none is picked or it was deleted. */
    fun selectedFor(session: EventSession?): EventArena? = session?.arenaId?.let { arenas[it] }

    fun isSelectedByActiveEvent(arena: EventArena): Boolean = sessions.active?.arenaId == arena.id

    /** The arena [player]'s set-commands change: the one they last created/edited/selected, else the current event's. */
    fun editTarget(player: Player): EventArena? =
        editing[player.uniqueId]?.let { arenas[it] } ?: selectedFor(sessions.active)

    fun setEditTarget(player: Player, arena: EventArena) { editing[player.uniqueId] = arena.id }

    fun forget(uuid: UUID) { editing.remove(uuid) }

    /**
     * Reason the event can't start because of its arena, or null. Types without gameplay may
     * start with no arena picked (Spleef and Sumo require one, see HostedGames.startProblem),
     * but a picked arena must still exist and be READY: nothing falls back to some other location.
     */
    fun startProblem(session: EventSession): String? {
        val id = session.arenaId ?: return null
        val arena = arenas[id] ?: return "The selected arena '$id' no longer exists. Select another with /event arena select <name>."
        val report = report(arena)
        if (report.isReady) return null
        return "Arena '${arena.displayName}' isn't ready (${report.summary()}). Fix it with /event arena info ${arena.displayName}."
    }

    // ── Create / delete / select ──────────────────────────

    fun create(name: String, type: EventType, actor: CommandSender): ArenaResult {
        val s = store ?: return unavailable()
        if (!EventArena.isValidName(name)) {
            return ArenaResult.Failure("Arena names use 2-32 letters, numbers, _ or -. '$name' isn't valid.")
        }
        val id = EventArena.idOf(name)
        arenas[id]?.let { return ArenaResult.Failure("An arena named '${it.displayName}' already exists (${it.type.displayName}). Pick another name.") }
        val now = System.currentTimeMillis()
        val arena = EventArena(
            id = id, displayName = name, type = type, world = eventWorld(), values = emptyMap(),
            createdBy = actorName(actor), createdAt = now, updatedBy = actorName(actor), updatedAt = now,
        )
        val inserted = try { s.insert(arena) } catch (e: Exception) { return saveFailed(e) }
        if (!inserted) return ArenaResult.Failure("An arena with the id '$id' already exists. Pick another name.")
        arenas[id] = arena
        if (actor is Player) setEditTarget(actor, arena)
        plugin.logger.info("[Event] ${actorName(actor)} created ${type.displayName} arena '$id' in world '${arena.world}'.")
        return ArenaResult.Success(arena, "Created ${type.displayName} arena '$name'. You're now editing it.")
    }

    /** Removes one arena's saved configuration. World blocks, claims and regions are never touched. */
    fun delete(arena: EventArena, actor: CommandSender): ArenaResult {
        val s = store ?: return unavailable()
        val current = arenas[arena.id] ?: return ArenaResult.Failure("Arena '${arena.displayName}' no longer exists.")
        if (isSelectedByActiveEvent(current)) {
            return ArenaResult.Failure("'${current.displayName}' is selected for the current event. Stop the event or select another arena first.")
        }
        val deleted = try { s.delete(current.id) } catch (e: Exception) { return saveFailed(e) }
        arenas.remove(current.id)
        editing.values.removeAll { it == current.id }
        if (!deleted) return ArenaResult.Failure("Arena '${current.displayName}' was already gone.")
        plugin.logger.info("[Event] ${actorName(actor)} deleted arena '${current.id}' (configuration only).")
        return ArenaResult.Success(null, "Deleted arena '${current.displayName}'. Only its saved settings were removed; no blocks were changed.")
    }

    /** Picks [arena] for the active event. Never starts it. */
    fun select(arena: EventArena, actor: CommandSender): EventResult {
        val current = arenas[arena.id] ?: return EventResult.Failure("Arena '${arena.displayName}' no longer exists.")
        val result = sessions.assignArena(current.id, current.type, current.displayName)
        if (result is EventResult.Success && actor is Player) setEditTarget(actor, current)
        return result
    }

    // ── Editing ───────────────────────────────────────────

    /** Saves [player]'s current position (with facing) as [field]. */
    fun setPoint(player: Player, arena: EventArena, field: ArenaField): ArenaResult {
        if (field.kind != ArenaFieldKind.POINT) return wrongKind(field)
        val (point, warnings) = when (val c = pointAt(player, arena)) { is Check.No -> return c.failure; is Check.Ok -> c.value }
        return edit(player, arena, field, ArenaValue.Point(point), warnings, "Set ${field.label} to ${point.describe()}.")
    }

    /** Adds [player]'s current position to a point list (mob spawns). */
    fun addPoint(player: Player, arena: EventArena, field: ArenaField): ArenaResult {
        if (field.kind != ArenaFieldKind.POINT_LIST) return wrongKind(field)
        val existing = arena.points(field)
        if (existing.size >= ArenaValidator.MAX_MOB_SPAWNS) {
            return ArenaResult.Failure("${field.label} is full (${ArenaValidator.MAX_MOB_SPAWNS}). Remove one first.")
        }
        val (point, warnings) = when (val c = pointAt(player, arena)) { is Check.No -> return c.failure; is Check.Ok -> c.value }
        if (existing.any { it.distanceSquared(point) < 1.0 }) {
            return ArenaResult.Failure("There's already a ${field.label.lowercase()} entry within a block of here.")
        }
        val list = existing + point
        return edit(player, arena, field, ArenaValue.Points(list), warnings, "Added ${field.label} #${list.size} at ${point.describe()}.")
    }

    fun removeLastPoint(actor: CommandSender, arena: EventArena, field: ArenaField): ArenaResult {
        if (field.kind != ArenaFieldKind.POINT_LIST) return wrongKind(field)
        val existing = arena.points(field)
        if (existing.isEmpty()) return ArenaResult.Failure("${field.label} has no entries.")
        val list = existing.dropLast(1)
        val value = if (list.isEmpty()) null else ArenaValue.Points(list)
        return edit(actor, arena, field, value, emptyList(), "Removed ${field.label} #${existing.size}.")
    }

    /** Saves [player]'s wand/pos1/pos2 selection as [field]. */
    fun setRegion(player: Player, arena: EventArena, field: ArenaField, selection: RegionSelections.Selection): ArenaResult {
        if (field.kind != ArenaFieldKind.REGION) return wrongKind(field)
        val region = when (selection) {
            is RegionSelections.Selection.Problem -> return ArenaResult.Failure(selection.message)
            is RegionSelections.Selection.Ok -> selection.region
        }
        val world = when (val c = requiredWorld(arena)) { is Check.No -> return c.failure; is Check.Ok -> c.value }
        if (region.world != world) {
            return ArenaResult.Failure("Your selection is in world '${region.world}', but '${arena.displayName}' uses '$world'. Select the region in that world.")
        }
        ArenaValidator.checkRegion(region, world, worldBounds(world))?.let {
            return ArenaResult.Failure("That selection $it.")
        }
        return edit(player, arena, field, ArenaValue.Region(region), emptyList(), "Set ${field.label} to ${region.describe()}.")
    }

    fun setHeight(actor: CommandSender, arena: EventArena, field: ArenaField, y: Int): ArenaResult {
        if (field.kind != ArenaFieldKind.HEIGHT) return wrongKind(field)
        ArenaValidator.checkHeight(y, worldBounds(arena.world))?.let { return ArenaResult.Failure("$it.") }
        return edit(actor, arena, field, ArenaValue.Height(y), emptyList(), "Set ${field.label} to Y $y.")
    }

    fun unset(actor: CommandSender, arena: EventArena, field: ArenaField): ArenaResult {
        if (!arena.isSet(field)) return ArenaResult.Failure("${field.label} isn't set.")
        return edit(actor, arena, field, null, emptyList(), "Cleared ${field.label}.")
    }

    // ── Internals ─────────────────────────────────────────

    /** A checked value, or the reason it was refused. */
    private sealed interface Check<out T> {
        data class Ok<T>(val value: T) : Check<T>
        data class No(val message: String) : Check<Nothing> {
            val failure: ArenaResult.Failure get() = ArenaResult.Failure(message)
        }
    }

    /**
     * The world [arena]'s locations must be in: its own world, or the event world when the
     * arena is still empty and the event world has changed since it was created.
     */
    private fun requiredWorld(arena: EventArena): Check<String> {
        val ew = eventWorld()
        if (arena.world == ew || arena.values.isEmpty()) return Check.Ok(ew)
        return Check.No("'${arena.displayName}' was set up in world '${arena.world}', but the event world is now '$ew'. Create a new arena in the event world.")
    }

    /** Validates [player]'s position for [arena]: right world, sane coordinates, room to stand. */
    private fun pointAt(player: Player, arena: EventArena): Check<Pair<ArenaPoint, List<String>>> {
        val world = when (val c = requiredWorld(arena)) { is Check.No -> return c; is Check.Ok -> c.value }
        val loc = player.location
        val locWorld = loc.world?.name ?: return Check.No("You aren't in a loaded world.")
        if (locWorld != world) return Check.No("Arena locations must be in the event world ('$world'). You're in '$locWorld'.")
        val point = ArenaPoint(locWorld, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        ArenaValidator.checkPoint(point, world, worldBounds(world))?.let { return Check.No("Your position $it.") }
        safetyProblem(loc)?.let { return Check.No(it) }
        val warnings = mutableListOf<String>()
        if (!hasGroundBelow(loc)) warnings += "There's no solid ground within $GROUND_SEARCH blocks below this spot; players placed here will fall."
        return Check.Ok(point to warnings)
    }

    /** Refuses spots where a player would suffocate or burn. The chunk is loaded: the player is standing in it. */
    private fun safetyProblem(loc: Location): String? {
        val feet = loc.block
        val head = feet.getRelative(0, 1, 0)
        for (block in listOf(feet, head)) {
            if (block.type in DANGEROUS) return "That spot has ${block.type.name.lowercase().replace('_', ' ')} in it; pick a safe spot."
            if (!block.isPassable) return "That spot is inside a block; players would suffocate. Stand in open space."
        }
        return null
    }

    private fun hasGroundBelow(loc: Location): Boolean {
        val feet = loc.block
        return (0..GROUND_SEARCH).any { feet.getRelative(0, -it, 0).let { b -> b.type.isSolid || b.isLiquid } }
    }

    /**
     * Applies one change and saves it, then swaps the in-memory copy. Refuses fields the
     * arena's type doesn't use (so settings never leak between types) and any change to the
     * arena of a running event.
     */
    private fun edit(
        actor: CommandSender, arena: EventArena, field: ArenaField, value: ArenaValue?,
        warnings: List<String>, message: String,
    ): ArenaResult {
        val s = store ?: return unavailable()
        val current = arenas[arena.id] ?: return ArenaResult.Failure("Arena '${arena.displayName}' no longer exists.")
        if (!current.layout.allows(field)) {
            return ArenaResult.Failure("${current.type.displayName} arenas don't use ${field.label}.")
        }
        if (value != null && !value.matches(field.kind)) return wrongKind(field)
        val session = sessions.active
        if (session != null && session.arenaId == current.id && session.state == EventState.RUNNING) {
            return ArenaResult.Failure("'${current.displayName}' is in use by the running event. Edit it after the event ends.")
        }
        val values = LinkedHashMap(current.values)
        if (value == null) values.remove(field) else values[field] = value
        val updated = current.copy(
            world = when (val c = requiredWorld(current)) { is Check.No -> return c.failure; is Check.Ok -> c.value },
            values = values,
            updatedBy = actorName(actor),
            updatedAt = System.currentTimeMillis(),
        )
        val saved = try { s.update(updated) } catch (e: Exception) { return saveFailed(e) }
        if (!saved) {
            arenas.remove(current.id)
            return ArenaResult.Failure("Arena '${current.displayName}' no longer exists.")
        }
        arenas[updated.id] = updated
        sessions.onChange?.invoke()
        return ArenaResult.Success(updated, "$message (${updated.displayName})", warnings)
    }

    private fun worldBounds(name: String): WorldBounds? =
        Bukkit.getWorld(name)?.let { WorldBounds(it.minHeight, it.maxHeight) }

    private fun actorName(actor: CommandSender) = if (actor is Player) actor.name else "Console"

    private fun wrongKind(field: ArenaField) = ArenaResult.Failure("${field.label} can't be set that way.")

    private fun unavailable() = ArenaResult.Failure("Arena storage is unavailable (it failed to load at startup). Check the console.")

    private fun saveFailed(e: Exception): ArenaResult.Failure {
        plugin.logger.warning("[Event] Failed to save arena: ${e.message}")
        return ArenaResult.Failure("Couldn't save the arena: ${e.message}. Nothing was changed.")
    }

    private companion object {
        const val GROUND_SEARCH = 4
        val DANGEROUS = setOf(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.POWDER_SNOW, Material.COBWEB)
    }
}
