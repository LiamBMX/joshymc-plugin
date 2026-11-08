package com.liam.joshymc.lifesteal.ranks

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig.Perk
import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig.RankDef
import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig.RequirementScope
import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig.RequirementType
import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig.Settings
import com.liam.joshymc.lifesteal.ranks.LifestealRankRules.Decision
import com.liam.joshymc.link.LuckPermsGroups
import com.liam.joshymc.manager.DonorTiers
import com.liam.joshymc.manager.RankManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lifesteal ranks, rank perks, rank kits and progression (issue #1428). Lifesteal only: it
 * does nothing on a server that doesn't host Lifesteal, and every perk/kit/permission it hands out
 * applies only while the player is in a Lifesteal world, so Survival is never affected.
 *
 * Grants and revokes are recorded PENDING in [LifestealRankStore] first, then LuckPerms is asked
 * to add/remove exactly one parent group by UUID (works offline; never `parent set`, never another
 * group), and ownership is written only after LuckPerms saved. A pending change survives restarts
 * and is retried (idempotent) by the sweep; after [MAX_ATTEMPTS] failures it becomes FAILED for
 * `/lsranks admin retry`.
 */
class LifestealRankManager(private val plugin: Joshymc) : Listener {

    enum class Status(val label: String) { NOT_HOSTED("not used on this server"), READY("loaded"), INVALID("invalid, see the console") }

    /** What happened to a grant/revoke request. */
    sealed class Outcome {
        data class Completed(val op: LifestealRankStore.Op) : Outcome()
        data class Refused(val decision: Decision) : Outcome()
        /** Same reference as an earlier change: nothing new was recorded. */
        data class Duplicate(val existing: LifestealRankStore.Op) : Outcome()
        /** Recorded, but LuckPerms hasn't saved it yet (unavailable, or an attempt failed); retried automatically. */
        data class Pending(val op: LifestealRankStore.Op, val reason: String) : Outcome()
        data class Failed(val op: LifestealRankStore.Op?, val reason: String) : Outcome()
    }

    val store = LifestealRankStore(plugin.databaseManager)
    val menu = LifestealRankMenu(plugin, this)

    @Volatile var status: Status = Status.NOT_HOSTED
        private set
    @Volatile var settings: Settings? = null
        private set
    @Volatile var problems: List<LifestealRankConfig.Problem> = emptyList()
        private set

    @Volatile private var tablesReady = false
    private var listenerRegistered = false
    private var sweepTask: BukkitTask? = null
    private val owned = ConcurrentHashMap<UUID, Set<String>>()
    private val attachments = ConcurrentHashMap<UUID, PermissionAttachment>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val waiting = ConcurrentHashMap<String, MutableList<(Outcome) -> Unit>>()
    private var luckPermsWarned = false

    /** This server runs Lifesteal at all. */
    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes

    /** Tables exist and the file is valid: ranks can be granted and read. */
    val ready: Boolean get() = hosted && tablesReady && settings != null

    fun isLifesteal(player: Player): Boolean = plugin.gamemodeLockManager.gamemodeOf(player) == ServerGamemode.LIFESTEAL

    /** Lifesteal rank perks/kits/permissions apply to [player] right now. */
    fun appliesTo(player: Player): Boolean = ready && settings?.enabled == true && isLifesteal(player)

    // ── Lifecycle ───────────────────────────────────────────────────────

    /** Startup and every reload, after the gamemode lock and lifesteal.yml are loaded. */
    fun refresh() {
        sweepTask?.cancel()
        sweepTask = null
        if (!hosted) {
            status = Status.NOT_HOSTED
            settings = null
            problems = emptyList()
            clearAllAttachments()
            return
        }
        load()
        if (!tablesReady) {
            try {
                store.createTables()
                tablesReady = true
            } catch (e: Exception) {
                plugin.logger.severe("[LifestealRanks] Could not create the rank tables, so Lifesteal ranks are off: ${e.message}")
                return
            }
        }
        // /joshymc reload unregisters every plugin listener, so register again each time (never twice).
        HandlerList.unregisterAll(this)
        Bukkit.getPluginManager().registerEvents(this, plugin)
        listenerRegistered = true
        owned.clear()
        for (player in Bukkit.getOnlinePlayers()) {
            loadOwned(player.uniqueId)
            applyPermissions(player)
        }
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sweep() }, STARTUP_SWEEP_DELAY_TICKS, SWEEP_INTERVAL_TICKS)
    }

    fun stop() {
        sweepTask?.cancel()
        sweepTask = null
        clearAllAttachments()
        if (listenerRegistered) {
            HandlerList.unregisterAll(this)
            listenerRegistered = false
        }
    }

    private fun load() {
        try {
            val file = plugin.configFile(LifestealRankConfig.FILE)
            if (!file.exists()) {
                plugin.saveResource(LifestealRankConfig.FILE, false)
                plugin.logger.info("[LifestealRanks] Created ${LifestealRankConfig.FILE}.")
            }
            val yaml = YamlConfiguration()
            yaml.load(file)
            val source = object : LifestealRankConfig.Source {
                override fun get(path: String): Any? = yaml.get(path, null)?.let { if (it is ConfigurationSection) null else it }
                override fun keys(path: String): List<String>? = (yaml.get(path, null) as? ConfigurationSection)?.getKeys(false)?.toList()
            }
            val result = LifestealRankConfig.parse(source, reserved())
            problems = result.problems
            settings = result.settings
            status = if (result.settings != null) Status.READY else Status.INVALID
        } catch (e: Exception) {
            problems = listOf(LifestealRankConfig.Problem(true, "Could not read ${LifestealRankConfig.FILE}: ${e.message}"))
            settings = null
            status = Status.INVALID
        }
        for (p in problems) {
            if (p.severe) plugin.logger.severe("[LifestealRanks] ${LifestealRankConfig.FILE}: ${p.message}")
            else plugin.logger.warning("[LifestealRanks] ${LifestealRankConfig.FILE}: ${p.message}")
        }
        val s = settings
        if (s == null) {
            plugin.logger.severe("[LifestealRanks] ${LifestealRankConfig.FILE} is invalid: rank perks, rank kits and new rank grants are paused. Owned ranks are kept. Fix it, then /lsranks admin reload.")
        } else {
            plugin.logger.info("[LifestealRanks] ${s.ranks.size} rank(s): ${s.ranks.joinToString { it.id + if (it.enabled) "" else " (disabled)" }}${if (s.enabled) "" else "; system turned off"}.")
        }
    }

    /** Groups a Lifesteal rank may never grant: staff, Survival donor tiers, JoshyMC display ranks, `default`. */
    private fun reserved(): LifestealRankConfig.Reserved {
        val groups = mutableSetOf("default")
        groups += DonorTiers.ORDER
        plugin.config.getConfigurationSection("staff-ranks.groups")?.let { section ->
            for (key in section.getKeys(false)) {
                groups += key.lowercase()
                groups += section.getStringList(key).map { it.lowercase() }
            }
        }
        plugin.config.getConfigurationSection("ranks.list")?.getKeys(false)?.forEach { groups += it.lowercase() }
        groups += listOf("media", "discord")
        plugin.config.getString("discord.linking.verified-group.group")?.let { groups += it.lowercase() }
        return LifestealRankConfig.Reserved(groups)
    }

    /** `/lsranks admin reload`: re-reads the file only (ownership and pending changes are untouched). */
    fun reload() {
        refresh()
        for (player in Bukkit.getOnlinePlayers()) applyPermissions(player)
    }

    // ── Ownership and perks ─────────────────────────────────────────────

    private fun loadOwned(uuid: UUID): Set<String> {
        val set = try { store.owned(uuid) } catch (e: Exception) {
            plugin.logger.warning("[LifestealRanks] Could not load ranks of $uuid: ${e.message}")
            emptySet()
        }
        owned[uuid] = set
        return set
    }

    /** The ranks [uuid] owns: the cache for online players, the database otherwise. */
    fun ownedRanks(uuid: UUID): Set<String> {
        if (!tablesReady) return emptySet()
        return owned[uuid] ?: if (Bukkit.getPlayer(uuid) != null) loadOwned(uuid) else try { store.owned(uuid) } catch (_: Exception) { emptySet() }
    }

    fun currentRank(uuid: UUID): RankDef? = settings?.let { LifestealRankRules.currentRank(it, ownedRanks(uuid)) }

    /** [perk]'s value for [player], 0 unless Lifesteal ranks apply to them right now. */
    fun perk(player: Player, perk: Perk): Int {
        val s = settings ?: return 0
        if (!appliesTo(player)) return 0
        return LifestealRankRules.perk(s, ownedRanks(player.uniqueId), perk)
    }

    /**
     * The Auction House / Buy Order / sell bonuses as the Survival [RankManager.RankPerk] shape,
     * so those systems read one value. Only called for players in a Lifesteal world, where it
     * replaces the Survival rank perk (a Survival donor group never counts on Lifesteal).
     */
    fun economyPerk(player: Player): RankManager.RankPerk? {
        val s = settings ?: return null
        val perks = LifestealRankRules.perks(s, ownedRanks(player.uniqueId))
        if (perks.isEmpty()) return null
        val rank = currentRank(player.uniqueId) ?: return null
        return RankManager.RankPerk(
            key = "lifesteal:${rank.id}",
            auctionExtraListings = perks[Perk.AUCTION_EXTRA_LISTINGS] ?: 0,
            ordersExtraOrders = perks[Perk.ORDER_EXTRA_ORDERS] ?: 0,
            sellMultiplier = 1.0 + (perks[Perk.SELL_BONUS_PERCENT] ?: 0) / 100.0,
        )
    }

    // ── Progression ─────────────────────────────────────────────────────

    /** The statistics [rank]'s requirements read, for [uuid]. Database reads: call off the hot path. */
    fun stats(uuid: UUID, rank: RankDef): LifestealRankRules.Stats {
        val since = if (rank.requirementScope == RequirementScope.SEASON) {
            plugin.lifestealProgressionManager.progress().season?.launchAt
        } else 0L
        val values = mutableMapOf<RequirementType, Long?>()
        for (type in rank.requirements.keys) {
            values[type] = when (type) {
                RequirementType.PLAYTIME_HOURS -> try { plugin.playtimeManager.getPlaytime(uuid) / 3600L } catch (_: Exception) { null }
                // No season launched yet while the scope is "season": nothing counts yet.
                RequirementType.PVP_KILLS -> if (since == null) 0L else store.pvpKills(uuid, since)
                RequirementType.UNIQUE_VICTIMS -> if (since == null) 0L else store.uniqueVictims(uuid, since)
            }
        }
        return LifestealRankRules.Stats(values)
    }

    // ── Grants ──────────────────────────────────────────────────────────

    /**
     * Grants [rankId] to [uuid] for [source], re-checking everything in the store transaction.
     * [reference] (UNIQUE) makes a repeat of the same request a [Outcome.Duplicate]. [onResult]
     * runs on the main thread exactly once.
     */
    fun requestGrant(
        uuid: UUID, name: String, rankId: String, source: LifestealRankRules.Source, reference: String?, actor: String,
        onResult: (Outcome) -> Unit,
    ) {
        val s = settings
        if (!hosted || !tablesReady || s == null) return onResult(Outcome.Refused(Decision.SYSTEM_DISABLED))
        val rank = s.rank(rankId) ?: return onResult(Outcome.Refused(Decision.UNKNOWN_RANK))
        if (source == LifestealRankRules.Source.PROGRESSION && !LifestealRankRules.requirementsMet(rank, stats(uuid, rank))) {
            return onResult(Outcome.Refused(Decision.REQUIREMENTS_NOT_MET))
        }
        val result = try {
            store.requestGrant(uuid, name, rank.id, rank.group, s.serverContext, source.id, reference, actor) { ownedNow, pending ->
                LifestealRankRules.decideGrant(s, ownedNow, rank.id, source, pending)
            }
        } catch (e: Exception) {
            plugin.logger.severe("[LifestealRanks] Could not record a ${rank.id} grant for $name ($uuid): ${e.message}. Nothing was granted.")
            return onResult(Outcome.Failed(null, "database error"))
        }
        handle(result, onResult)
    }

    /** Removes [rankId] from [uuid] (admin only): the one LuckPerms group, then ownership is marked revoked. */
    fun requestRevoke(uuid: UUID, name: String, rankId: String, actor: String, onResult: (Outcome) -> Unit) {
        val s = settings
        if (!hosted || !tablesReady || s == null) return onResult(Outcome.Refused(Decision.SYSTEM_DISABLED))
        val rank = s.rank(rankId) ?: return onResult(Outcome.Refused(Decision.UNKNOWN_RANK))
        val result = try {
            store.requestRevoke(uuid, name, rank.id, rank.group, s.serverContext, null, actor)
        } catch (e: Exception) {
            plugin.logger.severe("[LifestealRanks] Could not record a ${rank.id} revoke for $name ($uuid): ${e.message}.")
            return onResult(Outcome.Failed(null, "database error"))
        }
        handle(result, onResult)
    }

    private fun handle(result: LifestealRankStore.RequestResult, onResult: (Outcome) -> Unit) {
        when (result) {
            is LifestealRankStore.RequestResult.Refused -> onResult(Outcome.Refused(result.decision))
            is LifestealRankStore.RequestResult.Duplicate -> onResult(Outcome.Duplicate(result.existing))
            is LifestealRankStore.RequestResult.Requested -> {
                plugin.logger.info("[LifestealRanks] ${result.op.action} ${result.op.rankId} for ${result.op.playerName} (${result.op.uuid}) recorded as ${result.op.id} (source ${result.op.source}, by ${result.op.actor}).")
                process(result.op, onResult)
            }
        }
    }

    /** Applies a PENDING change through LuckPerms. Safe to call again for the same change (it's skipped while in flight). */
    private fun process(op: LifestealRankStore.Op, onResult: ((Outcome) -> Unit)? = null) {
        if (onResult != null) waiting.computeIfAbsent(op.id) { mutableListOf() }.add(onResult)
        if (!inFlight.add(op.id)) return
        val lp = try { LuckPermsGroups.current() } catch (e: Exception) {
            plugin.logger.warning("[LifestealRanks] LuckPerms API mismatch: ${e.message}")
            null
        }
        if (lp == null) {
            inFlight.remove(op.id)
            if (!luckPermsWarned) {
                plugin.logger.warning("[LifestealRanks] LuckPerms isn't available: rank changes stay pending and are retried every minute.")
                luckPermsWarned = true
            }
            finish(op.id, Outcome.Pending(op, "LuckPerms isn't available right now"))
            return
        }
        luckPermsWarned = false
        val present = op.action == LifestealRankStore.Action.GRANT
        val future = try {
            val ensure = if (present) lp.ensureGroup(op.group) else java.util.concurrent.CompletableFuture.completedFuture(false)
            ensure.thenCompose { lp.setGroup(op.uuid, op.group, present, op.context) }
        } catch (e: Exception) {
            java.util.concurrent.CompletableFuture.failedFuture<LuckPermsGroups.Change>(e)
        }
        future.whenComplete { change, error ->
            Bukkit.getScheduler().runTask(plugin, Runnable { afterLuckPerms(op, change, error) })
        }
    }

    private fun afterLuckPerms(op: LifestealRankStore.Op, change: LuckPermsGroups.Change?, error: Throwable?) {
        inFlight.remove(op.id)
        if (error != null) {
            val reason = (error.cause ?: error).message ?: error.javaClass.simpleName
            val next = try { store.recordFailure(op.id, MAX_ATTEMPTS, "LuckPerms: $reason") } catch (e: Exception) { null }
            plugin.logger.warning("[LifestealRanks] LuckPerms could not ${op.action.name.lowercase()} ${op.group} for ${op.playerName} (${op.uuid}): $reason. Now ${next ?: "unknown"}.")
            val fresh = store.get(op.id) ?: op
            finish(op.id, if (next == LifestealRankStore.Status.FAILED) Outcome.Failed(fresh, reason) else Outcome.Pending(fresh, reason))
            return
        }
        val completed = try { store.complete(op.id, "luckperms ${change?.name ?: "UNKNOWN"}") } catch (e: Exception) {
            // LuckPerms saved but the record didn't: still PENDING, so the sweep re-applies (idempotent) and records it.
            plugin.logger.severe("[LifestealRanks] ${op.id}: LuckPerms saved, but recording it failed: ${e.message}. It is retried.")
            false
        }
        val fresh = store.get(op.id) ?: op
        if (completed) {
            plugin.logger.info("[LifestealRanks] ${op.action} ${op.rankId} for ${op.playerName} (${op.uuid}) completed (LuckPerms ${change?.name}).")
            val player = Bukkit.getPlayer(op.uuid)
            if (player != null) {
                loadOwned(op.uuid)
                applyPermissions(player)
            } else {
                owned.remove(op.uuid)
            }
            finish(op.id, Outcome.Completed(fresh))
        } else if (fresh.status == LifestealRankStore.Status.COMPLETED) {
            finish(op.id, Outcome.Completed(fresh))
        } else {
            finish(op.id, Outcome.Pending(fresh, "not recorded yet"))
        }
    }

    private fun finish(opId: String, outcome: Outcome) {
        val callbacks = waiting.remove(opId) ?: return
        for (cb in callbacks) {
            try { cb(outcome) } catch (e: Exception) {
                plugin.logger.warning("[LifestealRanks] A rank change callback for $opId threw: ${e.message}")
            }
        }
    }

    /** Every minute: retries PENDING changes (after a crash, a LuckPerms outage or a failed attempt). */
    private fun sweep() {
        if (!tablesReady) return
        val rows = try { store.pending(SWEEP_LIMIT) } catch (e: Exception) {
            plugin.logger.warning("[LifestealRanks] Sweep query failed: ${e.message}")
            return
        }
        for (op in rows) {
            if (settings?.rank(op.rankId) == null && op.action == LifestealRankStore.Action.GRANT) {
                // The rank was removed from the file: never grant a group the config no longer describes.
                if (store.cancel(op.id, "system", "rank ${op.rankId} is no longer defined in ${LifestealRankConfig.FILE}")) {
                    plugin.logger.warning("[LifestealRanks] Cancelled pending grant ${op.id}: rank ${op.rankId} is no longer defined.")
                }
                continue
            }
            process(op)
        }
    }

    /** `/lsranks admin retry`: FAILED → PENDING, then tried at once. */
    fun retry(opId: String, actor: String, onResult: (Outcome) -> Unit): Boolean {
        if (!store.retry(opId, actor)) return false
        val op = store.get(opId) ?: return false
        process(op, onResult)
        return true
    }

    // ── /freerank on Lifesteal ──────────────────────────────────────────

    /** `/freerank` on Lifesteal: the free rank (Rogue by default), once per player ever (UNIQUE reference). */
    fun claimFree(player: Player) {
        val s = settings
        if (!ready || s == null || !s.enabled) {
            Msg.error(player, "There is no free rank to claim on Lifesteal right now.")
            return
        }
        val rank = s.freeRank
        if (rank == null) {
            Msg.error(player, "There is no free rank to claim on Lifesteal yet.")
            return
        }
        Msg.reply(player, Component.text("Applying your free ${rank.displayName} rank...", NamedTextColor.GRAY))
        requestGrant(player.uniqueId, player.name, rank.id, LifestealRankRules.Source.FREE, "freerank:${player.uniqueId}", "player:${player.uniqueId}") { outcome ->
            if (!player.isOnline) return@requestGrant
            when (outcome) {
                is Outcome.Completed -> Msg.success(player, "You successfully claimed your free ${rank.displayName} rank!")
                is Outcome.Duplicate -> when (outcome.existing.status) {
                    LifestealRankStore.Status.PENDING -> Msg.error(player, "Your free ${rank.displayName} rank is still being applied. Try again in a moment.")
                    LifestealRankStore.Status.COMPLETED -> Msg.error(player, "You have already claimed your free ${rank.displayName} rank.")
                    else -> Msg.error(player, "Your free ${rank.displayName} rank couldn't be applied. Please tell staff (change ${outcome.existing.id.take(8)}).")
                }
                is Outcome.Refused -> when (outcome.decision) {
                    Decision.ALREADY_OWNED, Decision.HIGHER_OWNED -> Msg.error(player, "You already have this rank or a higher rank!")
                    else -> Msg.error(player, outcome.decision.message)
                }
                is Outcome.Pending -> Msg.reply(player, Component.text("Your free ${rank.displayName} rank is recorded and will be applied shortly.", NamedTextColor.YELLOW))
                is Outcome.Failed -> Msg.error(player, "Your free ${rank.displayName} rank couldn't be applied. Please tell staff.")
            }
        }
    }

    /** A progression rank claimed from `/lsranks` (requirements re-read now, server side). */
    fun claimProgression(player: Player, rankId: String) {
        val s = settings
        if (!appliesTo(player) || s == null) {
            Msg.error(player, "Lifesteal ranks can only be claimed in Lifesteal.")
            return
        }
        val rank = s.rank(rankId) ?: return Msg.error(player, Decision.UNKNOWN_RANK.message)
        requestGrant(player.uniqueId, player.name, rank.id, LifestealRankRules.Source.PROGRESSION, "progression:${player.uniqueId}:${rank.id}", "player:${player.uniqueId}") { outcome ->
            if (!player.isOnline) return@requestGrant
            when (outcome) {
                is Outcome.Completed -> {
                    Msg.success(player, "You reached the ${rank.displayName} rank!")
                    plugin.commsManager.send(player, Component.text("Open /lsranks to see your new benefits.", TextColor.fromHexString(rank.color) ?: NamedTextColor.GOLD))
                }
                is Outcome.Duplicate -> when (outcome.existing.status) {
                    LifestealRankStore.Status.COMPLETED -> Msg.error(player, "You already earned the ${rank.displayName} rank.")
                    LifestealRankStore.Status.PENDING -> Msg.error(player, "Your ${rank.displayName} rank is still being applied.")
                    else -> Msg.error(player, "Your ${rank.displayName} rank couldn't be applied. Please tell staff (change ${outcome.existing.id.take(8)}).")
                }
                is Outcome.Refused -> Msg.error(player, outcome.decision.message)
                is Outcome.Pending -> Msg.reply(player, Component.text("Your ${rank.displayName} rank is recorded and will be applied shortly.", NamedTextColor.YELLOW))
                is Outcome.Failed -> Msg.error(player, "Your ${rank.displayName} rank couldn't be applied. Please tell staff.")
            }
        }
    }

    // ── Permissions (JoshyMC-applied, Lifesteal worlds only) ─────────────

    /** Gives [player] their inherited rank nodes while in a Lifesteal world, and removes them anywhere else. */
    fun applyPermissions(player: Player) {
        attachments.remove(player.uniqueId)?.let { runCatching { player.removeAttachment(it) } }
        val s = settings ?: return
        if (!appliesTo(player)) return
        val nodes = LifestealRankRules.permissions(s, ownedRanks(player.uniqueId))
        if (nodes.isEmpty()) return
        val attachment = player.addAttachment(plugin)
        for (node in nodes) attachment.setPermission(node, true)
        attachments[player.uniqueId] = attachment
    }

    private fun clearAllAttachments() {
        for ((uuid, attachment) in attachments) {
            Bukkit.getPlayer(uuid)?.let { runCatching { it.removeAttachment(attachment) } }
        }
        attachments.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        if (!ready) return
        val player = event.player
        loadOwned(player.uniqueId)
        applyPermissions(player)
        // Anything left PENDING for them (e.g. by a crash) is retried now rather than at the next sweep.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) store.pendingFor(player.uniqueId)?.let { process(it) }
        }, 60L)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        attachments.remove(event.player.uniqueId)?.let { runCatching { event.player.removeAttachment(it) } }
        owned.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        if (!hosted) return
        applyPermissions(event.player)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        private const val STARTUP_SWEEP_DELAY_TICKS = 200L
        private const val SWEEP_INTERVAL_TICKS = 20L * 60
        private const val SWEEP_LIMIT = 100
    }
}
