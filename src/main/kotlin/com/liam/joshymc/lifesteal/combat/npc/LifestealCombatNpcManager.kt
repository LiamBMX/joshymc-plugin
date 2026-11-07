package com.liam.joshymc.lifesteal.combat.npc

import com.destroystokyo.paper.profile.ProfileProperty
import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.combat.npc.CombatNpcRules.JoinAction
import com.liam.joshymc.lifesteal.combat.npc.CombatNpcRules.Outcome
import com.liam.joshymc.lifesteal.combat.npc.CombatNpcRules.StartupAction
import com.liam.joshymc.lifesteal.combat.npc.CombatNpcRules.State
import com.liam.joshymc.lifesteal.teams.TeamCombatRules
import com.liam.joshymc.manager.CommunicationsManager
import io.papermc.paper.datacomponent.item.ResolvableProfile
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mannequin
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import java.util.Base64
import java.util.UUID

/**
 * The Lifesteal Combat NPC (issue #1402). Lifesteal only: nothing here runs unless this server hosts
 * Lifesteal, and an NPC is only ever left for a player who disconnects while combat-tagged in a
 * Lifesteal world (CombatListener asks [takeOver] before the old Survival villager NPC).
 *
 * **One copy of the items, always.** On the tagged quit the player's whole inventory (41 slots via
 * `serializeAsBytes`), health, potion effects and XP go into one `lifesteal_combat_npcs` row
 * ([CombatNpcStore]); only after that row is committed is the player's own inventory emptied, in
 * the same quit handler, before Paper saves their data file. The NPC entity wears display copies
 * (drop chance 0, drops cleared on death) and is non-persistent, so it is never saved with the
 * chunk: a restart can't leave a ghost or a second set of items, and the row is the only truth.
 * Every hand-back *sets* the inventory from the row (never adds), so applying it twice after a
 * crash gives the same items.
 *
 * **Exactly-once transitions.** All state changes are conditional UPDATEs ([CombatNpcRules]) on the
 * main thread, so a death, an expiry and a reconnect in the same tick can't both win. A death is
 * committed (ACTIVE -> DEAD) before anything drops; drops are confirmed right after
 * (`drops_done`); hearts use the existing [com.liam.joshymc.lifesteal.hearts.HeartStore] with the
 * session id as dedupe key. See docs/lifesteal-combat-npc.md for the crash windows that remain.
 *
 * **Player-shaped NPC.** A vanilla `Mannequin` (player model, the owner's skin from their own
 * profile textures, real health, armor, effects, gravity, knockback, fire, drowning), like the
 * command NPCs in NPCManager. No NMS, no new dependency.
 */
class LifestealCombatNpcManager(private val plugin: Joshymc) {

    private val store = CombatNpcStore(plugin.databaseManager)

    /** A standing NPC. Main-thread only. */
    private class Live(
        val sessionId: String,
        val owner: UUID,
        val ownerName: String,
        val expiresAt: Long,
        var entityId: UUID?,
        /** Latest known state (live position/health/effects; inventory as captured). */
        var last: CombatNpcStore.Snapshot,
        /** Chunk holding the plugin chunk ticket: world name + chunk key. */
        var ticket: Pair<String, Long>?,
        /** Set while this session is being ended, so re-entrant events (death -> remove) do nothing. */
        var ending: Boolean = false,
    )

    private val bySession = HashMap<String, Live>()
    private val byEntity = HashMap<UUID, String>()

    /** Settlement writes that failed and are retried each tick (never left for a later join to redo). */
    private val pendingComplete = HashSet<String>()
    private val pendingDropsMark = HashSet<String>()

    private val sessionKey = NamespacedKey(plugin, "combat_npc_session")

    private var tablesReady = false
    @Volatile private var stopping = false
    private var tickTask = -1
    private var tickCount = 0

    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes

    /** Number of NPCs standing right now. */
    val standing: Int get() = bySession.size

    private fun isLifesteal(world: World?): Boolean = plugin.gamemodeLockManager.gamemodeOf(world) == ServerGamemode.LIFESTEAL

    /** The NPC settings, or the defaults while lifesteal.yml can't be read (NPCs already standing keep working). */
    private fun settings(): LifestealSettings.CombatNpc =
        plugin.lifestealCombatManager.settings?.npc ?: LifestealSettings.CombatNpc()

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload, after lifestealCombatManager.refresh(). Recovery runs once per server start. */
    fun refresh() {
        stopping = false
        if (!hosted) return
        if (!tablesReady) {
            try {
                store.createTables()
                tablesReady = true
            } catch (e: Exception) {
                plugin.logger.severe("[CombatNPC] Could not create the Combat NPC table, so Lifesteal Combat NPCs are off: ${e.message}")
                return
            }
            recover()
            // Online players (a /reload): settle their sessions now, like a join.
            for (player in Bukkit.getOnlinePlayers()) onJoin(player)
        }
        if (tickTask == -1) {
            tickTask = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable { tick() }, TICK_PERIOD, TICK_PERIOD)
        }
        val retention = plugin.lifestealCombatManager.settings?.retentionDays
        if (retention != null) {
            val cutoff = System.currentTimeMillis() - retention * DAY_MILLIS
            plugin.databaseManager.async("lifesteal combat NPC retention") {
                val removed = store.purgeCompletedBefore(cutoff)
                if (removed > 0) plugin.logger.info("[CombatNPC] Deleted $removed settled Combat NPC session(s) older than $retention days.")
            }
        }
    }

    /**
     * Shutdown: every standing NPC's current state is saved (still ACTIVE) and the entity removed;
     * the next start spawns it again for the rest of its original lifetime, or settles it as
     * survived when that ran out meanwhile. Nothing is dropped or handed out here.
     */
    fun stop() {
        stopping = true
        if (tickTask != -1) {
            plugin.server.scheduler.cancelTask(tickTask)
            tickTask = -1
        }
        val now = System.currentTimeMillis()
        for (live in bySession.values.toList()) {
            if (live.ending) continue
            val entity = entityOf(live)
            val snapshot = entity?.let { finalSnapshot(it, live, now) } ?: live.last
            try {
                store.saveLive(live.sessionId, snapshot, now)
            } catch (e: Exception) {
                plugin.logger.severe("[CombatNPC] Couldn't save ${live.ownerName}'s Combat NPC at shutdown (${e.message}); its last saved state (at most ${SAVE_EVERY_RUNS * TICK_PERIOD / 20}s old) is used.")
            }
            live.ending = true
            entity?.remove()
            releaseTicket(live)
        }
        bySession.clear()
        byEntity.clear()
        retryPending()
    }

    // ── Quit: the hand-over to the NPC ──────────────────────

    /**
     * CombatListener's tagged quit, after the combat-log rules decided an NPC is left. Returns true
     * when the Lifesteal system handled the quit (then the old villager NPC is never used, even if
     * nothing could be spawned), false outside Lifesteal (Survival keeps the old NPC unchanged).
     */
    fun takeOver(player: Player): Boolean {
        if (!hosted || !tablesReady || stopping) return false
        val combat = plugin.lifestealCombatManager.settings ?: return false
        if (!plugin.lifestealCombatManager.isLifesteal(player)) return false
        // From here on it's Lifesteal's: never the old villager (which kills the player).
        if (!combat.leaveNpc) return true
        if (player.isDead || player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) return true
        // A Ranked Team Wars match restores its own snapshot; its fights never tag anyway.
        if (plugin.lifestealRankedManager.holds(player)) return true

        val now = System.currentTimeMillis()
        val npc = combat.npc
        val snapshot = try {
            capture(player, now)
        } catch (e: Exception) {
            plugin.logger.severe("[CombatNPC] Couldn't capture ${player.name}'s state (${e.message}); no Combat NPC, they keep their items.")
            return true
        } ?: run {
            plugin.logger.severe("[CombatNPC] ${player.name}'s inventory has an unexpected layout; no Combat NPC, they keep their items.")
            return true
        }
        val id = UUID.randomUUID().toString()
        val expiresAt = CombatNpcRules.expiresAt(now, npc.lifetimeSeconds)
        val tagExpiresAt = plugin.combatManager.tagExpiresAt(player.uniqueId)
        val textures = player.playerProfile.properties.firstOrNull { it.name == "textures" }
        val inserted = try {
            store.insertActive(id, player.uniqueId, player.name, now, expiresAt, tagExpiresAt, textures?.value, textures?.signature, snapshot)
        } catch (e: Exception) {
            plugin.logger.severe("[CombatNPC] Couldn't save ${player.name}'s Combat NPC session (${e.message}); no NPC, they keep their items.")
            return true
        }
        if (!inserted) {
            plugin.logger.warning("[CombatNPC] ${player.name} already has an unsettled Combat NPC session; no second NPC (they keep what they have).")
            return true
        }
        // The row holds everything now: empty the player's own copy before Paper writes their data file.
        clearOwnedState(player)
        val live = Live(id, player.uniqueId, player.name, expiresAt, null, snapshot, null)
        bySession[id] = live
        if (!spawn(live, textures?.value, textures?.signature, now)) {
            plugin.logger.warning("[CombatNPC] Couldn't spawn ${player.name}'s Combat NPC; their state is kept as survived.")
            endAlive(live, snapshot, Outcome.REMOVED, now)
        } else {
            plugin.logger.info("[CombatNPC] ${player.name} logged out in combat: Combat NPC ${id.take(8)} stands for ${npc.lifetimeSeconds}s.")
        }
        return true
    }

    private fun capture(player: Player, now: Long): CombatNpcStore.Snapshot? {
        // The server puts the cursor and the 2x2 crafting grid back into the inventory (anything
        // that doesn't fit drops at their feet), so the snapshot holds everything they carry.
        player.closeInventory()
        val contents = player.inventory.contents
        if (contents.size != CombatNpcRules.INVENTORY_SIZE) return null
        val loc = player.location
        return CombatNpcStore.Snapshot(
            location = point(loc),
            health = player.health,
            maxHealthBase = player.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: VANILLA_MAX_HEALTH,
            absorption = player.absorptionAmount,
            fireTicks = player.fireTicks,
            air = player.remainingAir,
            inventory = contents.map { encode(it) },
            heldSlot = player.inventory.heldItemSlot,
            xpLevel = player.level,
            xpProgress = player.exp,
            xpTotal = player.totalExperience,
            effects = player.activePotionEffects.map { effectOf(it) },
            effectsAt = now,
        )
    }

    /** Everything the snapshot took leaves the player (inventory, effects, XP). */
    private fun clearOwnedState(player: Player) {
        player.setItemOnCursor(null)
        player.inventory.contents = arrayOfNulls(player.inventory.contents.size)
        for (effect in player.activePotionEffects.toList()) player.removePotionEffect(effect.type)
        player.level = 0
        player.exp = 0f
        player.totalExperience = 0
    }

    // ── The NPC entity ──────────────────────────────────────

    private fun spawn(live: Live, textures: String?, signature: String?, now: Long): Boolean {
        val s = live.last
        val world = Bukkit.getWorld(s.location.world) ?: return false
        val loc = Location(world, s.location.x, s.location.y, s.location.z, s.location.yaw, s.location.pitch)
        val items = try { decodeAll(s.inventory) } catch (e: Exception) {
            plugin.logger.severe("[CombatNPC] ${live.ownerName}'s saved items can't be read (${e.message}); the NPC isn't spawned and the row is kept.")
            return false
        }
        val npc = settings()
        val chunk = world.getChunkAt(loc)
        chunk.addPluginChunkTicket(plugin)
        live.ticket = world.name to chunk.chunkKey
        val entity = try {
            world.spawn(loc, Mannequin::class.java) { m ->
                // Never saved with the chunk: the database row is the only copy that survives a restart.
                m.isPersistent = false
                m.customName(Component.text(live.ownerName, NamedTextColor.WHITE))
                m.isCustomNameVisible = npc.showUsername
                m.description = plugin.commsManager.parseLegacy(npc.label)
                m.canPickupItems = false
                if (textures != null) {
                    m.profile = ResolvableProfile.resolvableProfile()
                        .name(live.ownerName)
                        .uuid(live.owner)
                        .addProperty(ProfileProperty("textures", textures, signature))
                        .build()
                }
                m.addScoreboardTag(ENTITY_TAG)
                m.persistentDataContainer.set(sessionKey, PersistentDataType.STRING, live.sessionId)
                val eq = m.equipment
                eq.setBoots(items[36])
                eq.setLeggings(items[37])
                eq.setChestplate(items[38])
                eq.setHelmet(items[39])
                eq.setItemInOffHand(items[40])
                eq.setItemInMainHand(items.getOrNull(s.heldSlot))
                eq.setBootsDropChance(0f)
                eq.setLeggingsDropChance(0f)
                eq.setChestplateDropChance(0f)
                eq.setHelmetDropChance(0f)
                eq.setItemInOffHandDropChance(0f)
                eq.setItemInMainHandDropChance(0f)
                m.getAttribute(Attribute.MAX_HEALTH)?.baseValue = s.maxHealthBase
                for (effect in CombatNpcRules.effectsAt(s.effects, s.effectsAt, now)) potionOf(effect)?.let { m.addPotionEffect(it) }
                val max = m.getAttribute(Attribute.MAX_HEALTH)?.value ?: s.maxHealthBase
                m.health = CombatNpcRules.restoredHealth(s.health, max)
                runCatching { m.absorptionAmount = s.absorption.coerceAtLeast(0.0) }
                m.fireTicks = s.fireTicks
                m.remainingAir = s.air
            }
        } catch (e: Exception) {
            plugin.logger.severe("[CombatNPC] Spawning ${live.ownerName}'s Combat NPC failed: ${e.message}")
            releaseTicket(live)
            return false
        }
        live.entityId = entity.uniqueId
        byEntity[entity.uniqueId] = live.sessionId
        return true
    }

    private fun entityOf(live: Live): Mannequin? =
        live.entityId?.let { Bukkit.getEntity(it) as? Mannequin }?.takeIf { it.isValid }

    /** Session id of [entity] when it is a standing Combat NPC. */
    fun sessionOf(entity: Entity): String? = byEntity[entity.uniqueId]?.takeIf { bySession[it]?.ending == false }

    fun isCombatNpc(entity: Entity): Boolean = entity.scoreboardTags.contains(ENTITY_TAG)

    fun ownerOf(entity: Entity): UUID? = sessionOf(entity)?.let { bySession[it]?.owner }

    /** The NPC's live state (position, health, absorption, fire, air, effects); inventory as captured. */
    private fun liveSnapshot(entity: Mannequin, live: Live, now: Long): CombatNpcStore.Snapshot = live.last.copy(
        location = point(entity.location),
        health = entity.health,
        absorption = entity.absorptionAmount,
        fireTicks = entity.fireTicks,
        air = entity.remainingAir,
        effects = entity.activePotionEffects.map { effectOf(it) },
        effectsAt = now,
    )

    /** [liveSnapshot] plus the armor/off hand as the NPC wears them now (durability, broken pieces). */
    private fun finalSnapshot(entity: Mannequin, live: Live, now: Long): CombatNpcStore.Snapshot {
        val base = liveSnapshot(entity, live, now)
        val eq = entity.equipment
        val worn = mapOf(
            36 to encode(eq.boots),
            37 to encode(eq.leggings),
            38 to encode(eq.chestplate),
            39 to encode(eq.helmet),
            40 to encode(eq.itemInOffHand),
        )
        return base.copy(inventory = CombatNpcRules.withWornEquipment(base.inventory, worn, ::sameIgnoringDamage))
    }

    // ── Tick: expiry, live state, retries ───────────────────

    private fun tick() {
        val now = System.currentTimeMillis()
        tickCount++
        val save = tickCount % SAVE_EVERY_RUNS == 0
        for (live in bySession.values.toList()) {
            if (live.ending) continue
            val entity = entityOf(live)
            if (entity == null) {
                // Gone without a death (unloaded, removed by something else): it survived as last seen.
                endAlive(live, live.last, Outcome.REMOVED, now)
                continue
            }
            if (entity.isDead) continue // Its death event is being handled.
            if (now >= live.expiresAt) {
                endAlive(live, finalSnapshot(entity, live, now), Outcome.SURVIVED, now)
                continue
            }
            live.last = liveSnapshot(entity, live, now)
            followTicket(live, entity)
            if (save) {
                val id = live.sessionId
                val snapshot = live.last
                plugin.databaseManager.async("lifesteal combat NPC live state") { store.saveLive(id, snapshot, now) }
            }
        }
        retryPending()
    }

    private fun retryPending() {
        val now = System.currentTimeMillis()
        pendingDropsMark.removeIf { id -> runCatching { store.markDropsDone(id, now) || store.byId(id)?.dropsDone == true }.getOrDefault(false) }
        pendingComplete.removeIf { id -> runCatching { store.complete(id, now) || store.byId(id)?.state == State.COMPLETE }.getOrDefault(false) }
    }

    /** ACTIVE -> SURVIVED (expired or removed). On a database error the NPC keeps standing and the tick retries. */
    private fun endAlive(live: Live, snapshot: CombatNpcStore.Snapshot, outcome: Outcome, now: Long) {
        live.ending = true
        val won = try {
            store.endAlive(live.sessionId, snapshot, outcome, State.SURVIVED, now)
        } catch (e: Exception) {
            plugin.logger.warning("[CombatNPC] Couldn't settle ${live.ownerName}'s Combat NPC as survived (${e.message}); retrying.")
            live.ending = false
            live.last = snapshot
            return
        }
        forget(live)
        entityOf(live)?.remove()
        if (won) plugin.logger.info("[CombatNPC] ${live.ownerName}'s Combat NPC ${live.sessionId.take(8)} ${if (outcome == Outcome.SURVIVED) "survived its lifetime" else "was removed without dying (kept as survived)"}.")
    }

    private fun forget(live: Live) {
        bySession.remove(live.sessionId)
        live.entityId?.let { byEntity.remove(it) }
        releaseTicket(live)
    }

    private fun followTicket(live: Live, entity: Mannequin) {
        val chunk = entity.location.chunk
        val key = entity.world.name to chunk.chunkKey
        if (key == live.ticket) return
        releaseTicket(live)
        chunk.addPluginChunkTicket(plugin)
        live.ticket = key
    }

    private fun releaseTicket(live: Live) {
        val ticket = live.ticket ?: return
        live.ticket = null
        // Another standing NPC in the same chunk keeps it.
        if (bySession.values.any { it !== live && it.ticket == ticket }) return
        val (worldName, key) = ticket
        val world = Bukkit.getWorld(worldName) ?: return
        val x = (key and 0xFFFFFFFFL).toInt()
        val z = (key ushr 32).toInt()
        if (world.isChunkLoaded(x, z)) world.getChunkAt(x, z).removePluginChunkTicket(plugin)
    }

    // ── Entity events (LifestealCombatNpcListener) ──────────

    /**
     * The NPC died: commit the death first (ACTIVE -> DEAD), then drop the items once, then hearts,
     * statistics and the death message. A second death event, a reconnect in the same tick or a
     * restart all find the session no longer ACTIVE.
     */
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity as? Mannequin ?: return
        if (!isCombatNpc(entity)) return
        // The NPC's own equipment copies never drop: the row's items are what drops.
        event.drops.clear()
        event.droppedExp = 0
        val id = byEntity[entity.uniqueId] ?: return
        val live = bySession[id] ?: return
        if (live.ending) return
        live.ending = true
        val now = System.currentTimeMillis()
        val final = finalSnapshot(entity, live, now)
        val killer = (event.damageSource.causingEntity as? Player)?.takeIf { it.uniqueId != live.owner }
        val message = runCatching { entity.combatTracker.deathMessage }.getOrNull()
            ?: Component.text("${live.ownerName} died")
        val plain = PlainTextComponentSerializer.plainText().serialize(message)
        val damageType = runCatching { event.damageSource.damageType.key.toString() }.getOrNull()
        val committed = try {
            store.die(id, final, killer?.uniqueId, killer?.name, damageType, plain, now)
        } catch (e: Exception) {
            // Not recorded, so it must not die holding the items (the ACTIVE row would bring them back).
            plugin.logger.severe("[CombatNPC] Couldn't record ${live.ownerName}'s Combat NPC death (${e.message}); the NPC stays alive.")
            event.isCancelled = true
            event.reviveHealth = 1.0
            live.ending = false
            return
        }
        forget(live)
        // If anything still cancels this death after us, the entity must not linger with the gear.
        Bukkit.getScheduler().runTask(plugin, Runnable { if (entity.isValid) entity.remove() })
        if (!committed) return

        dropItems(final, entity.location, live.ownerName)
        try {
            if (!store.markDropsDone(id, System.currentTimeMillis())) pendingDropsMark.add(id)
        } catch (e: Exception) {
            plugin.logger.warning("[CombatNPC] Dropped ${live.ownerName}'s items but couldn't confirm it yet (${e.message}); retrying.")
            pendingDropsMark.add(id)
        }

        broadcastDeath(entity.world, message)
        val location = entity.location
        isolated("hearts") { plugin.lifestealHeartManager.onCombatNpcDeath(id, live.owner, live.ownerName, killer, isLifesteal(entity.world)) }
        isolated("kill stats") { plugin.scoreboardManager.recordDeath(live.owner, killer) }
        isolated("kill streaks") { plugin.killStreakManager.recordDeath(live.owner, live.ownerName, killer) }
        isolated("death event") {
            Bukkit.getPluginManager().callEvent(LifestealCombatNpcDeathEvent(id, live.owner, live.ownerName, killer, location, damageType))
        }
        plugin.logger.info("[CombatNPC] ${live.ownerName}'s Combat NPC ${id.take(8)} was killed: $plain.")
    }

    /** The NPC left the world without dying (e.g. removed by a command or plugin, chunk unloaded). */
    fun onRemoved(entity: Entity) {
        if (entity !is Mannequin || entity.isDead) return
        val id = byEntity[entity.uniqueId] ?: return
        val live = bySession[id] ?: return
        if (live.ending) return
        endAlive(live, finalSnapshot(entity, live, System.currentTimeMillis()), Outcome.REMOVED, System.currentTimeMillis())
    }

    /** A Combat NPC entity loaded that no session owns (should never exist: they are non-persistent). */
    fun removeIfGhost(entity: Entity) {
        if (!isCombatNpc(entity)) return
        if (byEntity.containsKey(entity.uniqueId)) return
        entity.remove()
    }

    enum class HitDecision { ALLOW, DENY, NOT_NPC }

    /**
     * Whether [attacker] may hurt the NPC [entity]. ALLOW also overrides region/claim/PvP-toggle
     * protection (the narrow safe-zone exception), but only for this NPC and only for damage.
     */
    fun playerHit(entity: Entity, attacker: Player): HitDecision {
        val id = sessionOf(entity) ?: return HitDecision.NOT_NPC
        val live = bySession[id] ?: return HitDecision.NOT_NPC
        if (!settings().allowPlayerDamage) return HitDecision.DENY
        if (attacker.uniqueId == live.owner) return HitDecision.DENY
        if (attacker.gameMode == GameMode.SPECTATOR) return HitDecision.DENY
        if (plugin.modModeManager.isModMode(attacker)) return HitDecision.DENY
        // Lifesteal friendly fire applies to the owner's NPC as it would to the owner.
        val policy = plugin.lifestealTeamManager.combatPolicy()
        val relation = plugin.lifestealTeamManager.combatRelation(attacker.uniqueId, live.owner)
        if (TeamCombatRules.damage(attacker.uniqueId, live.owner, relation, policy) == TeamCombatRules.DamageDecision.BLOCK_FRIENDLY_FIRE) {
            return HitDecision.DENY
        }
        return HitDecision.ALLOW
    }

    /** Mob / environmental damage toggles (player damage goes through [playerHit]). */
    fun otherDamageAllowed(fromMob: Boolean): Boolean = if (fromMob) settings().allowMobDamage else settings().allowEnvironmentalDamage

    // ── Join: settle the owner's session ────────────────────

    /**
     * PlayerJoinEvent at LOWEST, before anything else touches the inventory: the owner's open session
     * (if any) is settled before they can use their inventory. On a database error the player is
     * disconnected again rather than playing with an empty inventory while their items wait.
     */
    fun onJoin(player: Player) {
        if (!hosted || !tablesReady) return
        resolve(player, 0)
    }

    private fun resolve(player: Player, depth: Int) {
        val now = System.currentTimeMillis()
        val session = try {
            store.open(player.uniqueId)
        } catch (e: Exception) {
            plugin.logger.severe("[CombatNPC] Couldn't read ${player.name}'s Combat NPC session (${e.message}); disconnecting them so nothing is lost.")
            return kickLater(player)
        } ?: return
        when (CombatNpcRules.joinAction(session.state, session.dropsDone)) {
            JoinAction.NONE -> return
            JoinAction.TAKE_OVER -> {
                val live = bySession[session.id]
                val entity = live?.let { entityOf(it) }
                val snapshot = when {
                    entity != null && live != null && !entity.isDead -> finalSnapshot(entity, live, now)
                    live != null -> live.last
                    else -> session.snapshot
                }
                live?.ending = true
                val won = try {
                    store.endAlive(session.id, snapshot, Outcome.RECONNECTED, State.RECOVERING, now)
                } catch (e: Exception) {
                    live?.ending = false
                    plugin.logger.severe("[CombatNPC] Couldn't end ${player.name}'s Combat NPC on reconnect (${e.message}); disconnecting them, the NPC keeps standing.")
                    return kickLater(player)
                }
                if (!won) {
                    live?.ending = false
                    if (depth < 2) resolve(player, depth + 1)
                    return
                }
                if (live != null) {
                    forget(live)
                    entity?.remove()
                }
                restore(player, session.copy(state = State.RECOVERING, snapshot = snapshot), settings().messages.restored)
            }
            JoinAction.RESTORE -> {
                val won = try { store.beginRestore(session.id, now) } catch (e: Exception) {
                    plugin.logger.severe("[CombatNPC] Couldn't start restoring ${player.name}'s Combat NPC state (${e.message}); disconnecting them.")
                    return kickLater(player)
                }
                if (!won) {
                    if (depth < 2) resolve(player, depth + 1)
                    return
                }
                restore(player, session, settings().messages.survived)
            }
            JoinAction.RESTORE_AGAIN -> restore(player, session, settings().messages.survived)
            JoinAction.DEATH -> deathRecovery(player, session)
            JoinAction.DROP_THEN_DEATH -> {
                ensureDrops(session)
                deathRecovery(player, session)
            }
        }
    }

    /**
     * Hands the saved state over: the inventory is *set* from the row (so a repeat after a crash
     * gives the same items), then health, effects (remaining real time only), XP and position, then
     * the player's data is saved and the session settles.
     */
    private fun restore(player: Player, session: CombatNpcStore.Session, message: String) {
        val s = session.snapshot
        val items = try { decodeAll(s.inventory) } catch (e: Exception) {
            plugin.logger.severe("[CombatNPC] ${player.name}'s saved items (session ${session.id}) can't be read (${e.message}); nothing was changed and the session stays open for staff.")
            tell(player, "Your combat-log state couldn't be restored yet; staff have been told. Nothing was lost.", NamedTextColor.RED)
            return
        }
        val now = System.currentTimeMillis()
        player.setItemOnCursor(null)
        player.closeInventory()
        val inventory = player.inventory
        if (items.size == inventory.contents.size) inventory.contents = items.toTypedArray()
        else for ((i, stack) in items.withIndex()) if (i < inventory.contents.size) inventory.setItem(i, stack)
        if (s.heldSlot in 0..8) inventory.heldItemSlot = s.heldSlot
        player.updateInventory()
        player.level = s.xpLevel
        player.exp = s.xpProgress.coerceIn(0f, 0.9999f)
        player.totalExperience = s.xpTotal
        for (effect in CombatNpcRules.effectsAt(s.effects, s.effectsAt, now)) potionOf(effect)?.let { player.addPotionEffect(it) }
        val world = Bukkit.getWorld(s.location.world)
        if (world != null) player.teleport(Location(world, s.location.x, s.location.y, s.location.z, s.location.yaw, s.location.pitch))
        val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: VANILLA_MAX_HEALTH
        player.health = CombatNpcRules.restoredHealth(s.health, max)
        runCatching { player.absorptionAmount = s.absorption.coerceAtLeast(0.0) }
        player.fireTicks = s.fireTicks
        player.remainingAir = s.air
        CombatNpcRules.remainingTag(session.tagExpiresAt, now)?.let { plugin.combatManager.resumeTag(player, it) }
        settle(player, session.id)
        tell(player, message)
    }

    /**
     * The NPC was killed: the items already dropped where it died, so the player gets a normal
     * death's result: empty inventory, no effects, no XP, full (new) health, at their respawn point.
     */
    private fun deathRecovery(player: Player, session: CombatNpcStore.Session) {
        player.setItemOnCursor(null)
        player.closeInventory()
        player.inventory.contents = arrayOfNulls(player.inventory.contents.size)
        player.updateInventory()
        for (effect in player.activePotionEffects.toList()) player.removePotionEffect(effect.type)
        player.level = 0
        player.exp = 0f
        player.totalExperience = 0
        player.fireTicks = 0
        player.fallDistance = 0f
        player.remainingAir = player.maximumAir
        player.foodLevel = 20
        player.saturation = 5f
        plugin.combatManager.untag(player)
        // Hearts already moved at the death (offline); bring max health in line before healing.
        plugin.lifestealHeartManager.sync(player)
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: VANILLA_MAX_HEALTH
        (player.respawnLocation ?: fallbackLocation())?.let { player.teleport(it) }
        settle(player, session.id)
        val death = session.deathMessage ?: "${session.ownerName} died"
        tell(player, settings().messages.died, mapOf("{death}" to death))
    }

    private fun settle(player: Player, id: String) {
        try { player.saveData() } catch (e: Exception) { plugin.logger.warning("[CombatNPC] Couldn't save ${player.name}'s data right after settling: ${e.message}") }
        val done = try { store.complete(id, System.currentTimeMillis()) } catch (e: Exception) {
            plugin.logger.warning("[CombatNPC] Couldn't mark session ${id.take(8)} settled (${e.message}); retrying.")
            false
        }
        if (!done) pendingComplete.add(id)
    }

    // ── Startup recovery ────────────────────────────────────

    private fun recover() {
        val now = System.currentTimeMillis()
        val sessions = try { store.allOpen() } catch (e: Exception) {
            plugin.logger.severe("[CombatNPC] Couldn't read Combat NPC sessions at startup (${e.message}); they stay as they are and are settled when their owners join.")
            return
        }
        try {
            val unreadable = store.unreadableOpen()
            if (unreadable.isNotEmpty()) plugin.logger.severe("[CombatNPC] ${unreadable.size} Combat NPC session(s) can't be read and are left untouched: ${unreadable.joinToString { it.take(8) }}.")
        } catch (_: Exception) {}
        var respawned = 0
        var survived = 0
        var dropped = 0
        for (session in sessions) {
            if (bySession.containsKey(session.id)) continue
            val worldLoaded = Bukkit.getWorld(session.snapshot.location.world) != null
            when (CombatNpcRules.startupAction(session.state, session.expiresAt, session.dropsDone, worldLoaded, now)) {
                StartupAction.RESPAWN -> {
                    val live = Live(session.id, session.owner, session.ownerName, session.expiresAt, null, session.snapshot, null)
                    bySession[session.id] = live
                    if (spawn(live, session.textures, session.texturesSignature, now)) respawned++
                    else { endAlive(live, session.snapshot, Outcome.REMOVED, now); survived++ }
                }
                StartupAction.SURVIVE -> {
                    try {
                        if (store.endAlive(session.id, session.snapshot, Outcome.SURVIVED, State.SURVIVED, now)) survived++
                    } catch (e: Exception) {
                        plugin.logger.warning("[CombatNPC] Couldn't settle ${session.ownerName}'s expired Combat NPC (${e.message}); it is settled when they join.")
                    }
                }
                StartupAction.DROP -> if (ensureDrops(session)) dropped++
                StartupAction.KEEP -> {}
            }
        }
        if (sessions.isNotEmpty()) {
            plugin.logger.info("[CombatNPC] Startup: ${sessions.size} unsettled session(s); $respawned NPC(s) standing again for the rest of their lifetime, $survived settled as survived, $dropped pending drop(s) recovered.")
        }
    }

    /**
     * Drops a committed death's items once (startup or join), when the death was recorded but the
     * drop wasn't confirmed: a crash in that same tick means the first drop never reached a world
     * save. Returns whether the drops are now confirmed.
     */
    private fun ensureDrops(session: CombatNpcStore.Session): Boolean {
        val s = session.snapshot
        val world = Bukkit.getWorld(s.location.world)
        val loc = if (world != null) Location(world, s.location.x, s.location.y, s.location.z)
        else fallbackLocation()?.also {
            plugin.logger.warning("[CombatNPC] ${session.ownerName}'s Combat NPC died in '${s.location.world}', which isn't loaded; dropping the items at ${it.world.name} spawn instead.")
        }
        if (loc == null) return false
        loc.world.getChunkAt(loc) // Load it (once, at most a few per start) so the drops land.
        dropItems(s, loc, session.ownerName)
        return try {
            store.markDropsDone(session.id, System.currentTimeMillis()).also { if (!it) pendingDropsMark.add(session.id) }
        } catch (e: Exception) {
            pendingDropsMark.add(session.id)
            false
        }
    }

    private fun dropItems(s: CombatNpcStore.Snapshot, loc: Location, ownerName: String) {
        val world = loc.world
        for ((i, blob) in s.inventory.withIndex()) {
            if (blob == null) continue
            val stack = try { decode(blob) } catch (e: Exception) {
                plugin.logger.severe("[CombatNPC] Slot $i of $ownerName's items can't be read and wasn't dropped: ${e.message}")
                continue
            }
            if (!stack.type.isAir) world.dropItemNaturally(loc, stack)
        }
        val xp = CombatNpcRules.deathXpDrop(s.xpLevel)
        if (xp > 0) world.spawn(loc, ExperienceOrb::class.java) { it.experience = xp }
    }

    private fun broadcastDeath(world: World, message: Component) {
        if (world.getGameRuleValue(GameRule.SHOW_DEATH_MESSAGES) == false) return
        for (p in Bukkit.getOnlinePlayers()) p.sendMessage(message)
        Bukkit.getConsoleSender().sendMessage(message)
    }

    // ── Diagnostics ─────────────────────────────────────────

    fun recent(limit: Int, owner: UUID?): List<CombatNpcStore.Session> = store.recent(limit, owner)

    // ── Helpers ─────────────────────────────────────────────

    private fun fallbackLocation(): Location? {
        val world = Bukkit.getWorlds().firstOrNull { isLifesteal(it) && it.environment == World.Environment.NORMAL } ?: Bukkit.getWorlds().firstOrNull()
        return world?.spawnLocation
    }

    private fun kickLater(player: Player) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (player.isOnline) player.kick(Component.text("Your combat-log state couldn't be loaded. Please reconnect in a moment.", NamedTextColor.RED))
        })
    }

    private fun tell(player: Player, template: String, values: Map<String, String> = emptyMap()) {
        var component: Component = plugin.commsManager.parseLegacy(template)
        for ((key, value) in values) {
            component = component.replaceText(TextReplacementConfig.builder().matchLiteral(key).replacement(value).build())
        }
        plugin.commsManager.send(player, component, CommunicationsManager.Category.COMBAT)
    }

    private fun tell(player: Player, text: String, color: NamedTextColor) =
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.COMBAT)

    private inline fun isolated(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            plugin.logger.warning("[CombatNPC] Combat NPC death: $what failed: ${e.message}")
        }
    }

    private fun point(loc: Location) = CombatNpcStore.Point(loc.world.name, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)

    private fun encode(stack: ItemStack?): String? =
        stack?.takeIf { !it.type.isAir && it.amount > 0 }?.let { Base64.getEncoder().encodeToString(it.serializeAsBytes()) }

    private fun decode(blob: String): ItemStack = ItemStack.deserializeBytes(Base64.getDecoder().decode(blob))

    private fun decodeAll(slots: List<String?>): List<ItemStack?> = slots.map { it?.let(::decode) }

    /** Same item apart from durability (so worn-down armor replaces the snapshot, a swapped item doesn't). */
    private fun sameIgnoringDamage(a: String, b: String): Boolean = try {
        val x = decode(a)
        val y = decode(b)
        if (x.type != y.type || x.amount != y.amount) false
        else {
            x.editMeta { if (it is Damageable) it.damage = 0 }
            y.editMeta { if (it is Damageable) it.damage = 0 }
            x.isSimilar(y)
        }
    } catch (_: Exception) {
        false
    }

    private fun effectOf(effect: PotionEffect) = CombatNpcRules.Effect(
        type = effect.type.key.toString(),
        amplifier = effect.amplifier,
        durationTicks = if (effect.isInfinite) CombatNpcRules.INFINITE_DURATION else effect.duration,
        ambient = effect.isAmbient,
        particles = effect.hasParticles(),
        icon = effect.hasIcon(),
    )

    private fun potionOf(effect: CombatNpcRules.Effect): PotionEffect? {
        val key = NamespacedKey.fromString(effect.type) ?: return null
        val type = Registry.EFFECT.get(key) ?: return null
        return PotionEffect(type, effect.durationTicks, effect.amplifier, effect.ambient, effect.particles, effect.icon)
    }

    companion object {
        /** Scoreboard tag on every Combat NPC entity (mob stacking, cleaners and NPCManager never match it). */
        const val ENTITY_TAG = "joshymc_lifesteal_combat_npc"
        private const val TICK_PERIOD = 10L
        /** Live state is written every this many runs (5 s). */
        private const val SAVE_EVERY_RUNS = 10
        private const val DAY_MILLIS = 86_400_000L
        private const val VANILLA_MAX_HEALTH = 20.0
    }
}
