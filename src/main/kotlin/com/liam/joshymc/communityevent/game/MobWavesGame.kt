package com.liam.joshymc.communityevent.game

import com.liam.joshymc.communityevent.EventResult
import com.liam.joshymc.communityevent.EventSession
import com.liam.joshymc.communityevent.EventType
import com.liam.joshymc.communityevent.LeaveReason
import com.liam.joshymc.communityevent.arena.ArenaField
import com.liam.joshymc.communityevent.arena.ArenaRegion
import com.liam.joshymc.communityevent.arena.EventArena
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.block.BlockFace
import org.bukkit.entity.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Vindicator
import org.bukkit.entity.WitherSkeleton
import org.bukkit.entity.Zombie
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.time.Duration
import java.util.UUID

/** A Mob Waves participant's state (issue #1192). */
enum class Survival(val label: String, val color: NamedTextColor) {
    ALIVE("Alive", NamedTextColor.GREEN),
    /** Eliminated and watching from the arena lobby. */
    SPECTATING("Spectating", NamedTextColor.GRAY),
    /** Eliminated and gone (left the event or the event world). */
    ELIMINATED("Eliminated", NamedTextColor.RED),
    DISCONNECTED("Disconnected", NamedTextColor.DARK_GRAY),
}

/**
 * Hosted Mob Waves (issue #1192): every participant fights the configured waves together.
 * `/event start` teleports them to the arena's Player Spawn, hands out the saved kit
 * (`/event mobs setkit`, the same kit storage Crystal PvP uses) and counts down; then wave 1
 * spawns. A wave ends when every one of its mobs is dead or gone; an intermission follows
 * (staff can skip it with `/event mobs next`), then the next wave. Clearing the last wave with
 * anyone alive is a victory; everyone eliminated is a defeat. Either way the event then ends
 * through the normal stop. No rewards are given.
 *
 * Mobs: spawned only at the arena's Mob Spawn Points, only on a loaded chunk, only where
 * there's room, never next to a living player, in staggered batches under a live-mob cap.
 * Each carries `joshymc:event_mob` (the session id) and a `joshymc_event_mob` scoreboard tag,
 * isn't saved with its chunk, and is tracked by UUID in [MobWaveRun]; only tracked mobs count
 * toward a wave, and cleanup removes only them. Health/damage/speed changes are made on each
 * spawned mob alone.
 *
 * Players: the session's registration is the roster; [round] holds who is alive (its ACTIVE
 * state spans every wave and intermission). Damage that would kill a participant eliminates
 * them instead, so nobody dies or drops anything in the event world; they're stripped of the
 * kit and moved to the lobby to spectate, and can't rejoin this event's combat.
 */
class MobWavesGame(games: HostedGames) : MinigameGame(games, EventType.MOBS) {

    private var settings = MobWavesSettings.read(null)
    private var waves: List<WaveDef> = emptyList()
    var run: MobWaveRun? = null
        private set
    private val status = LinkedHashMap<UUID, Survival>()

    private var tickCount = 0
    private var ticksSinceBatch = 0
    private var intermissionLeft = 0
    /** The id written on this run's mobs; null when no run is live. */
    private var sessionTag: String? = null
    private var skippedNoticeWave = 0
    private var blockedNoticeWave = 0

    private var bar: BossBar? = null
    private val barViewers = HashSet<UUID>()

    private val mobKey = NamespacedKey(games.plugin, "event_mob")

    fun readSettings(): MobWavesSettings = MobWavesSettings.read(plugin.config.getConfigurationSection("hosted-events"))

    val isPaused: Boolean get() = run?.paused == true

    override fun startProblem(session: EventSession, arena: EventArena): String? {
        val s = readSettings()
        if (!s.isValid) {
            return "The Mob Waves config has ${s.problems.size} problem(s), first: ${s.problems.firstOrNull() ?: "no waves"}. See /event mobs status."
        }
        val spawn = arena.point(ArenaField.SPAWN) ?: return "Arena '${arena.displayName}' has no Player Spawn. Set it with /event mobs setplayers."
        val points = arena.points(ArenaField.MOB_SPAWNS)
        if (points.isEmpty()) return "Arena '${arena.displayName}' has no Mob Spawn Points. Add some with /event mobs setspawn."
        val region = arena.region(ArenaField.REGION) ?: return "Arena '${arena.displayName}' has no Arena Boundary. Set it with /event mobs setregion."
        if (!region.contains(spawn)) return "The Player Spawn of '${arena.displayName}' is outside its Arena Boundary."
        points.forEachIndexed { i, p -> if (!region.contains(p)) return "Mob Spawn Point #${i + 1} of '${arena.displayName}' is outside its Arena Boundary." }
        for (wave in s.playable) {
            wave.spawnPoints.firstOrNull { it > points.size }?.let {
                return "${wave.title()} uses Mob Spawn Point #$it, but '${arena.displayName}' only has ${points.size}."
            }
        }
        if (!games.mobsKit.available) return "Mob Waves kit storage failed to load at startup; check the console."
        if (games.mobsKit.isEmpty) return "No Mob Waves kit is saved. Build it in your event inventory and run /event mobs setkit."
        if (session.participantCount < s.minPlayers) return "Mob Waves needs at least ${s.minPlayers} registered player(s) (${session.participantCount} registered)."
        return null
    }

    override fun begin(session: EventSession) {
        settings = readSettings()
        if (!settings.isValid) error("the Mob Waves config has problems: ${settings.problems.firstOrNull() ?: "no waves"}")
        waves = settings.playable
        val spawn = playerSpawn() ?: error("The arena has no Player Spawn")
        val entrants = ArrayList<UUID>()
        for (player in gatherPlayers(session)) {
            if (bringIn(player, spawn)) entrants += player.uniqueId
            else comms.send(player, Component.text("You couldn't be teleported to Mob Waves.", NamedTextColor.RED), CommunicationsManager.Category.EVENT)
        }
        if (entrants.size < settings.minPlayers) {
            error("only ${entrants.size} registered player(s) could be brought to the arena; Mob Waves needs ${settings.minPlayers}")
        }
        sessionTag = session.id.toString()
        val r = MobWaveRun(waves.size)
        run = r
        entrants.forEach { status[it] = Survival.ALIVE }
        round.begin(entrants)
        showBar()
        tell(Component.text("Mob Waves: ${entrants.size} player(s) against ${waves.size} wave(s). Survive them all!", NamedTextColor.GOLD))

        val kit = games.mobsKit.items()
        val equipped = HashSet<UUID>()
        startCountdown(
            settings.countdownSeconds,
            onTick = {
                for (uuid in round.active) {
                    if (uuid in equipped) continue
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    if (applyKit(p, kit, GameMode.ADVENTURE)) equipped += uuid
                }
            },
            onGo = { onGo(equipped) },
        )
        // One light task for spawning, the intermission clock and mob upkeep; tied to the session.
        session.track(Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, TICK_PERIOD, TICK_PERIOD))
        games.changed()
    }

    private fun onGo(equipped: Set<UUID>) {
        round.active.filter { it !in equipped }.forEach { eliminate(it, "wasn't ready in time", Survival.ELIMINATED) }
        if (round.active.isEmpty()) { queueEvaluate(); return }
        startWave(1)?.let { tellStaff(Component.text("Couldn't start wave 1: $it", NamedTextColor.RED)) }
    }

    // ── Waves ─────────────────────────────────────────────

    private fun currentWave(): WaveDef? = run?.let { waves.getOrNull(it.wave - 1) }

    /** Starts wave [number]. Returns why it couldn't, or null. [MobWaveRun] refuses duplicates. */
    private fun startWave(number: Int): String? {
        val r = run ?: return "Mob Waves isn't running."
        val def = waves.getOrNull(number - 1) ?: return "There is no wave $number."
        val regular = ArrayList<PendingSpawn>()
        val bosses = ArrayList<PendingSpawn>()
        def.groups.forEachIndexed { i, g -> repeat(g.count) { (if (g.boss) bosses else regular) += PendingSpawn(i) } }
        // Regular mobs in a random mix; bosses arrive last.
        if (!r.beginWave(number, regular.shuffled() + bosses)) {
            return when {
                r.paused -> "Wave progression is paused. Resume it first."
                r.phase == WavePhase.ACTIVE -> "Wave ${r.wave} is still in progress."
                r.phase.isOver -> "Mob Waves is over."
                else -> "Wave $number can't start now."
            }
        }
        ticksSinceBatch = def.spawnIntervalTicks // first batch on the next tick
        intermissionLeft = 0
        val sub = if (def.hasBoss) "${def.total} mobs. A boss is coming!" else "${def.total} mobs"
        audience().forEach {
            it.showTitle(Title.title(
                Component.text("Wave $number", NamedTextColor.RED).decoration(TextDecoration.BOLD, true),
                Component.text(def.name ?: sub, NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(2), Duration.ofMillis(500)),
            ))
            it.playSound(it.location, Sound.ENTITY_ZOMBIE_AMBIENT, 0.6f, 0.8f)
        }
        tell(Component.text("${def.title()} (${number}/${waves.size}): ", NamedTextColor.RED).append(Component.text(sub, NamedTextColor.YELLOW)))
        updateBar()
        games.changed()
        return null
    }

    /** `/event mobs next` / Start Next Wave: ends the intermission early. */
    fun nextWave(): EventResult {
        val s = session ?: return EventResult.Failure("Mob Waves isn't running. Start the event with /event start.")
        val r = run ?: return EventResult.Failure("Mob Waves isn't running. Start the event with /event start.")
        if (!isRunning) return EventResult.Failure("Mob Waves isn't running.")
        when {
            r.phase == WavePhase.COUNTDOWN -> return EventResult.Failure("Wave 1 starts when the countdown ends.")
            r.phase == WavePhase.ACTIVE -> return EventResult.Failure("Wave ${r.wave} is still in progress (${r.remaining} mob(s) left).")
            r.phase.isOver -> return EventResult.Failure("Mob Waves is over. The event ends shortly.")
            r.paused -> return EventResult.Failure("Wave progression is paused. Resume it first.")
        }
        startWave(r.wave + 1)?.let { return EventResult.Failure(it) }
        return EventResult.Success(s, "Wave ${r.wave} started.")
    }

    /** `/event mobs pause`: no spawning, no intermission clock, mobs frozen and unhittable. */
    fun pause(): EventResult {
        val s = session ?: return EventResult.Failure("Mob Waves isn't running.")
        val r = run ?: return EventResult.Failure("Mob Waves isn't running.")
        if (!isRunning) return EventResult.Failure("Mob Waves isn't running.")
        if (r.paused) return EventResult.Failure("Wave progression is already paused.")
        if (!r.pause()) {
            return EventResult.Failure(if (r.phase == WavePhase.COUNTDOWN) "Wait for the countdown to finish." else "Mob Waves is over.")
        }
        forEachMob { it.setAI(false) }
        tell(Component.text("Staff paused the waves.", NamedTextColor.YELLOW))
        updateBar()
        games.changed()
        return EventResult.Success(s, "Wave progression paused.")
    }

    fun resume(): EventResult {
        val s = session ?: return EventResult.Failure("Mob Waves isn't running.")
        val r = run ?: return EventResult.Failure("Mob Waves isn't running.")
        if (!isRunning) return EventResult.Failure("Mob Waves isn't running.")
        if (!r.resume()) return EventResult.Failure(if (r.phase.isOver) "Mob Waves is over." else "Wave progression isn't paused.")
        forEachMob { it.setAI(true) }
        tell(Component.text("The waves continue!", NamedTextColor.GREEN))
        updateBar()
        games.changed()
        queueEvaluate() // a wave cleared while paused completes now
        return EventResult.Success(s, "Wave progression resumed.")
    }

    // ── Ticking ───────────────────────────────────────────

    private fun tick() {
        if (!isRunning) return
        val r = run ?: return
        tickCount++
        val second = tickCount % (20 / TICK_PERIOD.toInt()) == 0
        when (r.phase) {
            WavePhase.ACTIVE -> {
                spawnStep(r)
                if (second) upkeep(r)
            }
            WavePhase.INTERMISSION -> if (second && !r.paused) intermissionStep(r)
            else -> {}
        }
    }

    private fun spawnStep(r: MobWaveRun) {
        val def = currentWave() ?: return
        if (r.paused || r.pendingSpawns == 0) return
        ticksSinceBatch += TICK_PERIOD.toInt()
        if (ticksSinceBatch < def.spawnIntervalTicks) return
        val batch = r.nextBatch(def.batchSize, settings.maxActiveMobs)
        if (batch.isEmpty()) return
        ticksSinceBatch = 0
        val points = spawnPointsFor(def)
        val players = alivePlayers()
        var gaveUp = 0
        for (entry in batch) {
            val group = def.groups[entry.group]
            val mob = pickSpawn(points, players)?.let { spawnMob(it, def, group) }
            if (mob == null) {
                if (r.retry(entry)) gaveUp++
                continue
            }
            if (!r.spawned(mob.uniqueId)) mob.remove()
        }
        if (gaveUp > 0 && skippedNoticeWave != r.wave) {
            skippedNoticeWave = r.wave
            tellStaff(Component.text("Wave ${r.wave}: some mobs never found a safe spawn spot (blocked, unloaded or players standing on every point) and were skipped.", NamedTextColor.YELLOW))
        }
        updateBar()
        if (r.isCleared) queueEvaluate()
    }

    /** Once a second during a wave: forget mobs that vanished, pull escapees back, point idle mobs at a living player. */
    private fun upkeep(r: MobWaveRun) {
        val region = boundary() ?: return
        val def = currentWave()
        val players = alivePlayers()
        var changed = false
        for (uuid in r.trackedMobs) {
            val mob = Bukkit.getEntity(uuid) as? Mob
            if (mob == null || !mob.isValid || mob.isDead) {
                if (r.resolve(uuid, killed = false)) changed = true
                continue
            }
            if (!inside(region, mob.location, 1)) {
                val back = def?.let { pickSpawn(spawnPointsFor(it), emptyList()) } ?: playerSpawn()
                if (back != null) mob.teleport(back)
            }
            if (r.paused) continue
            val target = mob.target
            if (target == null || (target is Player && !isCompeting(target.uniqueId))) {
                mob.target = players.minByOrNull { it.location.distanceSquared(mob.location) }
            }
        }
        if (changed) { updateBar(); queueEvaluate() }
    }

    private fun intermissionStep(r: MobWaveRun) {
        if (intermissionLeft > 0) {
            val next = r.wave + 1
            alivePlayers().forEach {
                comms.sendActionBar(it, Component.text("Wave $next starts in ${intermissionLeft}s", NamedTextColor.YELLOW))
                if (intermissionLeft <= 3) it.playSound(it.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.0f)
            }
            intermissionLeft--
            updateBar()
            return
        }
        startWave(r.wave + 1)?.let { tellStaff(Component.text("Couldn't start wave ${r.wave + 1}: $it", NamedTextColor.RED)) }
    }

    // ── Spawning ──────────────────────────────────────────

    private fun spawnPointsFor(def: WaveDef): List<Location> {
        val all = arena?.points(ArenaField.MOB_SPAWNS) ?: return emptyList()
        val picked = if (def.spawnPoints.isEmpty()) all else def.spawnPoints.mapNotNull { all.getOrNull(it - 1) }
        return picked.mapNotNull { toLocation(it) }
    }

    /** A random spawn point that's safe right now, or null. */
    private fun pickSpawn(points: List<Location>, players: List<Player>): Location? {
        val minSq = settings.minPlayerDistance * settings.minPlayerDistance
        return points.shuffled().firstOrNull { isSafe(it, players, minSq) }?.clone()
    }

    /**
     * Inside the boundary and the world's height, on a chunk that is already loaded (nothing is
     * force-loaded), with room for a mob's feet and head, not in lava, and not next to a player.
     */
    private fun isSafe(loc: Location, players: List<Player>, minSq: Double): Boolean {
        val w = loc.world ?: return false
        val region = boundary() ?: return false
        if (!inside(region, loc, 0)) return false
        if (loc.blockY <= w.minHeight || loc.blockY >= w.maxHeight - 2) return false
        if (!w.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)) return false
        val feet = w.getBlockAt(loc.blockX, loc.blockY, loc.blockZ)
        val head = feet.getRelative(BlockFace.UP)
        if (!feet.isPassable || !head.isPassable || feet.type == Material.LAVA || head.type == Material.LAVA) return false
        return players.none { it.world == w && it.location.distanceSquared(loc) < minSq }
    }

    private fun spawnMob(loc: Location, def: WaveDef, group: MobGroup): Mob? {
        val tag = sessionTag ?: return null
        @Suppress("UNCHECKED_CAST")
        val cls = group.type.entityClass as? Class<Mob> ?: return null
        val mob = try {
            loc.world.spawn(loc, cls) { configure(it, def, group, tag) }
        } catch (e: Exception) {
            plugin.logger.warning("[Event] Mob Waves couldn't spawn ${group.type.key.key}: ${e.message}")
            return null
        }
        if (!mob.isValid) {
            // Another plugin (a region's mob-spawning rule, say) cancelled the spawn.
            if (blockedNoticeWave != run?.wave) {
                blockedNoticeWave = run?.wave ?: 0
                tellStaff(Component.text("Another plugin refused to spawn Mob Waves mobs in the arena (a region mob-spawning rule?). Those mobs are retried, then skipped.", NamedTextColor.RED))
            }
            return null
        }
        // Jockeys and riders aren't part of the wave.
        mob.passengers.filter { !isTagged(it) }.forEach { mob.removePassenger(it); it.remove() }
        mob.vehicle?.takeIf { !isTagged(it) }?.let { mob.leaveVehicle(); it.remove() }
        return mob
    }

    /** Runs before the mob is added to the world, so every listener already sees it as an event mob. */
    private fun configure(mob: Mob, def: WaveDef, group: MobGroup, tag: String) {
        mob.persistentDataContainer.set(mobKey, PersistentDataType.STRING, tag)
        mob.addScoreboardTag(EVENT_MOB_TAG)
        mob.isPersistent = false
        mob.removeWhenFarAway = false
        mob.canPickupItems = false
        (mob as? Ageable)?.setAdult()
        val gear = group.gear ?: def.gear
        mob.equipment.let { eq ->
            if (gear != MobGear.NONE) {
                eq.setBoots(ItemStack(gear.armor[0]))
                eq.setLeggings(ItemStack(gear.armor[1]))
                eq.setChestplate(ItemStack(gear.armor[2]))
                eq.setHelmet(ItemStack(gear.armor[3]))
                if (gear.weapon != null && (mob is Zombie || mob is WitherSkeleton || mob is Vindicator)) eq.setItemInMainHand(ItemStack(gear.weapon))
            }
            eq.helmetDropChance = 0f
            eq.chestplateDropChance = 0f
            eq.leggingsDropChance = 0f
            eq.bootsDropChance = 0f
            eq.itemInMainHandDropChance = 0f
            eq.itemInOffHandDropChance = 0f
        }
        scale(mob, Attribute.MAX_HEALTH, settings.healthFor(def, group))
        mob.getAttribute(Attribute.MAX_HEALTH)?.value?.let { mob.health = it }
        scale(mob, Attribute.ATTACK_DAMAGE, settings.damageFor(def, group))
        scale(mob, Attribute.MOVEMENT_SPEED, def.speedMultiplier)
        if (group.boss || group.name != null) {
            mob.customName(Component.text(group.name ?: "Boss", if (group.boss) NamedTextColor.DARK_RED else NamedTextColor.RED).decoration(TextDecoration.BOLD, group.boss))
            mob.isCustomNameVisible = true
        }
        if (group.boss) mob.isGlowing = true
    }

    /** Scales one attribute of this mob only (its own instance, never a shared default). */
    private fun scale(mob: Mob, attribute: Attribute, factor: Double) {
        if (factor == 1.0) return
        mob.getAttribute(attribute)?.let { it.baseValue = it.baseValue * factor }
    }

    private fun forEachMob(action: (Mob) -> Unit) {
        run?.trackedMobs?.forEach { uuid -> (Bukkit.getEntity(uuid) as? Mob)?.let(action) }
    }

    /** Removes this run's mobs, and nothing else. */
    private fun removeMobs() {
        val r = run ?: return
        for (uuid in r.releaseAll()) Bukkit.getEntity(uuid)?.remove()
    }

    // ── Rules used by the listeners ───────────────────────

    /** A mob of the live run. In-memory, so it's cheap enough for every damage/target event. */
    fun isEventMob(entity: Entity?): Boolean = entity != null && isRunning && run?.isTracked(entity.uniqueId) == true

    /** Carries the event-mob tag at all (any session, including a stale one). */
    fun isTagged(entity: Entity): Boolean = entity.persistentDataContainer.has(mobKey, PersistentDataType.STRING)

    /** A tagged mob that doesn't belong to the live run: left over from a crash or an earlier event. */
    fun isStale(entity: Entity): Boolean = isTagged(entity) && !isEventMob(entity)

    val dropsItems: Boolean get() = settings.dropItems

    fun boundary(): ArenaRegion? = arena?.region(ArenaField.REGION)

    fun inArena(loc: Location, margin: Int = 0): Boolean = boundary()?.let { inside(it, loc, margin) } == true

    private fun inside(r: ArenaRegion, loc: Location, margin: Int): Boolean =
        loc.world?.name == r.world &&
            loc.blockX in (r.minX - margin)..(r.maxX + margin) &&
            loc.blockY in (r.minY - margin)..(r.maxY + margin) &&
            loc.blockZ in (r.minZ - margin)..(r.maxZ + margin)

    /** An event mob died. */
    fun mobDied(uuid: UUID) {
        val r = run ?: return
        if (r.resolve(uuid, killed = true)) { updateBar(); queueEvaluate() }
    }

    /** An event mob left the world some other way (unloaded, removed by a command, ...). */
    fun mobRemoved(uuid: UUID) {
        val r = run ?: return
        if (r.resolve(uuid, killed = false)) { updateBar(); queueEvaluate() }
    }

    override fun onActiveMove(player: Player, to: Location) {
        val r = boundary() ?: return
        if (!inside(r, to, 1)) sendBack(player)
    }

    /** A participant who left the arena (or fell into the void) goes back to the Player Spawn. */
    fun sendBack(player: Player) {
        comms.send(player, Component.text("Stay inside the arena!", NamedTextColor.RED), CommunicationsManager.Category.EVENT)
        teleportSafely(player, playerSpawn())
    }

    /** Damage that would have killed [player]: they're eliminated instead of dying. */
    fun lethal(player: Player) {
        if (!isCompeting(player.uniqueId)) return
        player.world.playSound(player.location, Sound.ENTITY_PLAYER_DEATH, 0.8f, 1.0f)
        eliminate(player.uniqueId, "was overwhelmed", Survival.SPECTATING)
    }

    override fun knockOut(uuid: UUID, reason: String) = eliminate(uuid, reason, Survival.SPECTATING)

    // ── Eliminations ──────────────────────────────────────

    /** Takes [uuid] out of the fight for the rest of the event (once; repeats are ignored). */
    private fun eliminate(uuid: UUID, reason: String, state: Survival) {
        if (!round.eliminate(uuid)) return
        val player = Bukkit.getPlayer(uuid)
        val watching = player != null && games.inventorySaved(player)
        status[uuid] = if (state == Survival.SPECTATING && !watching) Survival.ELIMINATED else state
        if (player != null && watching) {
            // The kit goes at once; the move to the lobby waits a tick so it never runs inside a damage event.
            games.gear.strip(player)
            recover(player)
            session?.track(Bukkit.getScheduler().runTask(plugin, Runnable { if (isRunning) sendToWaiting(player) }))
            comms.send(player, Component.text("You were eliminated ($reason). You're spectating for the rest of this event.", NamedTextColor.RED), CommunicationsManager.Category.EVENT)
        }
        tell(Component.text("${nameOf(uuid)} $reason. ${round.active.size} player(s) still alive.", NamedTextColor.GRAY))
        updateBar()
        games.changed()
        queueEvaluate()
    }

    private fun recover(player: Player) {
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.fireTicks = 0
        player.fallDistance = 0f
        player.getAttribute(Attribute.MAX_HEALTH)?.value?.let { player.health = it }
        player.foodLevel = 20
        player.saturation = 20f
    }

    override fun withdraw(uuid: UUID, reason: LeaveReason) {
        val state = if (reason == LeaveReason.QUIT) Survival.DISCONNECTED else Survival.ELIMINATED
        if (round.isAlive(uuid)) eliminate(uuid, reason.label, state)
        else if (status[uuid] == Survival.SPECTATING) status[uuid] = state
        Bukkit.getPlayer(uuid)?.let { p -> bar?.let { p.hideBossBar(it) } }
        barViewers.remove(uuid)
    }

    override fun evaluate() {
        val r = run ?: return
        if (r.phase.isOver) return
        if (round.active.isEmpty()) { defeat(r); return }
        if (r.paused || !r.isCleared) return
        when (r.completeWave()) {
            WavePhase.INTERMISSION -> intermission(r)
            WavePhase.VICTORY -> victory(r)
            else -> {}
        }
    }

    private fun intermission(r: MobWaveRun) {
        intermissionLeft = settings.intermissionSeconds
        val next = waves.getOrNull(r.wave)
        val alive = alivePlayers()
        if (settings.healBetweenWaves) alive.forEach { recover(it) }
        tell(
            Component.text("Wave ${r.wave}/${r.totalWaves} cleared! ", NamedTextColor.GREEN)
                .append(Component.text("${round.active.size} player(s) alive. ${next?.title() ?: "The next wave"} starts in ${intermissionLeft}s.", NamedTextColor.YELLOW))
        )
        alive.forEach {
            it.showTitle(Title.title(
                Component.text("Wave ${r.wave} cleared!", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true),
                Component.text("Next wave in ${intermissionLeft}s", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(2), Duration.ofMillis(500)),
            ))
            it.playSound(it.location, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f)
        }
        tellStaff(Component.text("Start it early with /event mobs next, or hold it with /event mobs pause.", NamedTextColor.GRAY))
        updateBar()
        games.changed()
    }

    private fun victory(r: MobWaveRun) {
        val survivors = round.active
        round.complete(survivors.singleOrNull())
        removeMobs()
        comms.broadcast(
            Component.text("Mob Waves: the players survived all ${r.totalWaves} waves! ", NamedTextColor.GOLD)
                .append(Component.text("Survivors: ${survivors.joinToString(", ") { nameOf(it) }}", NamedTextColor.YELLOW)),
            CommunicationsManager.Category.EVENT,
        )
        winnerTitle(audience(), "Victory!", "All ${r.totalWaves} waves cleared")
        updateBar()
        games.changed()
        finishEventSoon()
    }

    private fun defeat(r: MobWaveRun) {
        if (!r.defeat()) return
        round.complete(null)
        removeMobs()
        val reached = if (r.wave == 0) "before the first wave" else "on wave ${r.wave}/${r.totalWaves}"
        comms.broadcast(Component.text("Mob Waves: every player fell $reached.", NamedTextColor.RED), CommunicationsManager.Category.EVENT)
        audience().forEach {
            it.showTitle(Title.title(
                Component.text("Defeat", NamedTextColor.RED).decoration(TextDecoration.BOLD, true),
                Component.text("Reached wave ${r.wave}", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(600)),
            ))
        }
        updateBar()
        games.changed()
        finishEventSoon()
    }

    // ── Boss bar ──────────────────────────────────────────

    private fun showBar() {
        val b = BossBar.bossBar(Component.text("Mob Waves", NamedTextColor.RED), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS)
        bar = b
        audience().forEach { it.showBossBar(b); barViewers += it.uniqueId }
        updateBar()
    }

    private fun updateBar() {
        val b = bar ?: return
        val r = run ?: return
        val (text, progress) = when (r.phase) {
            WavePhase.COUNTDOWN -> "Mob Waves is starting" to 1f
            WavePhase.ACTIVE -> "Wave ${r.wave}/${r.totalWaves}: ${r.remaining} mob(s) left" to
                (if (r.waveSize > 0) r.remaining.toFloat() / r.waveSize else 0f)
            WavePhase.INTERMISSION -> "Wave ${r.wave + 1}/${r.totalWaves} in ${intermissionLeft}s" to
                (intermissionLeft.toFloat() / settings.intermissionSeconds.coerceAtLeast(1))
            WavePhase.VICTORY -> "Victory! All ${r.totalWaves} waves cleared" to 1f
            WavePhase.DEFEAT -> "Defeated on wave ${r.wave}" to 0f
        }
        val name = if (r.paused) "$text (paused)" else text
        b.name(Component.text(name, if (r.phase == WavePhase.INTERMISSION || r.phase == WavePhase.VICTORY) NamedTextColor.GREEN else NamedTextColor.RED))
        b.color(if (r.phase == WavePhase.INTERMISSION || r.phase == WavePhase.VICTORY) BossBar.Color.GREEN else BossBar.Color.RED)
        b.progress(progress.coerceIn(0f, 1f))
    }

    private fun hideBar() {
        val b = bar ?: return
        barViewers.forEach { Bukkit.getPlayer(it)?.hideBossBar(b) }
        barViewers.clear()
        bar = null
    }

    // ── Kit ───────────────────────────────────────────────

    /** `/event mobs setkit`: saves the host's event inventory as the Mob Waves kit; never while they're fighting. */
    fun saveKit(player: Player): KitSave =
        copyKit(player, games.mobsKit, if (isCompeting(player.uniqueId)) "You're fighting in Mob Waves. Save the kit when you're out." else null)

    // ── Lifecycle ─────────────────────────────────────────

    override fun afterStop() {
        removeMobs()
        hideBar()
    }

    override fun clearRun() {
        run = null
        waves = emptyList()
        status.clear()
        tickCount = 0
        ticksSinceBatch = 0
        intermissionLeft = 0
        sessionTag = null
        skippedNoticeWave = 0
        blockedNoticeWave = 0
    }

    /** Startup: no event survives a restart, so every tagged mob still in [world] is stale. */
    fun sweepStale(world: World): Int {
        var removed = 0
        for (mob in world.getEntitiesByClass(Mob::class.java)) {
            if (isStale(mob)) { mob.remove(); removed++ }
        }
        return removed
    }

    // ── Helpers / status ──────────────────────────────────

    private fun playerSpawn(): Location? = arena?.point(ArenaField.SPAWN)?.let { toLocation(it) }

    private fun alivePlayers(): List<Player> = round.active.mapNotNull { Bukkit.getPlayer(it) }.filter { isPlaying(it) }

    fun survivors(): List<UUID> = round.active

    fun spectators(): List<UUID> = status.filterValues { it != Survival.ALIVE }.keys.toList()

    fun survivalOf(uuid: UUID): Survival? = status[uuid]

    override fun statusLines(): List<Component> {
        val r = run ?: return listOf(Component.text("Mob Waves isn't running.", NamedTextColor.GRAY))
        val def = currentWave()
        val lines = mutableListOf(
            line("Wave", if (r.wave == 0) "not started (0/${r.totalWaves})" else "${r.wave}/${r.totalWaves}${def?.name?.let { " ($it)" } ?: ""}"),
            line("Phase", r.phase.label + if (r.paused) " (paused)" else "", if (r.paused) NamedTextColor.YELLOW else NamedTextColor.WHITE),
        )
        if (r.phase == WavePhase.ACTIVE) {
            lines += line("Remaining mobs", "${r.remaining} (${r.activeMobs} alive, ${r.pendingSpawns} to spawn)")
            lines += line("Killed this wave", "${r.killed}/${r.waveSize}" + if (r.skipped > 0) ", ${r.skipped} skipped" else "")
        }
        if (r.phase == WavePhase.INTERMISSION) lines += line("Next wave in", "${intermissionLeft}s")
        lines += line("Surviving players", "${round.active.size}: ${round.active.joinToString(", ") { nameOf(it) }.ifEmpty { "none" }}", NamedTextColor.GREEN)
        val out = spectators()
        if (out.isNotEmpty()) lines += line("Eliminated", out.joinToString(", ") { "${nameOf(it)} (${status[it]?.label?.lowercase()})" }, NamedTextColor.RED)
        lines += line("Mobs killed", r.totalKilled.toString())
        return lines
    }

    companion object {
        /** Scoreboard tag on every event mob: other systems (mob stacking, quests) skip anything carrying it. */
        const val EVENT_MOB_TAG = "joshymc_event_mob"
        private const val TICK_PERIOD = 5L
    }
}
