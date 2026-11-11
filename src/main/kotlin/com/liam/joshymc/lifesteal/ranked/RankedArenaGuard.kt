package com.liam.joshymc.lifesteal.ranked

/**
 * Issue #1507 (audit finding F-6): the Bukkit-free rules that keep the world from changing inside
 * a Ranked Team Wars arena while it is in use. [com.liam.joshymc.lifesteal.ranked.RankedArenaGuardListener]
 * feeds it block coordinates from events; nothing here touches a server.
 *
 * A **zone** is the stored arena boundary ([RankedArena.bounds], the only protected area) of an
 * arena that is in use right now: held by a match that hasn't been released yet (PREPARING,
 * COUNTDOWN, ACTIVE, FINISHING, until the cleanup removes it), or reserved by a queue proposal
 * waiting for its players to accept. The zone list is rebuilt from that in-memory state, so a
 * restart, a cancel or a completed match never leaves an arena protected, and idle arenas are
 * left to WorldGuard (staff maintenance between matches is untouched).
 *
 * The rules only ever **refuse** a change; they never allow something another plugin (WorldGuard,
 * claims) refused.
 */
object RankedArenaGuard {

    /** An arena in use. [matchId] is null while only a queue proposal reserves it. */
    data class Zone(val arenaId: String, val matchId: String?, val world: String, val box: Box) {
        fun contains(world: String, x: Int, y: Int, z: Int): Boolean =
            world == this.world && x >= box.minX && x <= box.maxX && y >= box.minY && y <= box.maxY && z >= box.minZ && z <= box.maxZ
    }

    /** A block position. */
    data class Pos(val x: Int, val y: Int, val z: Int)

    /** Who is behind a fire, for [ignitionRefused]. */
    enum class Actor {
        /** A participant of the match using this zone (their own fight). */
        PARTICIPANT,
        /** Anybody else: other players, spectating staff, players of another match. */
        OUTSIDER,
        /** No player behind it (lightning, a dispenser, a mob). */
        NONE,
    }

    /**
     * The zones in use: one per match still in [matches] (with a boundary), plus arenas only a
     * queue proposal reserves. A match's arena wins over a reservation of the same arena.
     */
    fun zones(matches: Collection<Pair<String, RankedArena>>, reserved: Collection<RankedArena>): List<Zone> {
        val out = ArrayList<Zone>(matches.size + reserved.size)
        val seen = HashSet<String>()
        for ((matchId, arena) in matches) {
            val box = arena.bounds ?: continue
            if (seen.add(arena.id)) out += Zone(arena.id, matchId, arena.world, box)
        }
        for (arena in reserved) {
            val box = arena.bounds ?: continue
            if (seen.add(arena.id)) out += Zone(arena.id, null, arena.world, box)
        }
        return out
    }

    /** The zone that contains the block, or null. Enabled arenas never overlap, so there is at most one. */
    fun zoneAt(zones: List<Zone>, world: String, x: Int, y: Int, z: Int): Zone? {
        for (zone in zones) if (zone.contains(world, x, y, z)) return zone
        return null
    }

    fun zoneAt(zones: List<Zone>, world: String, p: Pos): Zone? = zoneAt(zones, world, p.x, p.y, p.z)

    /**
     * Something at [from] changes the block at [to] (fluid flowing, fire or vines spreading, a
     * dispenser, a sapling growing, a sponge drying): refused when [to] is in a zone that [from]
     * isn't in. Fluids and fire already inside an arena keep behaving normally, and nothing
     * outside any zone is ever refused.
     */
    fun crossesInto(zones: List<Zone>, world: String, from: Pos?, to: Pos): Boolean {
        if (zones.isEmpty()) return false
        val target = zoneAt(zones, world, to) ?: return false
        if (from == null) return true
        return !target.contains(world, from.x, from.y, from.z)
    }

    /**
     * A piston move: [piston] itself, its head ([head]), every moved block ([moved]) and where
     * each one ends up (`moved + direction`). Refused unless every one of those blocks is in the
     * same zone, or none is in any zone. Covers normal and sticky pistons, extension and
     * retraction, and slime/honey blocks (Bukkit lists every block they drag along in [moved]).
     */
    fun pistonRefused(zones: List<Zone>, world: String, piston: Pos, head: Pos, moved: List<Pos>, dx: Int, dy: Int, dz: Int): Boolean {
        if (zones.isEmpty()) return false
        val pistonZone = zoneAt(zones, world, piston)
        fun differs(p: Pos): Boolean = zoneAt(zones, world, p) != pistonZone
        if (differs(head)) return true
        for (m in moved) {
            if (differs(m)) return true
            if (differs(Pos(m.x + dx, m.y + dy, m.z + dz))) return true
        }
        return false
    }

    /**
     * A player (or something they did) changes a block inside a zone: placing, breaking, a
     * bucket, using a block, a sign, hanging entities, armor stands. Arenas never change while in
     * use, so this is refused for everyone; participants were already refused by the match rules.
     */
    fun playerChangeRefused(zones: List<Zone>, world: String, block: Pos): Boolean = zoneAt(zones, world, block) != null

    /**
     * A block inside [zone] catches fire. Allowed when the fire comes from the same zone
     * ([sourceInZone]: fire or lava already inside the arena), from the match's own participants
     * (flint and steel, flaming arrows: unchanged combat), or from an End Crystal ([crystal]);
     * refused from outsiders and from unattributed sources outside the arena (lightning, a
     * dispenser, fire spreading in).
     */
    fun ignitionRefused(actor: Actor, sourceInZone: Boolean, crystal: Boolean): Boolean = when {
        sourceInZone -> false
        crystal -> false
        actor == Actor.PARTICIPANT -> false
        else -> true
    }

    /**
     * Damage to a participant of an ACTIVE match that no player is behind ([cause] is the Bukkit
     * damage cause name): explosions that aren't an End Crystal (TNT cannons, dispensed TNT),
     * dispenser projectiles fired from outside the arena, and falling blocks dropped from outside
     * it. Participants can't place blocks or TNT, so inside a running arena those only ever come
     * from outside. Everything else (fall damage, lava already in the arena, the other side's
     * hits) is left to the match rules.
     */
    fun outsideHazard(cause: String, crystal: Boolean, sourceOutsideArena: Boolean): Boolean = when (cause) {
        "ENTITY_EXPLOSION", "BLOCK_EXPLOSION" -> !crystal
        "PROJECTILE", "FALLING_BLOCK" -> sourceOutsideArena
        else -> false
    }
}
