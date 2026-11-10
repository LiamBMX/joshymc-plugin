package com.liam.joshymc.lifesteal.bank

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.EventState
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.bounty.BountyMoney
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.progression.ProgressionRules
import com.liam.joshymc.lifesteal.teams.TeamCombatRules
import com.liam.joshymc.tutorial.TutorialRules
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Lifesteal Protected Bank and PvP coin stealing (issue #1427).
 *
 * Two balances per player: the **wallet** is the existing economy balance (EconomyManager,
 * `/balance`, `/pay`, shops, bounties: nothing about it changes), the **bank** is a separate
 * protected balance in [BankStore]. A legitimate PvP kill moves a share of the victim's wallet
 * (never the bank) to the killer.
 *
 * Lifesteal only, exactly like the bounties: it runs only where `hostedGamemodes` is Lifesteal
 * alone ([TutorialRules.economyBelongsTo]), because only there is the `economy` table
 * Lifesteal's. A server that also hosts Survival never creates the tables, never registers
 * `/bank` and never moves a coin on a death, so the Survival economy is untouched.
 *
 * Threading: every transfer and steal is one short synchronous transaction on the main thread
 * (the heart system's pattern). That keeps the live checks (combat tag, ranked match, event,
 * freeze) and the transaction in the same tick, so nothing can change between the check and the
 * move, whatever opened it (command, alias, GUI, a stale GUI, another plugin's dispatch).
 */
class LifestealBankManager(private val plugin: Joshymc) {

    private inner class EconomyWallet : BankStore.Wallet {
        override fun balance(uuid: UUID): Double = plugin.economyManager.getBalance(uuid)
        override fun withdraw(uuid: UUID, coins: Double): Boolean = plugin.economyManager.withdraw(uuid, coins)
        override fun deposit(uuid: UUID, coins: Double): Boolean = plugin.economyManager.deposit(uuid, coins)
    }

    val store = BankStore(plugin.databaseManager, EconomyWallet())
    val menu = LifestealBankMenu(plugin, this)

    /** Random per boot: a death's dedupe key (victim + server tick) is never reused after a restart. */
    private val bootId = UUID.randomUUID().toString().substring(0, 8)

    @Volatile private var tablesReady = false
    @Volatile var bankSettings: BankSettings? = null
        private set
    @Volatile var coinSettings: CoinStealSettings? = null
        private set

    /**
     * Integration point for later capacity bonuses (team upgrades, rank perks, progression): each
     * returns extra capacity in cents for a player. Only used when `bank.max-capacity` is limited;
     * negative values count as 0 and the sum can never pass [BountyMoney.MAX_CENTS].
     */
    fun interface CapacityBonus {
        fun bonusCents(uuid: UUID): Long
    }

    private val capacityBonuses = CopyOnWriteArrayList<CapacityBonus>()

    fun registerCapacityBonus(bonus: CapacityBonus) {
        capacityBonuses += bonus
    }

    /** This server runs Lifesteal at all (the listener exists). */
    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes

    /** This server's `economy` table is Lifesteal's (a Lifesteal-only server). */
    val economyIsLifesteal: Boolean
        get() = TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL, plugin.networkIdentityManager.hostedGamemodes)

    /** Bank balances can be read and withdrawn (even while banking is disabled or the section is invalid). */
    val ready: Boolean get() = hosted && economyIsLifesteal && tablesReady

    /** PvP kills move coins right now. */
    val stealing: Boolean get() = ready && coinSettings?.enabled == true

    /** Settings for reading balances and withdrawing while `bank` is invalid: the defaults (no fee). */
    val effectiveBank: BankSettings get() = bankSettings ?: BankSettings.DEFAULTS

    fun isLifesteal(player: Player): Boolean = isLifesteal(player.world)

    fun isLifesteal(world: World?): Boolean = plugin.gamemodeLockManager.gamemodeOf(world) == ServerGamemode.LIFESTEAL

    fun season(): String? = try {
        plugin.lifestealProgressionManager.progress().season?.id
    } catch (_: UninitializedPropertyAccessException) {
        null
    }

    /** Capacity of [uuid]'s bank in cents, null = unlimited. */
    fun capacityFor(uuid: UUID): Long? {
        val base = effectiveBank.maxCapacityCents ?: return null
        var total = base
        for (bonus in capacityBonuses) {
            val extra = try { bonus.bonusCents(uuid) } catch (e: Exception) {
                plugin.logger.warning("[Bank] A capacity bonus failed for $uuid and counts as 0: ${e.message}")
                0L
            }
            total = BountyMoney.add(total, extra.coerceAtLeast(0)) ?: BountyMoney.MAX_CENTS
        }
        return total
    }

    /** Why [player] can't use the bank at all right now (server state), or null. Live player state is [accessBlock]. */
    fun unavailableReason(): String? = when {
        !hosted -> "The bank only exists on the Lifesteal server."
        !economyIsLifesteal -> "The bank is unavailable on this server (its coin balances aren't Lifesteal's)."
        !tablesReady -> "The bank is unavailable right now. Please tell staff."
        else -> null
    }

    /** Live state of [player], read now, every time a transfer runs. */
    fun accessBlock(player: Player): BankRules.Block? {
        val state = BankRules.AccessState(
            inLifesteal = isLifesteal(player),
            dead = player.isDead,
            combatTagged = safe { plugin.combatManager.isTagged(player) },
            inRankedMatch = safe {
                val ranked = plugin.lifestealRankedManager
                ranked.holds(player) || ranked.isSpectator(player.uniqueId) || ranked.hasPendingRestore(player.uniqueId)
            },
            inEvent = safe { inEvent(player) },
            frozen = safe { plugin.adminManager.isFrozen(player.uniqueId) },
        )
        return BankRules.accessBlock(state, effectiveBank)
    }

    /** A check that throws (manager not started) blocks banking rather than letting it through. */
    private inline fun safe(check: () -> Boolean): Boolean = try { check() } catch (_: Exception) { true }

    private fun inEvent(player: Player): Boolean {
        val events = plugin.eventManager
        val session = events.sessions.current
        return events.isInEventWorld(player) ||
            (session != null && session.state == EventState.RUNNING && player.uniqueId in session.participants)
    }

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload, after lifesteal.yml is loaded. */
    fun refresh() {
        if (!hosted) {
            bankSettings = null
            coinSettings = null
            return
        }
        bankSettings = plugin.lifestealConfigManager.bank.settings
        coinSettings = plugin.lifestealConfigManager.pvpCoins.settings
        if (!economyIsLifesteal) {
            plugin.logger.warning(
                "[Bank] This server also hosts Survival, so its coin balances are Survival's; the Lifesteal bank and PvP coin stealing are off here " +
                    "(they only run on the dedicated Lifesteal server)."
            )
            return
        }
        if (!tablesReady) {
            try {
                store.createTables()
                tablesReady = true
            } catch (e: Exception) {
                plugin.logger.severe("[Bank] Could not create the bank tables, so the bank and coin stealing are off: ${e.message}")
                return
            }
        }
        val b = bankSettings
        when {
            b == null -> plugin.logger.severe("[Bank] lifesteal.yml > bank is invalid: no deposits until it is fixed. Bank balances are kept and can still be withdrawn (no fee).")
            !b.enabled -> plugin.logger.info("[Bank] Disabled in lifesteal.yml: no deposits. Bank balances are kept and can still be withdrawn.")
            else -> plugin.logger.info(
                "[Bank] On: capacity ${b.maxCapacityCents?.let { BountyMoney.format(it) } ?: "unlimited"}, deposit fee ${BankRules.formatBps(b.depositFeeBps)}, " +
                    "withdrawal fee ${BankRules.formatBps(b.withdrawalFeeBps)}."
            )
        }
        val c = coinSettings
        when {
            c == null -> plugin.logger.severe("[Bank] lifesteal.yml > pvp-coins is invalid: no coins are stolen on PvP deaths until it is fixed (hearts are unaffected).")
            !c.enabled -> plugin.logger.info("[Bank] PvP coin stealing is disabled in lifesteal.yml (hearts are unaffected).")
            else -> plugin.logger.info(
                "[Bank] PvP coin stealing on: ${BankRules.formatBps(c.walletLossBps)} of the victim's wallet" +
                    (c.maxPerKillCents?.let { ", at most ${BountyMoney.format(it)}" } ?: "") +
                    ", ranked ${if (c.rankedEnabled) "on" else "off"}, same-victim cooldown ${c.sameVictimCooldownMillis / 60_000L}m."
            )
        }
    }

    fun stop() {
        menu.clear()
    }

    // ── Deposits and withdrawals ────────────────────────────

    sealed interface Outcome {
        data class Done(val result: BankStore.TransferResult.Done) : Outcome
        data class Refused(val message: String) : Outcome
    }

    /**
     * The only way coins enter or leave a bank, for `/bank deposit|withdraw` and every GUI button.
     * [cents] null = all. [txId] identifies the attempt (a GUI confirmation reuses its id, so a
     * double click moves coins once). Rechecks everything now, in the tick it runs.
     */
    fun transfer(player: Player, direction: BankRules.Direction, cents: Long?, txId: String, source: String): Outcome {
        fun refuse(message: String): Outcome {
            send(player, "&c$message")
            return Outcome.Refused(message)
        }
        unavailableReason()?.let { return refuse(it) }
        val settings = bankSettings
        if (direction == BankRules.Direction.DEPOSIT) {
            if (settings == null) return refuse("Deposits are paused right now (server configuration). Please tell staff.")
            if (!settings.enabled) return refuse("Deposits are turned off right now. You can still withdraw.")
        }
        accessBlock(player)?.let { return refuse(it.message) }
        // An invalid section only allows withdrawals (checked above), at the default terms: no fee.
        val s = settings ?: BankSettings.DEFAULTS
        val feeBps = if (direction == BankRules.Direction.DEPOSIT) s.depositFeeBps else s.withdrawalFeeBps
        val now = System.currentTimeMillis()
        val season = season()
        val request = BankStore.TransferRequest(
            txId = txId, uuid = player.uniqueId, direction = direction, requestedCents = cents,
            capacityCents = capacityFor(player.uniqueId), feeBps = feeBps, now = now, season = season, source = source,
        )
        val result = try {
            store.transfer(request)
        } catch (e: Exception) {
            plugin.logger.severe("[Bank] ${direction.id} $txId by ${player.name} failed and was rolled back; nothing moved: ${e.message}")
            recordFailure(txId, player.uniqueId, if (direction == BankRules.Direction.DEPOSIT) BankStore.EntryType.DEPOSIT else BankStore.EntryType.WITHDRAW, cents, null, now, season, source, e)
            return refuse("The transfer failed and nothing moved. Please try again.")
        }
        return when (result) {
            is BankStore.TransferResult.Refused -> refuse(refusalText(result))
            BankStore.TransferResult.Duplicate -> {
                send(player, "&7That transfer was already completed.")
                Outcome.Refused("duplicate")
            }
            is BankStore.TransferResult.Done -> {
                val p = result.plan
                val template = if (direction == BankRules.Direction.DEPOSIT) effectiveBank.messages.deposited else effectiveBank.messages.withdrew
                val feeText = if (p.feeCents > 0) BankRules.fill(effectiveBank.messages.feeSuffix, mapOf("fee" to BountyMoney.format(p.feeCents))) else ""
                send(player, BankRules.fill(template, mapOf(
                    "amount" to BountyMoney.format(p.amountCents),
                    "net" to BountyMoney.format(p.netCents),
                    "fee" to feeText,
                    "wallet" to BountyMoney.format(result.walletAfterCents),
                    "bank" to BountyMoney.format(result.bankAfterCents),
                )))
                Outcome.Done(result)
            }
        }
    }

    private fun refusalText(r: BankStore.TransferResult.Refused): String = when (r.reason) {
        BankRules.Refusal.INSUFFICIENT_WALLET -> "${r.reason.message} Wallet: ${BountyMoney.format(r.walletCents)}."
        BankRules.Refusal.INSUFFICIENT_BANK -> "${r.reason.message} Bank: ${BountyMoney.format(r.bankCents)}."
        BankRules.Refusal.CAPACITY -> "${r.reason.message} It can hold at most ${BountyMoney.format(effectiveBank.maxCapacityCents ?: BountyMoney.MAX_CENTS)}."
        else -> r.reason.message
    }

    private fun recordFailure(
        txId: String, uuid: UUID, type: BankStore.EntryType, cents: Long?, counterparty: UUID?, now: Long, season: String?, source: String, e: Exception,
    ) {
        try {
            store.recordFailure(txId, uuid, type, cents, counterparty, now, season, source, e.message ?: e.javaClass.simpleName)
        } catch (inner: Exception) {
            plugin.logger.severe("[Bank] Could not record failed transaction $txId either: ${inner.message}")
        }
    }

    /** Wallet and bank in cents, or null when the database can't be read. */
    fun balances(uuid: UUID): BankStore.Balances? = try {
        store.balances(uuid)
    } catch (e: Exception) {
        plugin.logger.warning("[Bank] Could not read the balances of $uuid: ${e.message}")
        null
    }

    // ── PvP coin stealing ───────────────────────────────────

    /** MONITOR + ignoreCancelled on PlayerDeathEvent: only deaths that really happen. Never changes the death, drops or hearts. */
    fun onDeath(event: PlayerDeathEvent) {
        if (!stealing) return
        val victim = event.player
        val causing = event.damageSource.causingEntity as? Player
        val killer = causing?.takeIf { it.uniqueId != victim.uniqueId }
        val attribution = attribute(victim.uniqueId, isLifesteal(victim), causing?.uniqueId, killer)
        if (attribution != HeartRules.Attribution.PVP_KILL || killer == null) return
        val ranked = rankedHeld(victim.uniqueId) || rankedHeld(killer.uniqueId)
        val inEvent = safe { inEvent(victim) } || safe { inEvent(killer) }
        process(
            dedupeKey = "coins:pvp:$bootId:${victim.uniqueId}:${Bukkit.getCurrentTick()}",
            killer = killer, victim = victim.uniqueId, victimName = victim.name, onlineVictim = victim,
            attribution = attribution, ranked = ranked, inEvent = inEvent, source = "pvp",
        )
    }

    /**
     * A Lifesteal Combat NPC (issue #1402) was killed: that is its offline owner's death, judged by
     * the same rules. Deduped on the NPC session id, so one NPC death moves coins at most once.
     */
    fun onCombatNpcDeath(sessionId: String, victim: UUID, victimName: String, killer: Player?, victimInLifesteal: Boolean) {
        if (!stealing || killer == null) return
        val attribution = attribute(victim, victimInLifesteal, killer.uniqueId, killer.takeIf { it.uniqueId != victim })
        if (attribution != HeartRules.Attribution.PVP_KILL) return
        process(
            dedupeKey = "coins:combatnpc:$sessionId",
            killer = killer, victim = victim, victimName = victimName, onlineVictim = Bukkit.getPlayer(victim)?.takeIf { it.isOnline },
            attribution = attribution, ranked = rankedHeld(killer.uniqueId), inEvent = safe { inEvent(killer) }, source = "combat-npc",
        )
    }

    /** The heart system's own attribution ([HeartRules.attribute]); coins don't need a loaded heart record. */
    private fun attribute(victim: UUID, victimInLifesteal: Boolean, causing: UUID?, killer: Player?): HeartRules.Attribution =
        HeartRules.attribute(
            HeartRules.Death(
                victim = victim,
                victimInLifesteal = victimInLifesteal,
                victimLoaded = true,
                causingPlayer = causing,
                killerOnline = killer?.isOnline == true,
                killerInLifesteal = killer != null && isLifesteal(killer),
                killerLoaded = true,
                killerIsNpc = killer != null && killer.hasMetadata("NPC"),
            )
        )

    private fun rankedHeld(uuid: UUID): Boolean = try {
        plugin.lifestealRankedManager.holds(uuid)
    } catch (_: UninitializedPropertyAccessException) {
        false
    }

    private fun process(
        dedupeKey: String, killer: Player, victim: UUID, victimName: String, onlineVictim: Player?,
        attribution: HeartRules.Attribution, ranked: Boolean, inEvent: Boolean, source: String,
    ) {
        val s = coinSettings ?: return
        val now = System.currentTimeMillis()
        val shared = sharedConnection(killer.uniqueId, victim)
        val death = BankRules.Death(
            attribution = attribution,
            inEvent = inEvent,
            inRankedMatch = ranked,
            window = try { plugin.lifestealProgressionManager.heartWindow(now) } catch (_: UninitializedPropertyAccessException) { ProgressionRules.HeartWindow.UNKNOWN },
            heartCheck = try { plugin.lifestealTeamManager.heartCheck(killer.uniqueId, victim) } catch (_: UninitializedPropertyAccessException) { TeamCombatRules.HeartCheck.UNKNOWN },
            relation = try { plugin.lifestealTeamManager.combatRelation(killer.uniqueId, victim, now) } catch (_: UninitializedPropertyAccessException) { TeamCombatRules.Relation.UNKNOWN },
            sharedConnection = shared,
            attacker = plugin.competitiveEligibility.verdict(killer, victim),
        )
        when (val e = BankRules.eligibility(death, s)) {
            is BankRules.Eligibility.Refused -> {
                (death.attacker as? com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.Verdict.Ineligible)?.let {
                    plugin.competitiveEligibility.reportBlocked(killer, victim, victimName, "COINS", it, dedupeKey)
                }
                if (e.reason != BankRules.StealRefusal.DISABLED && e.reason != BankRules.StealRefusal.NOT_PVP) {
                    plugin.logger.info("[Bank] ${killer.name} killed $victimName: no coins moved (${e.reason.log}).")
                }
                e.reason.killerMessage?.let { send(killer, it) }
                return
            }
            BankRules.Eligibility.Eligible -> Unit
        }
        val season = season()
        val request = BankStore.StealRequest(
            dedupeKey = dedupeKey, killer = killer.uniqueId, victim = victim, settings = s, now = now, season = season, ranked = ranked, source = source,
            offlineVictimNotice = if (onlineVictim == null) { amount, wallet -> victimText(s, amount, wallet, killer.name) } else null,
        )
        val result = try {
            store.steal(request)
        } catch (ex: Exception) {
            plugin.logger.severe("[Bank] Coin steal ${killer.name} <- $victimName ($dedupeKey) failed and was rolled back; no coins moved: ${ex.message}")
            recordFailure(dedupeKey, killer.uniqueId, BankStore.EntryType.PVP_GAIN, null, victim, now, season, source, ex)
            return
        }
        when (result) {
            BankStore.StealResult.Duplicate, BankStore.StealResult.Empty -> Unit
            is BankStore.StealResult.Cooldown -> BankRules.StealRefusal.COOLDOWN.killerMessage?.let { send(killer, it) }
            is BankStore.StealResult.Stolen -> {
                plugin.logger.info("[Bank] ${killer.name} stole ${BountyMoney.format(result.amountCents)} from $victimName ($source${if (ranked) ", ranked" else ""}).")
                send(killer, BankRules.fill(s.messages.killer, mapOf(
                    "amount" to BountyMoney.format(result.amountCents), "victim" to victimName, "wallet" to BountyMoney.format(result.killerWalletAfterCents),
                )))
                onlineVictim?.let { send(it, victimText(s, result.amountCents, result.victimWalletAfterCents, killer.name)) }
                if (s.antiFarmingEnabled) {
                    val reasons = buildList {
                        if (result.earlierInWindow + 1 >= s.repeatKillThreshold) {
                            add("repeated: ${result.earlierInWindow + 1} coin steals from this victim within ${s.repeatWindowMillis / 3_600_000L}h")
                        }
                        if (shared) add("killer and victim have shared a connection (hashed address; not proof of an alt)")
                    }
                    for (reason in reasons) {
                        try {
                            store.recordSuspicious(now, killer.uniqueId, victim, result.stealId, reason)
                        } catch (ex: Exception) {
                            plugin.logger.warning("[Bank] Could not record a suspicious coin steal: ${ex.message}")
                        }
                        plugin.logger.warning("[Bank] Suspicious: ${killer.name} stole from $victimName: $reason.")
                    }
                }
            }
        }
    }

    private fun victimText(s: CoinStealSettings, amount: Long, wallet: Long, killerName: String): String =
        BankRules.fill(s.messages.victim, mapOf("amount" to BountyMoney.format(amount), "killer" to killerName, "wallet" to BountyMoney.format(wallet)))

    /** AltManager's `player_connections` (salted hashes of connection addresses), the same signal the bounties log. */
    private fun sharedConnection(a: UUID, b: UUID): Boolean = try {
        plugin.databaseManager.queryFirst(
            "SELECT 1 FROM player_connections pa JOIN player_connections pb ON pb.connection_hash = pa.connection_hash WHERE pa.uuid = ? AND pb.uuid = ?",
            a.toString(), b.toString(),
        ) { 1 } != null
    } catch (_: Exception) {
        false
    }

    // ── Notices ─────────────────────────────────────────────

    /** Coins lost while offline (a Combat NPC death) are told on the next join, once. */
    fun onJoin(player: Player) {
        if (!ready) return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            val notices = try { store.takeNotices(player.uniqueId, System.currentTimeMillis()) } catch (e: Exception) {
                plugin.logger.warning("[Bank] Could not read the notices of ${player.name}: ${e.message}")
                return@Runnable
            }
            notices.forEach { send(player, it.message) }
        }, 40L)
    }

    fun onQuit(player: Player) {
        menu.forget(player.uniqueId)
    }

    // ── Administrative adjustments ──────────────────────────

    fun adjust(actor: String, uuid: UUID, op: BankStore.AdminOp, cents: Long, reason: String): BankStore.AdminResult {
        val now = System.currentTimeMillis()
        val season = season()
        val txId = "admin:" + UUID.randomUUID()
        return try {
            store.adjust(BankStore.AdminRequest(txId, uuid, op, cents, effectiveBank.adminMaxAdjustmentCents, actor, reason, now, season))
        } catch (e: Exception) {
            plugin.logger.severe("[Bank] Admin ${op.name} on $uuid by $actor failed and was rolled back: ${e.message}")
            recordFailure(txId, uuid, if (op == BankStore.AdminOp.ADD) BankStore.EntryType.ADMIN_ADD else BankStore.EntryType.ADMIN_REMOVE, cents, null, now, season, "admin", e)
            BankStore.AdminResult.Refused("The adjustment failed and nothing changed.")
        }
    }

    // ── Messages ────────────────────────────────────────────

    fun send(player: Player, legacy: String) {
        if (player.isOnline) plugin.commsManager.send(player, plugin.commsManager.parseLegacy(legacy))
    }
}
