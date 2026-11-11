package com.liam.joshymc.lifesteal.hearts

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.eligible
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.hearts.HeartRules.changesHearts
import com.liam.joshymc.lifesteal.hearts.monitor.HeartMonitorRules
import com.liam.joshymc.lifesteal.hearts.monitor.HeartTransferMonitor
import com.liam.joshymc.lifesteal.ranked.RankedHeartSummary
import com.liam.joshymc.lifesteal.ranked.RankedMatchRules
import com.liam.joshymc.manager.AntiDupeManager
import org.bukkit.inventory.EquipmentSlot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The Lifesteal heart system (issue #1341): permanent hearts per player, applied as the player's
 * maximum health, and PvP heart stealing. Runs only where this server hosts Lifesteal
 * (`networkIdentityManager.hostedGamemodes`) and only for players in a Lifesteal world
 * (`gamemodeLockManager.gamemodeOf(world)`); on a Survival server it creates no tables, registers
 * no listener and never touches anyone's health.
 *
 * The database row ([HeartStore]) is the only source of truth. [loaded] holds the hearts of online
 * Lifesteal players whose row has been read this session (the "session"); it is filled on join /
 * entering a Lifesteal world and dropped on quit, so a transfer never runs against data that
 * wasn't loaded, and a reconnect always reads the row again. Every change is written in one
 * synchronous transaction *before* anything is applied to the player, so nothing waits on a
 * delayed save, a disconnect can't lose a change, and shutdown has nothing pending. Those
 * transactions are a few indexed rows on the local SQLite file (the same synchronous pattern the
 * plugin's other one-shot claims use); nothing runs per tick.
 */
class LifestealHeartManager(private val plugin: Joshymc) {

    private val store = HeartStore(plugin.databaseManager)

    /** Effective hearts of online players with a loaded record, by UUID. */
    private val loaded = ConcurrentHashMap<UUID, Int>()

    /** Random per boot, so a death's dedupe key (victim + server tick) is never reused after a restart. */
    private val bootId = UUID.randomUUID().toString().substring(0, 8)

    private var tablesReady = false

    /** Players already told about an unreadable record this session (so it isn't logged every join). */
    private val malformedLogged = ConcurrentHashMap.newKeySet<UUID>()

    @Volatile var limits: HeartRules.Limits? = null
        private set
    @Volatile var cooldownMinutes: Int = 0
        private set

    /** Heart item settings (issue #1344), from lifesteal.yml. */
    @Volatile var itemSettings: LifestealSettings.HeartItems = LifestealSettings.HeartItems()
        private set

    /** Builds and reads physical Heart items. */
    val items = HeartItem(plugin)

    /** Issue #1508: logging-only review of committed transfers (shared connections, repeats). */
    val monitor = HeartTransferMonitor(plugin)

    // Heart item safeguards (issue #1344), all main-thread state cleared on quit.
    /** Players inside a withdrawal or redemption right now (re-entry is refused). */
    private val busy = ConcurrentHashMap.newKeySet<UUID>()
    private val lastWithdrawAt = ConcurrentHashMap<UUID, Long>()
    private val lastRedeemAt = ConcurrentHashMap<UUID, Long>()
    /** Server tick of each player's last redemption attempt: one attempt per tick, whatever the hand. */
    private val lastRedeemTick = ConcurrentHashMap<UUID, Int>()

    // Lifesteal playtime (issue #1457), for the withdrawal eligibility rule. A player is "counting"
    // while online, alive, loaded in a Lifesteal world and not AFK; [playtimeSince] holds when
    // their uncounted stretch began. Every sample (each minute, quit, world change, respawn,
    // withdrawal) credits that stretch to `lifesteal_heart_playtime` and starts a new one only if
    // they are still counting, so offline, AFK and non-Lifesteal time never count and at most one
    // sample interval is ever only in memory.
    private val playtimeSince = ConcurrentHashMap<UUID, Long>()
    private var playtimeTask: BukkitTask? = null

    /** This server runs Lifesteal (fixed per reload). The listener is only registered when true. */
    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes

    /** Hosted, lifesteal.yml valid and tables created: hearts can be read and changed. */
    val active: Boolean get() = hosted && tablesReady && limits != null

    /** Whether the heart system owns max health in [world] (Overload and similar skip it there). */
    fun managesHealthIn(world: World?): Boolean = hosted && plugin.gamemodeLockManager.gamemodeOf(world) == ServerGamemode.LIFESTEAL

    fun isLifesteal(player: Player): Boolean = plugin.gamemodeLockManager.gamemodeOf(player.world) == ServerGamemode.LIFESTEAL

    /** Cached hearts of an online player in Lifesteal, or null. */
    fun heartsOf(uuid: UUID): Int? = loaded[uuid]

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload, after lifestealConfigManager.load(). */
    fun refresh() {
        if (!hosted) {
            samplePlaytime(System.currentTimeMillis())
            if (limits != null || loaded.isNotEmpty()) plugin.logger.info("[Hearts] This server no longer runs Lifesteal; the heart system is off.")
            limits = null
            loaded.clear()
            for (player in Bukkit.getOnlinePlayers()) restoreVanilla(player)
            return
        }
        // Issue #1457: credit what was earned under the old settings before anything changes (reload).
        samplePlaytime(System.currentTimeMillis())
        val settings = plugin.lifestealConfigManager.settings
        if (settings == null) {
            limits = null
            loaded.clear()
            plugin.logger.severe(
                "[Hearts] lifesteal.yml is invalid, so hearts are paused: no heart is granted, stolen or changed and " +
                    "stored hearts are left exactly as they are. Fix lifesteal.yml, then /joshymc reload."
            )
            return
        }
        try {
            store.createTables()
            tablesReady = true
        } catch (e: Exception) {
            tablesReady = false
            limits = null
            plugin.logger.severe("[Hearts] Could not create the heart tables, so hearts are paused: ${e.message}")
            return
        }
        // Issue #1508: logging only; a failure here never pauses hearts.
        try {
            monitor.refresh()
        } catch (e: Exception) {
            plugin.logger.warning("[Hearts] Heart transfer monitoring couldn't start (hearts are unaffected): ${e.message}")
        }
        limits = HeartRules.Limits.of(settings.hearts)
        cooldownMinutes = settings.pvp.sameVictimCooldownMinutes
        itemSettings = settings.heartItems
        plugin.logger.info(
            "[Hearts] Heart system on: start ${settings.hearts.starting}, ${settings.hearts.minimum}-${settings.hearts.maximum} hearts, " +
                "same-victim cooldown ${cooldownMinutes}m."
        )
        loaded.clear()
        for (player in Bukkit.getOnlinePlayers()) sync(player)
        if (playtimeTask == null) {
            playtimeTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { samplePlaytime(System.currentTimeMillis()) }, PLAYTIME_SAMPLE_TICKS, PLAYTIME_SAMPLE_TICKS)
        }
    }

    fun stop() {
        samplePlaytime(System.currentTimeMillis())
        playtimeTask?.cancel()
        playtimeTask = null
        playtimeSince.clear()
        loaded.clear()
        limits = null
    }

    // ── Lifesteal playtime (issue #1457) ────────────────────

    private fun countsPlaytime(player: Player): Boolean =
        active && player.isOnline && !player.isDead && isLifesteal(player) && loaded.containsKey(player.uniqueId) &&
            !(runCatching { plugin.afkManager.isAfk(player) }.getOrDefault(false))

    /** Credits every online player's stretch since the last sample in one transaction (the minute task, reload, stop). */
    private fun samplePlaytime(now: Long) {
        val credits = HashMap<UUID, Long>()
        val online = HashSet<UUID>()
        for (player in Bukkit.getOnlinePlayers()) {
            val uuid = player.uniqueId
            online += uuid
            playtimeSince[uuid]?.let { credits[uuid] = HeartRules.playtimeCredit(it, now, PLAYTIME_MAX_CREDIT_MILLIS) }
            if (countsPlaytime(player)) playtimeSince[uuid] = now else playtimeSince.remove(uuid)
        }
        playtimeSince.keys.retainAll(online)
        savePlaytime(credits, now)
    }

    /** The same for one player ([stillCounting] false on quit). */
    private fun samplePlaytime(player: Player, now: Long, stillCounting: Boolean = countsPlaytime(player)) {
        val uuid = player.uniqueId
        val credit = playtimeSince[uuid]?.let { HeartRules.playtimeCredit(it, now, PLAYTIME_MAX_CREDIT_MILLIS) } ?: 0L
        if (stillCounting) playtimeSince[uuid] = now else playtimeSince.remove(uuid)
        if (credit > 0) savePlaytime(mapOf(uuid to credit), now)
    }

    private fun savePlaytime(credits: Map<UUID, Long>, now: Long) {
        if (credits.values.none { it > 0 } || !tablesReady) return
        try {
            store.addPlaytime(credits, now)
        } catch (e: Exception) {
            plugin.logger.warning("[Hearts] Couldn't save Lifesteal playtime for ${credits.size} player(s); that stretch isn't counted: ${e.message}")
        }
    }

    /** Staff view: stored Lifesteal playtime and what [uuid] still lacks to withdraw, or null with no record. */
    fun withdrawStatus(uuid: UUID): Pair<Long, HeartRules.Eligibility>? {
        Bukkit.getPlayer(uuid)?.let { if (it.isOnline) samplePlaytime(it, System.currentTimeMillis()) }
        val requirements = HeartRules.WithdrawRequirements.of(itemSettings.withdrawEligibility)
        val eligibility = store.eligibility(uuid, requirements, System.currentTimeMillis()) ?: return null
        return store.playtimeMillis(uuid) to eligibility
    }

    fun transferReviews(player: UUID?): List<HeartStore.TransferReview> = store.transferReviews(player)

    // ── Sessions ────────────────────────────────────────────

    /**
     * Brings [player] in line with where they are: in a Lifesteal world, load (or create, once)
     * their record and apply it; anywhere else, drop the session and put back the vanilla
     * maximum health if the heart system had set it. Join, respawn, world change and reload.
     */
    fun sync(player: Player) {
        syncHearts(player)
        // Issue #1457: a world change, respawn or join starts or ends a playtime stretch.
        if (player.isOnline) samplePlaytime(player, System.currentTimeMillis())
    }

    private fun syncHearts(player: Player) {
        if (!player.isOnline) return
        if (!hosted || !isLifesteal(player)) {
            loaded.remove(player.uniqueId)
            restoreVanilla(player)
            return
        }
        val limits = limits ?: return // Paused (invalid config): leave health exactly as the player has it.
        if (!active) return
        val record = try {
            store.ensure(player.uniqueId, limits.starting, System.currentTimeMillis())
        } catch (e: Exception) {
            loaded.remove(player.uniqueId)
            plugin.logger.severe("[Hearts] Could not load the hearts of ${player.name} (${player.uniqueId}); leaving their health as it is: ${e.message}")
            return
        }
        val stored = record.hearts
        if (stored == null) {
            loaded.remove(player.uniqueId)
            if (malformedLogged.add(player.uniqueId)) {
                plugin.logger.severe(
                    "[Hearts] The stored hearts of ${player.name} (${player.uniqueId}) are unreadable; their health is left as it is and " +
                        "no heart can be stolen from or by them until an admin runs /lifesteal hearts set ${player.name} <hearts>."
                )
            }
            return
        }
        val hearts = limits.clamp(stored)
        if (hearts != stored) {
            plugin.logger.warning(
                "[Hearts] ${player.name} has $stored stored hearts, outside ${limits.minimum}-${limits.maximum}; applying $hearts " +
                    "(the stored value is only replaced by their next heart change)."
            )
        }
        loaded[player.uniqueId] = hearts
        apply(player, hearts)
    }

    fun onQuit(player: Player) {
        samplePlaytime(player, System.currentTimeMillis(), stillCounting = false)
        loaded.remove(player.uniqueId)
        malformedLogged.remove(player.uniqueId)
        lastWithdrawAt.remove(player.uniqueId)
        lastRedeemAt.remove(player.uniqueId)
        lastRedeemTick.remove(player.uniqueId)
        deathTags.remove(player.uniqueId)
        rankedDeaths.remove(player.uniqueId)
    }

    /** Sets the max health base value to [hearts] × 2; modifiers (potions, items) stay on top. */
    private fun apply(player: Player, hearts: Int, heal: Boolean = false) {
        val attribute = player.getAttribute(Attribute.MAX_HEALTH) ?: return
        val base = HeartRules.healthFor(hearts)
        if (attribute.baseValue != base) attribute.baseValue = base
        player.persistentDataContainer.set(appliedKey, PersistentDataType.INTEGER, hearts)
        if (player.isDead) return
        val max = attribute.value
        if (heal) player.health = max
        else if (player.health > max) player.health = max
    }

    /** Only for players whose max health the heart system set (marked in their data). */
    private fun restoreVanilla(player: Player) {
        val pdc = player.persistentDataContainer
        if (!pdc.has(appliedKey, PersistentDataType.INTEGER)) return
        pdc.remove(appliedKey)
        val attribute = player.getAttribute(Attribute.MAX_HEALTH) ?: return
        attribute.baseValue = VANILLA_MAX_HEALTH
        if (!player.isDead && player.health > attribute.value) player.health = attribute.value
    }

    /** After respawning: reapply (or restore) and start at full health, like vanilla. */
    fun onPostRespawn(player: Player) {
        sync(player)
        val hearts = loaded[player.uniqueId] ?: return
        apply(player, hearts, heal = true)
    }

    // ── PvP ─────────────────────────────────────────────────

    /**
     * Issue #1461: the victim's combat tag as it stood when they died. [CombatListener] removes the
     * tag at NORMAL priority, before [onDeath] runs at MONITOR, so [captureCombatTag] copies it at
     * LOWEST of the same death. Main thread only; one entry per online player at most, used only in
     * the tick it was taken, removed when used and on quit.
     */
    private data class DeathTag(val tick: Int, val at: Long, val expiresAt: Long, val hits: List<HeartRules.RecentHit>)
    private val deathTags = HashMap<UUID, DeathTag>()

    /**
     * Issue #1474: the ranked match that held the victim when they died, copied at LOWEST of the
     * same death. The ranked death handler (HIGHEST) takes the victim out of the match's held set
     * before [onDeath] runs at MONITOR, so `matchOf(victim)` alone can't tell a ranked death from an
     * ordinary one by then. Memory only (match id + tick, no match or player references), one
     * entry per online player at most, used only in its own tick, removed when used and on quit.
     */
    private val rankedDeaths = HashMap<UUID, RankedMatchRules.DeathCapture>()

    /**
     * PlayerDeathEvent at LOWEST (issue #1461): remember the victim's still-running combat tag (and,
     * #1474, their ranked match, plus, #1478, the opponent hits that match recorded on them, which
     * the defeat at HIGHEST drops).
     */
    fun captureCombatTag(event: PlayerDeathEvent) {
        val uuid = event.player.uniqueId
        deathTags.remove(uuid)
        rankedDeaths.remove(uuid)
        if (!active || !isLifesteal(event.player)) return
        plugin.lifestealRankedManager.matchOf(uuid)?.let {
            rankedDeaths[uuid] = RankedMatchRules.DeathCapture(it.id, Bukkit.getCurrentTick(), plugin.lifestealRankedManager.opponentHitsOn(uuid), System.currentTimeMillis())
        }
        val combat = plugin.combatManager
        val expiresAt = combat.tagExpiresAt(uuid) ?: return
        val hits = combat.recentPvpAttackers(uuid).map { HeartRules.RecentHit(it.attacker, it.at) }
        if (hits.isEmpty()) return
        deathTags[uuid] = DeathTag(Bukkit.getCurrentTick(), System.currentTimeMillis(), expiresAt, hits)
    }

    private fun deathFor(victim: Player, killer: Player?, causing: UUID?) = HeartRules.Death(
        victim = victim.uniqueId,
        victimInLifesteal = isLifesteal(victim),
        victimLoaded = loaded.containsKey(victim.uniqueId),
        causingPlayer = causing,
        killerOnline = killer?.isOnline == true,
        killerInLifesteal = killer != null && isLifesteal(killer),
        killerLoaded = killer != null && loaded.containsKey(killer.uniqueId),
        killerIsNpc = killer != null && killer.hasMetadata("NPC"),
    )

    fun onDeath(event: PlayerDeathEvent) {
        val tick = Bukkit.getCurrentTick()
        val tag = deathTags.remove(event.player.uniqueId)?.takeIf { it.tick == tick }
        // Issue #1474: decided once, here, and used for the whole death (exclusion and summary).
        val rankedCapture = rankedDeaths.remove(event.player.uniqueId)
        val rankedMatch = RankedMatchRules.deathMatch(rankedCapture, tick, plugin.lifestealRankedManager.matchOf(event.player.uniqueId)?.id)
        if (!active) return
        val victim = event.player
        val limits = limits ?: return
        val causing = event.damageSource.causingEntity as? Player
        var killer = causing?.takeIf { it.uniqueId != victim.uniqueId }
        var attribution = HeartRules.attribute(deathFor(victim, killer, causing?.uniqueId))

        // Issue #1461: an environmental (or self-inflicted) death while still combat-tagged by
        // another player is credited to the most recent valid attacker of that tag. Not for a
        // combat-log kill (no combat-log heart penalty exists), a ranked match (ranked hits never
        // tag; the match has its own rules) or /kill.
        var note: String? = null
        val damageType = runCatching { event.damageSource.damageType.key.toString() }.getOrNull()
        val credited = HeartRules.combatTagKiller(
            direct = attribution,
            victim = victim.uniqueId,
            tagExpiresAt = tag?.expiresAt,
            hits = tag?.hits.orEmpty(),
            now = tag?.at ?: System.currentTimeMillis(),
            tagMillis = plugin.combatManager.tagDurationMillis,
            excluded = plugin.combatManager.isCombatLogged(victim) ||
                rankedMatch != null ||
                damageType == GENERIC_KILL,
        ) { attacker ->
            val candidate = Bukkit.getPlayer(attacker)
            candidate != null && HeartRules.attribute(deathFor(victim, candidate, attacker)) == HeartRules.Attribution.PVP_KILL &&
                // Issue #1487: an attacker who used staff abilities can't be credited through the tag either.
                plugin.competitiveEligibility.verdict(candidate, victim.uniqueId).eligible
        }
        if (credited != null) {
            killer = Bukkit.getPlayer(credited)
            attribution = HeartRules.attribute(deathFor(victim, killer, credited))
            note = HeartRules.combatTagNote(damageType?.removePrefix("minecraft:") ?: "the environment")
        }

        // Issue #1478: the ranked counterpart. An environmental (or self-inflicted) death inside an
        // ACTIVE ranked match is credited to the opponent the match saw hit the victim most recently,
        // within one combat-tag duration (the forfeit rule, #1465), on the other roster side of that
        // same match. Same dedupe key and same transferPvp below, so it can't add a second transfer.
        var rankedSource = RankedHeartSummary.Source.KILL
        if (rankedMatch != null && credited == null) {
            val rankedCredit = RankedMatchRules.environmentalKiller(
                direct = attribution,
                victim = victim.uniqueId,
                matchId = rankedMatch,
                captured = rankedCapture,
                currentTick = tick,
                windowMillis = plugin.combatManager.tagDurationMillis,
                excluded = damageType == GENERIC_KILL,
            ) { attacker ->
                val candidate = Bukkit.getPlayer(attacker)
                candidate != null && plugin.lifestealRankedManager.isCreditableOpponent(rankedMatch, attacker, victim.uniqueId) &&
                    HeartRules.attribute(deathFor(victim, candidate, attacker)) == HeartRules.Attribution.PVP_KILL &&
                    plugin.competitiveEligibility.verdict(candidate, victim.uniqueId).eligible
            }
            if (rankedCredit != null) {
                killer = Bukkit.getPlayer(rankedCredit)
                attribution = HeartRules.attribute(deathFor(victim, killer, rankedCredit))
                note = RankedMatchRules.environmentalNote(rankedMatch, damageType?.removePrefix("minecraft:") ?: "the environment")
                rankedSource = RankedHeartSummary.Source.ENVIRONMENT
            }
        }

        if (attribution == HeartRules.Attribution.NOT_LOADED) {
            plugin.logger.warning("[Hearts] ${killer?.name} killed ${victim.name}, but one of them has no loaded heart record; no hearts changed.")
        }
        if (attribution != HeartRules.Attribution.PVP_KILL || killer == null) return

        // One timestamp for the cooldown and the launch progression (issue #1352): the window is the
        // one at the moment of death, however late the transaction runs.
        val now = System.currentTimeMillis()
        val request = HeartStore.PvpRequest(
            dedupeKey = "pvp:$bootId:${victim.uniqueId}:${Bukkit.getCurrentTick()}",
            killer = killer.uniqueId,
            victim = victim.uniqueId,
            now = now,
            cooldownMillis = HeartRules.cooldownMillis(cooldownMinutes),
            limits = limits,
            // Issue #1348: decided at death time from the membership cache plus the recent-teammate
            // window, so a team change after the fatal hit (or in the same tick) can't unlock a transfer.
            team = plugin.lifestealTeamManager.heartCheck(killer.uniqueId, victim.uniqueId),
            window = plugin.lifestealProgressionManager.heartWindow(now),
            // Issue #1487: settled now, from the killer's live state and every hit they made on this victim.
            attacker = plugin.competitiveEligibility.verdict(killer, victim.uniqueId),
            note = note,
        )
        val result = try {
            store.transferPvp(request)
        } catch (e: Exception) {
            plugin.logger.severe("[Hearts] Heart transfer ${killer.name} <- ${victim.name} failed and was rolled back; no hearts changed: ${e.message}")
            send(killer, HeartMessages.TRANSFER_FAILED)
            send(victim, HeartMessages.TRANSFER_FAILED)
            rankedOutcome(rankedMatch, killer, victim, null, rankedSource)
            return
        }
        // Issue #1474: the stored result, exactly as returned, for the ranked match summary (no second transfer).
        rankedOutcome(rankedMatch, killer, victim, result, rankedSource)
        when (result) {
            HeartStore.PvpResult.Duplicate -> return
            is HeartStore.PvpResult.Unavailable -> {
                plugin.logger.warning("[Hearts] ${killer.name} killed ${victim.name}, but no hearts changed: ${result.reason}.")
                return
            }
            is HeartStore.PvpResult.Done -> {
                if (result.decision.changesHearts) {
                    loaded[victim.uniqueId] = result.victimAfter
                    apply(victim, result.victimAfter)
                    if (killer.isOnline && loaded.containsKey(killer.uniqueId)) {
                        loaded[killer.uniqueId] = result.killerAfter
                        apply(killer, result.killerAfter)
                    }
                }
                notify(result, killer, victim.name, victim, limits, victim.uniqueId, request)
                // Issue #1426: Team XP only for kills the heart service accepted as legitimate.
                plugin.teamProgression()?.onPvpKill(killer.uniqueId, victim.uniqueId, result.decision, request.dedupeKey)
                val source = when {
                    rankedMatch != null && rankedSource == RankedHeartSummary.Source.ENVIRONMENT -> HeartMonitorRules.Source.RANKED_ENVIRONMENT
                    rankedMatch != null -> HeartMonitorRules.Source.RANKED_KILL
                    note != null -> HeartMonitorRules.Source.COMBAT_TAG
                    else -> HeartMonitorRules.Source.KILL
                }
                monitorTransfer(result, request, source, killer.name, victim.name, rankedMatch)
            }
        }
    }

    /**
     * Issue #1474: hands what the heart service decided for a kill inside ranked match [matchId] to
     * that match's end-of-match summary. Presentation only: a Duplicate (already reported) adds
     * nothing, an Unavailable result or a rolled-back error ([result] null) is shown as no heart,
     * and a failure here is only logged, never reaching the transfer.
     */
    private fun rankedOutcome(matchId: String?, killer: Player, victim: Player, result: HeartStore.PvpResult?, source: RankedHeartSummary.Source) {
        if (matchId == null || result == HeartStore.PvpResult.Duplicate) return
        val outcome = (result as? HeartStore.PvpResult.Done)?.let { RankedHeartSummary.Outcome.of(it.decision) } ?: RankedHeartSummary.Outcome.UNAVAILABLE
        try {
            plugin.lifestealRankedManager.recordHeartOutcome(
                matchId, RankedHeartSummary.Entry(killer.uniqueId, killer.name, victim.uniqueId, victim.name, outcome, source)
            )
        } catch (e: Exception) {
            plugin.logger.warning("[Hearts] Couldn't add ${killer.name} -> ${victim.name} to the summary of ranked match $matchId: ${e.message}")
        }
    }

    /**
     * Issue #1402: a Lifesteal Combat NPC was killed, which is its (offline) owner's death. The same
     * attribution ([HeartRules.attribute], final damage's causing player only) and the same
     * [HeartStore.transferPvp] as [onDeath], so every rule applies unchanged: the 1-heart minimum,
     * the maximum, the same-victim cooldown, teammates and the launch progression window. The
     * dedupe key is the NPC session id, so one NPC death can move a heart at most once whatever
     * repeats (a second death event, a restart, a retry). The owner's record is read by the store;
     * a missing one is refused there ("victim has no heart record") and nothing moves.
     */
    fun onCombatNpcDeath(sessionId: String, victim: UUID, victimName: String, killer: Player?, victimInLifesteal: Boolean) {
        if (!active) return
        val limits = limits ?: return
        val attribution = HeartRules.attribute(
            HeartRules.Death(
                victim = victim,
                victimInLifesteal = victimInLifesteal,
                // Offline: there is no session to load, the store reads (and checks) the stored record.
                victimLoaded = true,
                causingPlayer = killer?.uniqueId,
                killerOnline = killer?.isOnline == true,
                killerInLifesteal = killer != null && isLifesteal(killer),
                killerLoaded = killer != null && loaded.containsKey(killer.uniqueId),
                killerIsNpc = killer != null && killer.hasMetadata("NPC"),
            )
        )
        if (attribution == HeartRules.Attribution.NOT_LOADED) {
            plugin.logger.warning("[Hearts] ${killer?.name} killed $victimName's Combat NPC, but the killer has no loaded heart record; no hearts changed.")
        }
        if (attribution != HeartRules.Attribution.PVP_KILL || killer == null) return
        val now = System.currentTimeMillis()
        val request = HeartStore.PvpRequest(
            dedupeKey = "combatnpc:$sessionId",
            killer = killer.uniqueId,
            victim = victim,
            now = now,
            cooldownMillis = HeartRules.cooldownMillis(cooldownMinutes),
            limits = limits,
            team = plugin.lifestealTeamManager.heartCheck(killer.uniqueId, victim),
            window = plugin.lifestealProgressionManager.heartWindow(now),
            // Issue #1487: the same check as a normal kill; hits on the NPC count as hits on its owner.
            attacker = plugin.competitiveEligibility.verdict(killer, victim),
        )
        val result = try {
            store.transferPvp(request)
        } catch (e: Exception) {
            plugin.logger.severe("[Hearts] Heart transfer ${killer.name} <- $victimName (Combat NPC $sessionId) failed and was rolled back; no hearts changed: ${e.message}")
            send(killer, HeartMessages.TRANSFER_FAILED)
            return
        }
        when (result) {
            HeartStore.PvpResult.Duplicate -> return
            is HeartStore.PvpResult.Unavailable -> {
                plugin.logger.warning("[Hearts] ${killer.name} killed $victimName's Combat NPC, but no hearts changed: ${result.reason}.")
                return
            }
            is HeartStore.PvpResult.Done -> {
                if (result.decision.changesHearts) {
                    // The owner is offline: their next join reads the stored value (sync). If they are
                    // somehow online, bring them in line now.
                    Bukkit.getPlayer(victim)?.let { if (it.isOnline) sync(it) }
                    if (killer.isOnline && loaded.containsKey(killer.uniqueId)) {
                        loaded[killer.uniqueId] = result.killerAfter
                        apply(killer, result.killerAfter)
                    }
                }
                notify(result, killer, victimName, Bukkit.getPlayer(victim)?.takeIf { it.isOnline }, limits, victim, request)
                plugin.teamProgression()?.onPvpKill(killer.uniqueId, victim, result.decision, request.dedupeKey)
                monitorTransfer(result, request, HeartMonitorRules.Source.COMBAT_NPC, killer.name, victimName)
            }
        }
    }

    /**
     * Issue #1465: [victim] disconnected from an ACTIVE ranked match and the match credited the
     * forfeit to [killer] (`RankedMatchRules.forfeitRecipient`: the opponent who hit them most
     * recently). Called from the quit (LOW, before this manager's own quit at MONITOR, so the
     * victim's session is still loaded). Exactly the same [HeartRules.attribute] and
     * [HeartStore.transferPvp] as a kill: 1-heart minimum, maximum, same-victim cooldown, the team
     * check, the progression window and the audit rows. [dedupeKey] is per match + victim, so one
     * disconnect moves a heart at most once. No Team XP and no Heart item: it isn't a kill.
     * Returns the stored result, or null when nothing was evaluated.
     */
    fun onRankedForfeit(dedupeKey: String, victim: Player, killer: Player, note: String): HeartStore.PvpResult? {
        if (!active) return null
        val limits = limits ?: return null
        if (killer.uniqueId == victim.uniqueId) return null
        val attribution = HeartRules.attribute(deathFor(victim, killer, killer.uniqueId))
        if (attribution != HeartRules.Attribution.PVP_KILL) {
            plugin.logger.info("[Hearts] ${victim.name} forfeited a ranked match by disconnecting; no heart moved to ${killer.name} ($attribution).")
            return null
        }
        val now = System.currentTimeMillis()
        val request = HeartStore.PvpRequest(
            dedupeKey = dedupeKey,
            killer = killer.uniqueId,
            victim = victim.uniqueId,
            now = now,
            cooldownMillis = HeartRules.cooldownMillis(cooldownMinutes),
            limits = limits,
            team = plugin.lifestealTeamManager.heartCheck(killer.uniqueId, victim.uniqueId),
            window = plugin.lifestealProgressionManager.heartWindow(now),
            attacker = plugin.competitiveEligibility.verdict(killer, victim.uniqueId),
            note = note,
        )
        val result = try {
            store.transferPvp(request)
        } catch (e: Exception) {
            plugin.logger.severe("[Hearts] Ranked forfeit transfer ${killer.name} <- ${victim.name} failed and was rolled back; no hearts changed: ${e.message}")
            send(killer, HeartMessages.TRANSFER_FAILED)
            return null
        }
        when (result) {
            HeartStore.PvpResult.Duplicate -> Unit
            is HeartStore.PvpResult.Unavailable -> plugin.logger.warning("[Hearts] ${victim.name} forfeited a ranked match, but no hearts changed: ${result.reason}.")
            is HeartStore.PvpResult.Done -> {
                if (result.decision.changesHearts) {
                    loaded[victim.uniqueId] = result.victimAfter
                    apply(victim, result.victimAfter)
                    if (killer.isOnline && loaded.containsKey(killer.uniqueId)) {
                        loaded[killer.uniqueId] = result.killerAfter
                        apply(killer, result.killerAfter)
                    }
                }
                // The victim is disconnecting: the ranked manager tells them when they're back.
                notify(result, killer, victim.name, null, limits, victim.uniqueId, request)
                monitorTransfer(result, request, HeartMonitorRules.Source.RANKED_FORFEIT, killer.name, victim.name, HeartMonitorRules.forfeitMatchId(dedupeKey))
            }
        }
        return result
    }

    /**
     * Issue #1508: hands a committed heart transfer (only [HeartRules.Decision.Transfer]: the killer
     * gained the victim's heart) to the logging-only monitor, after it was applied and the players
     * were told. Never throws and never touches the transfer: the monitor only queues async work.
     */
    private fun monitorTransfer(
        result: HeartStore.PvpResult.Done, request: HeartStore.PvpRequest, source: HeartMonitorRules.Source,
        killerName: String, victimName: String, matchId: String? = null,
    ) {
        if (result.decision !is HeartRules.Decision.Transfer) return
        try {
            monitor.onTransfer(
                HeartMonitorRules.Transfer(
                    result.transactionId, request.dedupeKey, source, request.killer, request.victim, request.now,
                    result.killerBefore, result.killerAfter, result.victimBefore, result.victimAfter, matchId,
                ),
                killerName, victimName,
            )
        } catch (e: Exception) {
            plugin.logger.warning("[Hearts] Couldn't hand heart transaction ${result.transactionId} to the monitor (the transfer is unaffected): ${e.message}")
        }
    }

    private fun notify(
        result: HeartStore.PvpResult.Done, killer: Player, victimName: String, victim: Player?, limits: HeartRules.Limits,
        victimId: UUID, request: HeartStore.PvpRequest,
    ) {
        val names = mapOf("{victim}" to victimName, "{killer}" to killer.name, "{maximum}" to limits.maximum.toString())
        when (val decision = result.decision) {
            is HeartRules.Decision.IneligibleAttacker -> {
                (request.attacker as? CompetitiveEligibility.Verdict.Ineligible)?.let {
                    plugin.competitiveEligibility.reportBlocked(killer, victimId, victimName, "HEART", it, request.dedupeKey + " tx=" + result.transactionId)
                }
                send(victim, HeartMessages.VICTIM_INELIGIBLE_ATTACKER, names)
            }
            is HeartRules.Decision.Transfer -> {
                send(killer, HeartMessages.KILLER_STOLE, names + ("{hearts}" to result.killerAfter.toString()))
                send(victim, HeartMessages.VICTIM_LOST, names + ("{hearts}" to result.victimAfter.toString()))
            }
            is HeartRules.Decision.VictimLosesOnly -> {
                send(victim, HeartMessages.VICTIM_LOST, names + ("{hearts}" to result.victimAfter.toString()))
            }
            HeartRules.Decision.VictimAtMinimum -> {
                send(killer, HeartMessages.KILLER_VICTIM_LAST_HEART, names)
                send(victim, HeartMessages.VICTIM_LAST_HEART, names)
            }
            HeartRules.Decision.KillerAtMaximum -> {
                send(killer, HeartMessages.KILLER_AT_MAXIMUM, names)
                send(victim, HeartMessages.VICTIM_KILLER_AT_MAXIMUM, names)
            }
            is HeartRules.Decision.Cooldown -> {
                send(killer, HeartMessages.KILLER_COOLDOWN, names + ("{time}" to HeartRules.formatDuration(decision.remainingMillis)))
                send(victim, HeartMessages.VICTIM_COOLDOWN, names)
            }
            HeartRules.Decision.Teammates -> {
                send(killer, HeartMessages.KILLER_TEAMMATE, names)
                send(victim, HeartMessages.VICTIM_TEAMMATE, names)
            }
            HeartRules.Decision.Allies -> {
                send(killer, HeartMessages.KILLER_ALLY, names)
                send(victim, HeartMessages.VICTIM_ALLY, names)
            }
            HeartRules.Decision.TeamUnknown -> {
                plugin.logger.warning("[Hearts] " + killer.name + " killed " + victimName + ", but Lifesteal team membership couldn't be read; no hearts changed.")
                send(killer, HeartMessages.TEAM_UNKNOWN, names)
                send(victim, HeartMessages.TEAM_UNKNOWN, names)
            }
            HeartRules.Decision.GracePeriod -> {
                send(killer, HeartMessages.GRACE_PERIOD, names)
                send(victim, HeartMessages.GRACE_PERIOD, names)
            }
            HeartRules.Decision.NotLaunched -> {
                send(killer, HeartMessages.NOT_LAUNCHED, names)
                send(victim, HeartMessages.NOT_LAUNCHED, names)
            }
            HeartRules.Decision.ProgressionUnknown -> {
                plugin.logger.warning("[Hearts] " + killer.name + " killed " + victimName + ", but the launch progression state is unknown; no hearts changed.")
                send(killer, HeartMessages.PROGRESSION_UNKNOWN, names)
                send(victim, HeartMessages.PROGRESSION_UNKNOWN, names)
            }
        }
    }

    // ── Heart items (issue #1344) ───────────────────────────
    //
    // Everything below runs on the main thread, synchronously, in the same tick as the command or
    // click: the database transaction commits first, then the inventory changes right away, so no
    // disconnect, other click or other command can land in between. What SQLite can't cover is a
    // server crash after the commit but before the player's data file is next saved; the player
    // data is saved right after each change to keep that window small, and the remaining risk is
    // handled by serials (see docs/lifesteal-hearts.md, "Heart items").

    /** Why a heart-item action can't run for [player] right now, or null. */
    private fun itemProblem(player: Player): String? = when {
        !hosted -> HeartMessages.NOT_LIFESTEAL_SERVER
        !active || limits == null -> HeartMessages.PAUSED
        !isLifesteal(player) -> HeartMessages.NOT_IN_LIFESTEAL
        !player.isOnline || player.isDead -> HeartMessages.NOT_NOW
        !loaded.containsKey(player.uniqueId) -> HeartMessages.NOT_LOADED
        // Issue #1361: the match inventory is replaced by the pre-match snapshot afterwards, so a Heart
        // item redeemed or withdrawn inside a match would come back (or vanish) with it.
        plugin.lifestealRankedManager.holds(player) -> HeartMessages.RANKED_MATCH
        else -> null
    }

    /** `/withdrawheart`: one permanent heart becomes one Heart item. */
    fun withdraw(player: Player) {
        val uuid = player.uniqueId
        itemProblem(player)?.let { return send(player, it) }
        val limits = limits ?: return
        val settings = itemSettings
        if (!settings.withdrawEnabled) return send(player, HeartMessages.WITHDRAW_DISABLED)
        val now = System.currentTimeMillis()
        // Issue #1455: the existing combat tag is the authority; checked before anything is taken or issued.
        if (HeartRules.combatBlocksWithdraw(plugin.combatManager.tagExpiresAt(uuid), now)) return send(player, HeartMessages.WITHDRAW_IN_COMBAT)
        if (!HeartRules.cooldownPassed(lastWithdrawAt[uuid], now, settings.actionCooldownMillis.toLong())) return
        val cached = loaded[uuid] ?: return send(player, HeartMessages.NOT_LOADED)
        if (HeartRules.withdraw(cached, limits) == HeartRules.WithdrawDecision.AtMinimum) return send(player, settings.messages.finalHeart)
        if (player.inventory.firstEmpty() < 0) return send(player, settings.messages.inventoryFull)
        if (!busy.add(uuid)) return
        try {
            lastWithdrawAt[uuid] = now
            // Issue #1457: the stretch so far is saved first, so the store's eligibility check sees it.
            samplePlaytime(player, now)
            val requirements = HeartRules.WithdrawRequirements.of(settings.withdrawEligibility)
            val serial = UUID.randomUUID().toString()
            val result = try {
                store.withdraw(uuid, serial, limits, now, requirements = requirements)
            } catch (e: Exception) {
                plugin.logger.severe("[Hearts] Heart withdrawal for ${player.name} failed and was rolled back; nothing changed: ${e.message}")
                return send(player, HeartMessages.WITHDRAW_FAILED)
            }
            when (result) {
                is HeartStore.WithdrawResult.AtMinimum -> {
                    loaded[uuid] = result.hearts
                    apply(player, result.hearts)
                    send(player, settings.messages.finalHeart)
                }
                is HeartStore.WithdrawResult.Unavailable -> {
                    plugin.logger.warning("[Hearts] ${player.name} couldn't withdraw a heart: ${result.reason}.")
                    send(player, HeartMessages.WITHDRAW_FAILED)
                }
                is HeartStore.WithdrawResult.NotEligible -> notEligible(player, result, settings.withdrawEligibility)
                is HeartStore.WithdrawResult.Withdrawn -> {
                    loaded[uuid] = result.after
                    apply(player, result.after)
                    deliver(player, listOf(result.serial), settings, "withdrawal ${result.transactionId}")
                    send(player, settings.messages.withdrawn, mapOf("{hearts}" to result.after.toString(), "{maximum}" to limits.maximum.toString()))
                }
            }
        } finally {
            busy.remove(uuid)
        }
    }

    /** Issue #1457: tells the player what is missing; staff logs only when the store audited it (rate-limited there). */
    private fun notEligible(player: Player, result: HeartStore.WithdrawResult.NotEligible, settings: LifestealSettings.WithdrawEligibility) {
        val e = result.eligibility
        if (e.playtimeMissingMillis > 0) send(player, settings.playtimeMessage, mapOf("{time}" to HeartRules.formatWait(e.playtimeMissingMillis)))
        if (e.accountAgeMissingMillis > 0) send(player, settings.accountAgeMessage, mapOf("{time}" to HeartRules.formatWait(e.accountAgeMissingMillis)))
        if (!result.audited) return
        val details = "reason=${e.auditReason} playtime_missing=${HeartRules.formatWait(e.playtimeMissingMillis)} age_missing=${HeartRules.formatWait(e.accountAgeMissingMillis)}"
        plugin.logger.info("[Hearts] ${player.name} (${player.uniqueId}) can't withdraw hearts yet: $details.")
        staffLog("HEART_WITHDRAW_INELIGIBLE", player.uniqueId, player.name, details)
    }

    /** One `admin_logs` row by the heart system (shown in /admin); a failure is only logged. */
    private fun staffLog(action: String, target: UUID, targetName: String?, details: String) {
        try {
            plugin.adminManager.logAction(null, SYSTEM_ACTOR, action, target, targetName, details)
        } catch (e: Exception) {
            plugin.logger.warning("[Hearts] Couldn't write the $action staff log: ${e.message}")
        }
    }

    /**
     * Puts freshly issued Heart items into empty slots (checked by the caller in the same tick), then
     * saves the player's data. If a slot is somehow gone, the item drops at their feet rather than
     * vanishing. Building an item can't fail for a valid serial; if it ever did, the serial stays
     * ISSUED and is logged so staff can void and refund it.
     */
    private fun deliver(player: Player, serials: List<String>, settings: LifestealSettings.HeartItems, context: String) {
        for (serial in serials) {
            val stack = try {
                items.create(serial, settings)
            } catch (e: Exception) {
                plugin.logger.severe("[Hearts] Couldn't build Heart item $serial for ${player.name} ($context): ${e.message}. Use /lifesteal hearts void $serial refund.")
                continue
            }
            val slot = player.inventory.firstEmpty()
            if (slot >= 0) player.inventory.setItem(slot, stack)
            else {
                player.world.dropItemNaturally(player.location, stack)
                plugin.logger.warning("[Hearts] ${player.name}'s inventory was full, so Heart item $serial was dropped at their feet ($context).")
            }
        }
        savePlayer(player)
    }

    /** Writes the player's data file now, so a crash right after a heart-item change can't roll their inventory back. */
    private fun savePlayer(player: Player) {
        try {
            player.saveData()
        } catch (e: Exception) {
            plugin.logger.warning("[Hearts] Couldn't save ${player.name}'s player data right after a Heart item change: ${e.message}")
        }
    }

    /**
     * Right-click redemption of the Heart item in [hand]. The listener only calls this for one hand
     * per click (main hand first). One attempt per player per tick, and at most one per
     * `action-cooldown-millis`. The item is only taken after the transaction committed.
     */
    fun redeem(player: Player, hand: EquipmentSlot) {
        val uuid = player.uniqueId
        val serial = when (val read = items.read(player.inventory.getItem(hand))) {
            HeartItem.Read.NotHeart -> return
            is HeartItem.Read.Malformed -> {
                plugin.logger.warning("[Hearts] ${player.name} tried to redeem a broken Heart item (${read.reason}).")
                return send(player, HeartMessages.INVALID_ITEM)
            }
            is HeartItem.Read.Heart -> read.serial
        }
        val tick = Bukkit.getCurrentTick()
        if (lastRedeemTick.put(uuid, tick) == tick) return
        val settings = itemSettings
        val now = System.currentTimeMillis()
        if (!HeartRules.cooldownPassed(lastRedeemAt[uuid], now, settings.actionCooldownMillis.toLong())) return
        lastRedeemAt[uuid] = now
        itemProblem(player)?.let { return send(player, if (it == HeartMessages.NOT_LOADED) settings.messages.redeemFailed else it) }
        val limits = limits ?: return
        if (!settings.redeemEnabled) return send(player, HeartMessages.REDEEM_DISABLED)
        val maximum = mapOf("{maximum}" to limits.maximum.toString())
        val cached = loaded[uuid] ?: return send(player, settings.messages.redeemFailed)
        if (HeartRules.redeem(cached, limits) == HeartRules.RedeemDecision.AtMaximum) return send(player, settings.messages.maximum, maximum)
        if (!busy.add(uuid)) return
        try {
            val result = try {
                store.redeem(uuid, serial, limits, now, reviewTransfers = settings.altTransferReview.enabled)
            } catch (e: Exception) {
                plugin.logger.severe("[Hearts] Redeeming Heart item $serial for ${player.name} failed and was rolled back; nothing changed: ${e.message}")
                return send(player, settings.messages.redeemFailed)
            }
            when (result) {
                is HeartStore.RedeemResult.Redeemed -> {
                    removeRedeemed(player, hand, serial)
                    loaded[uuid] = result.after
                    apply(player, result.after)
                    savePlayer(player)
                    send(player, settings.messages.redeemed, maximum + ("{hearts}" to result.after.toString()))
                    result.review?.let { reviewRecorded(player, it) }
                }
                is HeartStore.RedeemResult.AtMaximum -> {
                    loaded[uuid] = result.hearts
                    apply(player, result.hearts)
                    send(player, settings.messages.maximum, maximum)
                }
                HeartStore.RedeemResult.UnknownItem -> {
                    plugin.logger.warning("[Hearts] ${player.name} tried to redeem a Heart item whose serial $serial was never issued (forged).")
                    plugin.antiDupeManager.record(player, "Forged Heart Item", AntiDupeManager.RiskLevel.HIGH, item = "Lifesteal Heart", amount = "1", source = "Heart redemption", transactionId = serial)
                    send(player, HeartMessages.INVALID_ITEM)
                }
                is HeartStore.RedeemResult.AlreadyRedeemed -> {
                    plugin.logger.warning("[Hearts] ${player.name} tried to redeem Heart item $serial, which was already redeemed by ${result.by} (a copy).")
                    plugin.antiDupeManager.record(player, "Duplicate Heart Item Redemption", AntiDupeManager.RiskLevel.CRITICAL, item = "Lifesteal Heart", amount = "1", source = "Heart redemption", transactionId = serial)
                    send(player, HeartMessages.ALREADY_REDEEMED)
                }
                HeartStore.RedeemResult.Voided -> send(player, HeartMessages.VOIDED_ITEM)
                is HeartStore.RedeemResult.Unavailable -> {
                    plugin.logger.warning("[Hearts] ${player.name} couldn't redeem Heart item $serial: ${result.reason}.")
                    send(player, settings.messages.redeemFailed)
                }
            }
        } finally {
            busy.remove(uuid)
        }
    }

    /**
     * Issue #1457: a shared-connection transfer was recorded (once per serial). Staff only; the
     * player is told nothing and nothing about the connection is logged, only the two UUIDs.
     */
    private fun reviewRecorded(player: Player, review: HeartStore.TransferReview) {
        val issuerName = plugin.playerResolver.lastKnownName(review.issuer) ?: review.issuer.toString()
        plugin.logger.warning(
            "[Hearts] Heart item ${review.serial} withdrawn by $issuerName (${review.issuer}) was redeemed by ${player.name} (${player.uniqueId}), " +
                "an account AltManager saw on the same connection. Recorded for staff review (/lifesteal hearts reviews); nothing was blocked " +
                "(a shared connection isn't proof of one owner)."
        )
        staffLog("HEART_TRANSFER_REVIEW", player.uniqueId, player.name, "serial=${review.serial} issuer_uuid=${review.issuer} evidence=${review.evidence}")
    }

    /** Takes exactly the redeemed item: the one in [hand], or wherever that serial is now (it can't have moved within the tick). */
    private fun removeRedeemed(player: Player, hand: EquipmentSlot, serial: String) {
        val inventory = player.inventory
        if (items.read(inventory.getItem(hand)) == HeartItem.Read.Heart(serial)) {
            inventory.setItem(hand, null)
        } else {
            val slot = inventory.contents.indexOfFirst { items.read(it) == HeartItem.Read.Heart(serial) }
            if (slot >= 0) inventory.setItem(slot, null)
            else plugin.logger.warning("[Hearts] Heart item $serial was redeemed by ${player.name} but had already left their inventory; that copy is now worthless.")
        }
        player.updateInventory()
    }

    /** `/lifesteal hearts give`: staff-made, recorded Heart items for an online Lifesteal player. */
    fun grantItems(target: Player, amount: Int, actor: String, actorName: String): HeartStore.GrantResult {
        if (amount < 1 || amount > HeartRules.MAX_ADMIN_ITEM_GRANT) {
            return HeartStore.GrantResult.Invalid("The amount must be between 1 and ${HeartRules.MAX_ADMIN_ITEM_GRANT}.")
        }
        itemProblem(target)?.let { return HeartStore.GrantResult.Invalid("${target.name} can't receive Heart items right now (they must be online and loaded in Lifesteal).") }
        val free = target.inventory.storageContents.count { it == null || it.type.isAir }
        if (free < amount) return HeartStore.GrantResult.Invalid("${target.name} only has $free free inventory slot(s); Heart items don't stack.")
        val serials = List(amount) { UUID.randomUUID().toString() }
        val result = store.grantItems(target.uniqueId, serials, actor, actorName, System.currentTimeMillis())
        if (result is HeartStore.GrantResult.Granted) {
            deliver(target, result.serials, itemSettings, "staff grant ${result.transactionId}")
            plugin.logger.info("[Hearts] $actorName gave ${target.name} $amount Heart item(s) (transaction ${result.transactionId}).")
        }
        return result
    }

    fun outstandingItems(uuid: UUID): List<HeartStore.ItemRow> = store.outstandingItems(uuid)

    /** `/lifesteal hearts void <serial> [refund]`: staff recovery for a lost Heart item. */
    fun voidItem(serial: String, refund: Boolean, actor: String, actorName: String): HeartStore.VoidResult {
        val limits = limits ?: return HeartStore.VoidResult.Refused("Hearts are paused: lifesteal.yml is invalid.")
        val result = store.voidItem(serial, refund, limits, actor, actorName, System.currentTimeMillis())
        if (result is HeartStore.VoidResult.Voided) {
            plugin.logger.info("[Hearts] $actorName voided Heart item $serial${if (result.refunded) " and refunded its heart (${result.before} -> ${result.after})" else ""} (transaction ${result.transactionId}).")
            val after = result.after
            val online = Bukkit.getPlayer(result.owner)
            if (after != null && online != null && online.isOnline && isLifesteal(online)) {
                loaded[result.owner] = after
                apply(online, after)
                send(online, HeartMessages.ADMIN_CHANGED_TARGET, mapOf("{hearts}" to after.toString()))
            }
        }
        return result
    }

    // ── Administration ──────────────────────────────────────

    /** Time left on the same-victim cooldown of [killer] -> [victim] (0 = none), or null while hearts are off. */
    fun pairCooldownRemaining(killer: UUID, victim: UUID): Long? {
        if (!active) return null
        return HeartRules.cooldownRemaining(store.lastTransferAt(killer, victim), System.currentTimeMillis(), HeartRules.cooldownMillis(cooldownMinutes))
    }

    /** Fresh from the database (works for offline players), or null with no record. */
    fun storedRecord(uuid: UUID): HeartStore.Record? = store.load(uuid)

    fun adminChange(uuid: UUID, op: HeartRules.AdminOp, amount: Int, actor: String, actorName: String): HeartStore.AdminResult {
        val limits = limits ?: return HeartStore.AdminResult.Invalid("Hearts are paused: lifesteal.yml is invalid.")
        val result = store.adminChange(uuid, op, amount, limits, actor, actorName, System.currentTimeMillis())
        if (result is HeartStore.AdminResult.Changed) {
            plugin.logger.info("[Hearts] $actorName: ${op.id} $amount for $uuid, ${result.before ?: "unreadable"} -> ${result.after} (transaction ${result.transactionId}).")
            malformedLogged.remove(uuid)
            val online = Bukkit.getPlayer(uuid)
            if (online != null && online.isOnline && isLifesteal(online)) {
                loaded[uuid] = result.after
                apply(online, result.after)
                send(online, HeartMessages.ADMIN_CHANGED_TARGET, mapOf("{hearts}" to result.after.toString()))
            }
        }
        return result
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun send(player: Player?, template: String, values: Map<String, String> = emptyMap()) {
        if (player == null || !player.isOnline) return
        var component: Component = plugin.commsManager.parseLegacy(template)
        for ((key, value) in values) {
            component = component.replaceText(TextReplacementConfig.builder().matchLiteral(key).replacement(value).build())
        }
        plugin.commsManager.send(player, component)
    }

    companion object {
        /** On the player: the hearts the heart system last applied, so leaving Lifesteal can undo it. */
        val appliedKey = NamespacedKey("joshymc", "lifesteal_hearts_applied")

        /** A vanilla player's max health base value (10 hearts). */
        const val VANILLA_MAX_HEALTH = 20.0

        /** `/kill` (and plugin kills using it): never credited through the combat tag (issue #1461). */
        const val GENERIC_KILL = "minecraft:generic_kill"

        /** Lifesteal playtime is sampled once a minute (issue #1457). */
        const val PLAYTIME_SAMPLE_TICKS = 1200L
        /** One sample never credits more than two intervals (server freeze, missed run). */
        const val PLAYTIME_MAX_CREDIT_MILLIS = 120_000L
        /** Name the heart system's own `admin_logs` rows carry. */
        const val SYSTEM_ACTOR = "Lifesteal Hearts"
    }
}
