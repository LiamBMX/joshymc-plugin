package com.liam.joshymc.lifesteal.bounty

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.EventState
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.teams.TeamCombatRules
import com.liam.joshymc.tutorial.TutorialRules
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Lifesteal bounty system (issue #1401): player-funded bounties, paid out to whoever
 * legitimately kills the target in ordinary Lifesteal PvP, refunded when they expire.
 *
 * Lifesteal only: it runs only where `networkIdentityManager.hostedGamemodes` is exactly
 * Lifesteal (the dedicated Lifesteal server), because the `economy` table there is Lifesteal's;
 * on a server that also hosts Survival the one balance table is Survival's
 * ([TutorialRules.economyBelongsTo]), so bounties refuse to start rather than move Survival coins.
 * The Survival `/bounty` (TeamManager) is untouched; `/bounty` is routed by [com.liam.joshymc.command.BountyCommandRouter].
 *
 * Threading: every database call runs on the JoshyMC-DB thread (`databaseManager.async`) and
 * every player-facing effect (messages, announcements, GUIs) is handed back to the main thread.
 * Death attribution and the event/ranked/arena/team state are captured on the main thread at the
 * moment of death, and the claim itself is decided again inside the store transaction.
 */
class LifestealBountyManager(private val plugin: Joshymc) {

    val store = BountyStore(plugin.databaseManager, EconomyWallet())

    private inner class EconomyWallet : BountyStore.Wallet {
        override fun withdraw(uuid: UUID, coins: Double): Boolean = plugin.economyManager.withdraw(uuid, coins)
        override fun deposit(uuid: UUID, coins: Double): Boolean = plugin.economyManager.deposit(uuid, coins)
    }

    /** Random per boot: a death's dedupe key (victim + server tick) is never reused after a restart. */
    private val bootId = UUID.randomUUID().toString().substring(0, 8)

    @Volatile private var tablesReady = false
    @Volatile var settings: BountySettings? = null
        private set

    /** Active bounties, highest first. Reloaded from the database after every change and every sweep. */
    @Volatile var activeBounties: List<BountyStore.ActiveBounty> = emptyList()
        private set
    @Volatile private var activeByTarget: Map<UUID, BountyStore.ActiveBounty> = emptyMap()

    private var sweepTask: BukkitTask? = null
    private val sweeping = AtomicBoolean(false)
    /** One contribution in flight per player. */
    private val busy = ConcurrentHashMap.newKeySet<UUID>()

    val menu = LifestealBountyMenu(plugin, this)

    /** This server runs Lifesteal at all (the listener and the Lifesteal branch of /bounty exist). */
    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes

    /** This server's `economy` table is Lifesteal's (a Lifesteal-only server). */
    val economyIsLifesteal: Boolean
        get() = TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL, plugin.networkIdentityManager.hostedGamemodes)

    /** Existing escrow is processed (expirations, refunds, payouts): even while bounties are disabled or lifesteal.yml is invalid. */
    val processing: Boolean get() = hosted && economyIsLifesteal && tablesReady

    /** New contributions and claims are accepted. */
    val open: Boolean get() = processing && settings?.enabled == true

    /** Settings for processing existing escrow: the loaded ones, or the defaults while the section is invalid. */
    private val effective: BountySettings get() = settings ?: BountySettings.DEFAULTS

    fun isLifesteal(player: Player): Boolean = isLifesteal(player.world)

    fun isLifesteal(world: org.bukkit.World?): Boolean = plugin.gamemodeLockManager.gamemodeOf(world) == ServerGamemode.LIFESTEAL

    fun activeFor(target: UUID): BountyStore.ActiveBounty? = activeByTarget[target]?.takeIf { it.expiresAt > System.currentTimeMillis() }

    /** Why [player] can't use bounties right now, or null. */
    fun unavailableReason(player: Player? = null): String? = when {
        !hosted -> "Bounties only exist on the Lifesteal server."
        !economyIsLifesteal -> "Bounties are unavailable on this server (its coin balances aren't Lifesteal's)."
        !tablesReady -> "Bounties are unavailable right now. Please tell staff."
        settings == null -> "Bounties are paused right now (server configuration). Please tell staff."
        settings?.enabled != true -> "Bounties are turned off right now."
        player != null && !isLifesteal(player) -> "Bounties can only be used in Lifesteal."
        else -> null
    }

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload, after lifesteal.yml is loaded. */
    fun refresh() {
        sweepTask?.cancel()
        sweepTask = null
        if (!hosted) {
            settings = null
            return
        }
        val result = plugin.lifestealConfigManager.bounties
        settings = result.settings
        if (!economyIsLifesteal) {
            plugin.logger.warning(
                "[Bounties] This server also hosts Survival, so its coin balances are Survival's; Lifesteal bounties are off here " +
                    "(they only run on the dedicated Lifesteal server)."
            )
            return
        }
        if (!tablesReady) {
            try {
                store.createTables()
                store.prune(System.currentTimeMillis(), 30L * 24 * 3_600_000L)
                tablesReady = true
            } catch (e: Exception) {
                plugin.logger.severe("[Bounties] Could not create the bounty tables, so bounties are off: ${e.message}")
                return
            }
        }
        when {
            settings == null -> plugin.logger.severe(
                "[Bounties] lifesteal.yml > bounties is invalid: no new contributions or claims. Existing bounties still expire and " +
                    "are refunded, and pending payouts are still paid. Fix it, then /joshymc reload."
            )
            settings?.enabled != true -> plugin.logger.info("[Bounties] Disabled in lifesteal.yml: existing bounties still expire, refund and pay out.")
            else -> plugin.logger.info(
                "[Bounties] On: minimum ${BountyMoney.format(effective.minimumContributionCents)}, expiration ${effective.expirationMillis / 3_600_000L}h, " +
                    "claim cooldown ${effective.claimCooldownMillis / 3_600_000L}h."
            )
        }
        val ticks = effective.sweepIntervalSeconds * 20L
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sweep() }, 100L, ticks)
        plugin.databaseManager.async("bounty: load active") { reloadActive() }
    }

    fun stop() {
        sweepTask?.cancel()
        sweepTask = null
        menu.clear()
        busy.clear()
    }

    private fun reloadActive() {
        val list = try {
            store.activeBounties(System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.warning("[Bounties] Could not reload the active bounty list: ${e.message}")
            return
        }
        activeBounties = list
        activeByTarget = list.associateBy { it.target }
        onMain { menu.refreshOpenLists() }
    }

    private fun onMain(block: () -> Unit) {
        if (!plugin.isEnabled) return
        if (Bukkit.isPrimaryThread()) block() else Bukkit.getScheduler().runTask(plugin, Runnable(block))
    }

    private fun db(description: String, block: () -> Unit) {
        plugin.databaseManager.async("bounty: $description") {
            try {
                block()
            } catch (e: Exception) {
                plugin.logger.severe("[Bounties] $description failed: ${e.message}")
                throw e
            }
        }
    }

    // ── Contributions ───────────────────────────────────────

    sealed interface ContributeOutcome {
        data class Done(val result: BountyStore.ContributeResult) : ContributeOutcome
        data class Refused(val message: String) : ContributeOutcome
    }

    /**
     * The only way coins enter a bounty, for `/bounty add` and every GUI path. [transactionId]
     * identifies this attempt (a GUI confirmation reuses its id, so double clicks can't pay twice);
     * [expectedBountyId] is the bounty the GUI showed (null = create or add, as for the command).
     */
    fun contribute(
        player: Player, target: UUID, targetName: String, cents: Long, transactionId: String,
        expectedBountyId: String?, source: String, onDone: (ContributeOutcome) -> Unit = {},
    ) {
        fun refuse(message: String) {
            send(player, "&c$message")
            onDone(ContributeOutcome.Refused(message))
        }
        unavailableReason(player)?.let { return refuse(it) }
        val s = settings ?: return
        BountyRules.amountProblem(cents, s.minimumContributionCents)?.let { return refuse(it) }
        if (target == player.uniqueId) return refuse(BountyStore.Refusal.SELF.message)
        // Fast refusal from the live team cache; the store re-checks the stored membership in the transaction.
        if (plugin.lifestealTeamManager.sameTeam(player.uniqueId, target)) return refuse(BountyStore.Refusal.TEAMMATE.message)
        if (!busy.add(player.uniqueId)) return refuse("Your previous contribution is still being processed.")
        val now = System.currentTimeMillis()
        val request = BountyStore.ContributeRequest(
            transactionId = transactionId,
            contributor = player.uniqueId,
            contributorName = player.name,
            target = target,
            targetName = targetName,
            cents = cents,
            minimumCents = s.minimumContributionCents,
            expirationMillis = s.expirationMillis,
            now = now,
            expectedBountyId = expectedBountyId,
            source = source,
            targetNotice = { total, expiresAt ->
                BountyRules.fill(s.messages.targetNotified, mapOf("amount" to BountyMoney.format(total), "expires" to BountyRules.formatRemaining(expiresAt - now)))
            },
        )
        val contributorUuid = player.uniqueId
        db("contribution $transactionId") {
            val result = try {
                store.contribute(request)
            } catch (e: Exception) {
                plugin.logger.severe("[Bounties] Contribution $transactionId by ${request.contributorName} failed and was rolled back; no coins were taken: ${e.message}")
                null
            }
            if (result != null) reloadActive()
            onMain {
                busy.remove(contributorUuid)
                val online = Bukkit.getPlayer(contributorUuid)
                when (result) {
                    null -> {
                        online?.let { send(it, "&cThe contribution failed and no coins were taken. Please try again.") }
                        onDone(ContributeOutcome.Refused("failed"))
                    }
                    is BountyStore.ContributeResult.Refused -> {
                        online?.let { send(it, "&c${result.reason.message}") }
                        onDone(ContributeOutcome.Done(result))
                    }
                    is BountyStore.ContributeResult.Duplicate -> {
                        online?.let { send(it, "&7That contribution was already recorded.") }
                        onDone(ContributeOutcome.Done(result))
                    }
                    is BountyStore.ContributeResult.Contributed -> {
                        online?.let {
                            send(it, BountyRules.fill(s.messages.contributed, mapOf(
                                "amount" to BountyMoney.format(result.contributionCents), "target" to targetName, "total" to BountyMoney.format(result.totalCents),
                            )))
                        }
                        if (result.created) {
                            announce(BountyRules.fill(s.messages.created, mapOf("amount" to BountyMoney.format(result.totalCents), "target" to targetName)))
                            Bukkit.getPlayer(target)?.let { deliverNotifications(it) }
                        }
                        onDone(ContributeOutcome.Done(result))
                    }
                }
            }
        }
    }

    // ── Claims ──────────────────────────────────────────────

    /** MONITOR on PlayerDeathEvent (deaths that really happen). Never changes the death, drops or hearts. */
    fun onDeath(event: PlayerDeathEvent) {
        if (!open) return
        val victim = event.player
        val causing = event.damageSource.causingEntity as? Player
        val killer = causing?.takeIf { it.uniqueId != victim.uniqueId }
        val death = BountyRules.Death(
            victim = victim.uniqueId,
            causingPlayer = causing?.uniqueId,
            killerIsNpc = killer?.hasMetadata("NPC") == true,
            killerOnline = killer?.isOnline == true,
            victimInLifesteal = isLifesteal(victim),
            killerInLifesteal = killer != null && isLifesteal(killer),
            inEvent = inEvent(victim) || (killer != null && inEvent(killer)),
            inArena = inArena(victim.uniqueId) || (killer != null && inArena(killer.uniqueId)),
            inRankedMatch = inRankedMatch(victim.uniqueId) || (killer != null && inRankedMatch(killer.uniqueId)),
            inRankedArena = inRankedArena(victim.location) || (killer != null && inRankedArena(killer.location)),
            team = if (killer == null) BountyRules.Team.NOT_TEAMMATES else teamRelation(killer.uniqueId, victim.uniqueId),
        )
        val hasBounty = activeFor(victim.uniqueId) != null
        when (val eligibility = BountyRules.eligibility(death)) {
            is BountyRules.Eligibility.Refused -> {
                val text = eligibility.reason.player
                if (hasBounty && killer != null && text != null) send(killer, "&c$text")
                if (hasBounty && killer != null) {
                    val victimUuid = victim.uniqueId
                    val killerUuid = killer.uniqueId
                    val now = System.currentTimeMillis()
                    db("claim refusal") { store.audit(now, "CLAIM_REFUSED", killerUuid.toString(), null, victimUuid.toString(), null, eligibility.reason.name) }
                }
                return
            }
            is BountyRules.Eligibility.Eligible -> Unit
        }
        val k = killer ?: return
        val s = settings ?: return
        val now = System.currentTimeMillis()
        val request = BountyStore.ClaimRequest(
            dedupeKey = "bounty:$bootId:${victim.uniqueId}:${Bukkit.getCurrentTick()}",
            killer = k.uniqueId,
            killerName = k.name,
            victim = victim.uniqueId,
            victimName = victim.name,
            deathAt = now,
            cooldownMillis = s.claimCooldownMillis,
        )
        db("claim ${request.dedupeKey}") { processClaim(request, s) }
    }

    private fun processClaim(request: BountyStore.ClaimRequest, s: BountySettings) {
        val bounty = store.activeFor(request.victim) ?: return
        // Repeated-kill and shared-connection signals: logged for staff, never a reason to refuse.
        val earlier = store.recordKill(request.killer, request.victim, request.deathAt, s.antiAbuse.repeatWindowMillis)
        if (BountyRules.repeatedKills(earlier, s.antiAbuse.repeatKillThreshold)) {
            suspicious(request, bounty.id, "repeated kills: ${earlier + 1} kills of this target within ${s.antiAbuse.repeatWindowMillis / 3_600_000L}h", s)
        }
        if (s.antiAbuse.logSharedConnections && sharedConnection(request.killer, request.victim)) {
            suspicious(request, bounty.id, "killer and target have shared a connection (hashed address; not proof of an alt)", s)
        }
        val claim = store.claim(request)
        var pay: BountyStore.PayResult? = null
        var announced = false
        if (claim is BountyStore.ClaimResult.Claimed) {
            pay = store.pay(claim.payoutId, System.currentTimeMillis(), s.retryDelaySeconds * 1000L) { hunterNotice(it, s) }
            if (pay is BountyStore.PayResult.Paid) announced = store.markAnnounced(claim.payoutId)
            reloadActive()
        }
        onMain {
            val killer = Bukkit.getPlayer(request.killer)
            when (claim) {
                is BountyStore.ClaimResult.Claimed -> when (val p = pay) {
                    is BountyStore.PayResult.Paid -> {
                        invalidateBoards()
                        if (announced) announceClaim(p.payout, s)
                        killer?.let { deliverNotifications(it) }
                    }
                    is BountyStore.PayResult.Failed -> {
                        plugin.logger.severe("[Bounties] Payout ${claim.payoutId} (${BountyMoney.format(claim.amountCents)} to ${request.killerName}) failed and stays pending for retry: ${p.error}")
                        killer?.let { send(it, "&eYou claimed the bounty on &c${request.victimName}&e. Your reward of &6${BountyMoney.format(claim.amountCents)} &eis being processed and will be paid shortly.") }
                    }
                    else -> Unit
                }
                is BountyStore.ClaimResult.Cooldown -> killer?.let {
                    send(it, "&cYou already claimed a bounty on &f${request.victimName} &crecently. You can claim one on them again in &f${BountyRules.formatRemaining(claim.remainingMillis)}&c. The bounty stays active.")
                }
                BountyStore.ClaimResult.Teammates -> killer?.let { send(it, "&c${BountyRules.Refusal.TEAMMATE.player}") }
                else -> Unit
            }
        }
    }

    private fun suspicious(request: BountyStore.ClaimRequest, bountyId: String, detail: String, s: BountySettings) {
        store.recordSuspicious(request.deathAt, request.killer, request.victim, bountyId, detail)
        if (s.antiAbuse.consoleLogging) plugin.logger.warning("[Bounties] Suspicious: ${request.killerName} killed ${request.victimName} (bounty $bountyId): $detail.")
    }

    /** AltManager's `player_connections` (salted hashes of connection addresses): the only account-link signal that exists. */
    private fun sharedConnection(a: UUID, b: UUID): Boolean = try {
        plugin.databaseManager.queryFirst(
            "SELECT 1 FROM player_connections pa JOIN player_connections pb ON pb.connection_hash = pa.connection_hash WHERE pa.uuid = ? AND pb.uuid = ?",
            a.toString(), b.toString(),
        ) { 1 } != null
    } catch (_: Exception) {
        false
    }

    private fun hunterNotice(p: BountyStore.Payout, s: BountySettings): String =
        BountyRules.fill(s.messages.hunterPaid, mapOf("target" to p.targetName, "amount" to BountyMoney.format(p.amountCents)))

    private fun announceClaim(p: BountyStore.Payout, s: BountySettings) {
        announce(BountyRules.fill(s.messages.claimed, mapOf("hunter" to p.killerName, "target" to p.targetName, "amount" to BountyMoney.format(p.amountCents))))
    }

    private fun teamRelation(killer: UUID, victim: UUID): BountyRules.Team =
        when (plugin.lifestealTeamManager.combatRelation(killer, victim)) {
            TeamCombatRules.Relation.NOT_TEAMMATES -> BountyRules.Team.NOT_TEAMMATES
            TeamCombatRules.Relation.TEAMMATES -> BountyRules.Team.TEAMMATES
            TeamCombatRules.Relation.RECENT_TEAMMATES -> BountyRules.Team.RECENT_TEAMMATES
            TeamCombatRules.Relation.UNKNOWN -> BountyRules.Team.UNKNOWN
        }

    private fun inEvent(player: Player): Boolean = try {
        val events = plugin.eventManager
        val session = events.sessions.current
        events.isInEventWorld(player) ||
            (session != null && session.state == EventState.RUNNING && player.uniqueId in session.participants)
    } catch (_: UninitializedPropertyAccessException) {
        false
    }

    private fun inArena(uuid: UUID): Boolean = try {
        plugin.arenaManager.isInArena(uuid)
    } catch (_: UninitializedPropertyAccessException) {
        false
    }

    private fun inRankedMatch(uuid: UUID): Boolean = try {
        plugin.lifestealRankedManager.holds(uuid) || plugin.lifestealRankedManager.isSpectator(uuid)
    } catch (_: UninitializedPropertyAccessException) {
        false
    }

    private fun inRankedArena(location: Location): Boolean = try {
        val world = location.world?.name
        world != null && plugin.lifestealRankedManager.arenaList().any {
            it.arena.world == world && it.arena.bounds?.contains(location.x, location.y, location.z) == true
        }
    } catch (_: UninitializedPropertyAccessException) {
        false
    }

    // ── Sweep: expirations, refunds, payouts, notifications ─

    /** Runs every `sweep-interval-seconds`; never overlaps itself. Also `/bounty admin retry`. */
    fun sweep(onDone: ((String) -> Unit)? = null) {
        if (!processing) return
        if (!sweeping.compareAndSet(false, true)) {
            onDone?.invoke("A sweep is already running.")
            return
        }
        val s = effective
        db("sweep") {
            val touched = mutableSetOf<UUID>()
            val announcements = mutableListOf<BountyStore.Payout>()
            var expired = 0
            var refunded = 0
            var paid = 0
            var failed = 0
            try {
                val now = System.currentTimeMillis()
                expired = store.expireDue(now).size
                for (id in store.dueRefunds(now)) {
                    when (val r = store.refund(id, now, s.retryDelaySeconds * 1000L) { c, target -> refundNotice(c, target, s) }) {
                        is BountyStore.RefundResult.Refunded -> { refunded++; touched += r.contribution.contributor }
                        is BountyStore.RefundResult.Failed -> { failed++; plugin.logger.severe("[Bounties] Refund of contribution $id failed and stays pending: ${r.error}") }
                        else -> Unit
                    }
                }
                for (id in store.duePayouts(now)) {
                    when (val p = store.pay(id, now, s.retryDelaySeconds * 1000L) { hunterNotice(it, s) }) {
                        is BountyStore.PayResult.Paid -> { paid++; touched += p.payout.killer }
                        is BountyStore.PayResult.Failed -> { failed++; plugin.logger.severe("[Bounties] Payout $id failed and stays pending: ${p.error}") }
                        else -> Unit
                    }
                }
                for (p in store.unannouncedPayouts()) {
                    if (store.markAnnounced(p.id)) announcements += p
                }
                reloadActive()
            } finally {
                sweeping.set(false)
            }
            val summary = "expired $expired, refunded $refunded, paid $paid, failed $failed"
            if (expired + refunded + paid + failed > 0) plugin.logger.info("[Bounties] Sweep: $summary.")
            onMain {
                if (paid > 0) invalidateBoards()
                announcements.forEach { announceClaim(it, s) }
                touched.mapNotNull { Bukkit.getPlayer(it) }.forEach { deliverNotifications(it) }
                onDone?.invoke(summary)
            }
        }
    }

    private fun refundNotice(c: BountyStore.Contribution, targetName: String, s: BountySettings): String =
        BountyRules.fill(s.messages.refunded, mapOf("target" to targetName, "amount" to BountyMoney.format(c.amountCents)))

    // ── Notifications ───────────────────────────────────────

    /** Shows [player]'s undelivered private bounty notices once (join, entering Lifesteal, right after they're created). */
    fun deliverNotifications(player: Player) {
        if (!processing || !isLifesteal(player)) return
        val uuid = player.uniqueId
        db("notifications $uuid") {
            val now = System.currentTimeMillis()
            val claimed = store.pendingNotifications(uuid).filter { store.markDelivered(it.id, now) }
            if (claimed.isEmpty()) return@db
            onMain {
                val online = Bukkit.getPlayer(uuid)
                if (online == null || !online.isOnline || !isLifesteal(online)) {
                    db("notifications back $uuid") { claimed.forEach { store.unmarkDelivered(it.id) } }
                    return@onMain
                }
                claimed.forEach { send(online, it.message) }
            }
        }
    }

    fun onJoin(player: Player) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { if (player.isOnline) deliverNotifications(player) }, 40L)
    }

    fun onQuit(player: Player) {
        menu.forget(player.uniqueId)
        busy.remove(player.uniqueId)
    }

    // ── Reads for commands and menus (async, answer on the main thread) ──

    data class PersonalStats(val stats: BountyStore.Stats, val active: List<BountyStore.OwnContribution>)

    fun loadStats(uuid: UUID, callback: (PersonalStats?) -> Unit) {
        db("stats $uuid") {
            val loaded = try { PersonalStats(store.stats(uuid), store.activeContributionsBy(uuid)) } catch (e: Exception) { null }
            onMain { callback(loaded) }
        }
    }

    data class BoardPage(val board: BountyStore.Board, val page: Int, val pages: Int, val entries: List<BountyStore.BoardEntry>)

    private data class BoardKey(val board: BountyStore.Board, val page: Int)
    private val boardCache = ConcurrentHashMap<BoardKey, Pair<Long, BoardPage>>()

    fun loadBoard(board: BountyStore.Board, page: Int, perPage: Int, callback: (BoardPage?) -> Unit) {
        val key = BoardKey(board, page)
        boardCache[key]?.let { (at, cached) -> if (System.currentTimeMillis() - at < BOARD_CACHE_MILLIS) return callback(cached) }
        db("leaderboard $board") {
            val loaded = try {
                val size = store.leaderboardSize()
                val p = BountyRules.clampPage(page, size, perPage)
                BoardPage(board, p, BountyRules.pageCount(size, perPage), store.leaderboard(board, p * perPage, perPage))
            } catch (e: Exception) { null }
            if (loaded != null) boardCache[key] = System.currentTimeMillis() to loaded
            onMain { callback(loaded) }
        }
    }

    /** Leaderboards change only on a payout; drop the cache then. */
    private fun invalidateBoards() = boardCache.clear()

    /** Runs a read for staff off the main thread. */
    fun <T> read(description: String, query: (BountyStore) -> T, callback: (T?) -> Unit) {
        db(description) {
            val value = try { query(store) } catch (e: Exception) { plugin.logger.warning("[Bounties] $description failed: ${e.message}"); null }
            onMain { callback(value) }
        }
    }

    // ── Messages ────────────────────────────────────────────

    /** Lifesteal players only: Survival players never see a bounty broadcast. */
    private fun announce(legacy: String) {
        invalidateBoards()
        val component = plugin.commsManager.parseLegacy(legacy)
        for (player in Bukkit.getOnlinePlayers()) {
            if (isLifesteal(player)) plugin.commsManager.sendRaw(player, component)
        }
        plugin.logger.info("[Bounties] " + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component))
    }

    fun send(player: Player, legacy: String) {
        if (player.isOnline) plugin.commsManager.send(player, plugin.commsManager.parseLegacy(legacy))
    }

    companion object {
        const val BOARD_CACHE_MILLIS = 30_000L
    }
}
