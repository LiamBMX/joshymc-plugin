package com.liam.joshymc.flight

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.AntiDupeManager
import com.liam.joshymc.util.giveItemSafely
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Physical Fly Vouchers (issue #1304, Survival only): Feather items that, on right-click,
 * grant the temporary LuckPerms node `joshymc.fly` for their duration (issue #1408, see
 * [FlyVoucherGrants]; before that they only added to a JoshyMC balance that `/fly`'s
 * command permission never let through). Built like the Credit Vouchers
 * ([com.liam.joshymc.manager.CreditVoucherManager]): identified by PDC only (never name or
 * lore), one random voucher uuid each (so they never stack), and that uuid can be redeemed
 * once ever (`fly_voucher_redemptions` primary key).
 *
 * On top of that every voucher carries an HMAC of its version, uuid and duration, keyed by a
 * random per-server secret (`fly_voucher_secret`), so editing the duration or writing a
 * voucher by hand with an NBT tool is refused.
 */
class FlyVoucherManager(private val plugin: Joshymc) {

    val voucherTypeKey = NamespacedKey(plugin, "voucher_type")
    val voucherVersionKey = NamespacedKey(plugin, "voucher_version")
    val voucherUuidKey = NamespacedKey(plugin, "voucher_uuid")
    val voucherSecondsKey = NamespacedKey(plugin, "fly_voucher_seconds")
    val voucherSignatureKey = NamespacedKey(plugin, "fly_voucher_signature")

    private lateinit var secret: ByteArray

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS fly_voucher_redemptions (
                voucher_uuid TEXT PRIMARY KEY,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                seconds INTEGER NOT NULL,
                ends_before INTEGER,
                ends_after INTEGER NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS fly_voucher_secret (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                secret TEXT NOT NULL
            )
        """.trimIndent())
        // Issue #1408: PENDING while LuckPerms is being written, COMPLETED once it confirmed,
        // INTERRUPTED when a crash left it unknowable. Rows from before are all COMPLETED.
        try {
            plugin.databaseManager.execute("ALTER TABLE fly_voucher_redemptions ADD COLUMN status TEXT NOT NULL DEFAULT 'COMPLETED'")
        } catch (_: Exception) {
            // Column already exists.
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        plugin.databaseManager.execute(
            "INSERT OR IGNORE INTO fly_voucher_secret (id, secret) VALUES (1, ?)",
            fresh.joinToString("") { "%02x".format(it) }
        )
        val hex = plugin.databaseManager.queryFirst("SELECT secret FROM fly_voucher_secret WHERE id = 1") { it.getString("secret") }
            ?: error("fly_voucher_secret is empty")
        secret = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    // ── Item building / identification ─────────────────────────────────────

    fun createVoucherStack(seconds: Long): ItemStack {
        require(seconds in 1..SurvivalFlight.MAX_VOUCHER_SECONDS) { "Invalid Fly Voucher duration: $seconds" }
        val voucherUuid = UUID.randomUUID().toString()
        val stack = ItemStack(Material.FEATHER, 1)
        stack.editMeta { meta ->
            meta.displayName(Component.text("Fly Voucher", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(
                Component.text("Duration: ", NamedTextColor.GRAY).append(Component.text(SurvivalFlight.long(seconds), NamedTextColor.WHITE)),
                Component.empty(),
                Component.text("Right-click to redeem!", NamedTextColor.GRAY),
                Component.text("Adds ${SurvivalFlight.long(seconds).lowercase()} of flight time.", NamedTextColor.GREEN),
                Component.empty(),
                Component.text("Use /flytime to check your remaining time.", NamedTextColor.DARK_GRAY),
                Component.text("Survival only.", NamedTextColor.DARK_GRAY)
            ).map { it.decoration(TextDecoration.ITALIC, false) })
            val pdc = meta.persistentDataContainer
            pdc.set(voucherTypeKey, PersistentDataType.STRING, TYPE)
            pdc.set(voucherVersionKey, PersistentDataType.INTEGER, VOUCHER_VERSION)
            pdc.set(voucherUuidKey, PersistentDataType.STRING, voucherUuid)
            pdc.set(voucherSecondsKey, PersistentDataType.LONG, seconds)
            pdc.set(voucherSignatureKey, PersistentDataType.STRING,
                SurvivalFlight.sign(secret, SurvivalFlight.signedPayload(VOUCHER_VERSION, voucherUuid, seconds)))
        }
        return stack
    }

    fun isFlyVoucher(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.get(voucherTypeKey, PersistentDataType.STRING) == TYPE
    }

    /** Gives [quantity] separate 1-count vouchers (never a stack) of [seconds] each. */
    fun give(player: Player, seconds: Long, quantity: Int) {
        repeat(quantity) { plugin.giveItemSafely(player, createVoucherStack(seconds)) }
    }

    /** Why [template] (a crate reward / kit copy source) can't be handed out, or null (issue #1529). */
    fun templateProblem(template: ItemStack): String? =
        if (verify(template) == null) "is a Fly Voucher that is malformed or not signed by this server" else null

    /**
     * Issue #1529: a copy of [template] with its own fresh voucher uuid and the signature for that
     * uuid, amount 1, same duration. Before this, crate-won Fly Vouchers kept the template's uuid,
     * so only the first winner could redeem one (`fly_voucher_redemptions` is keyed by it). Null
     * when the template doesn't verify: an invalid voucher is never signed into a valid one.
     */
    fun reissue(template: ItemStack): ItemStack? {
        if (!isFlyVoucher(template)) return null
        val verified = verify(template) ?: return null
        val voucherUuid = UUID.randomUUID().toString()
        val copy = template.clone()
        copy.amount = 1
        copy.editMeta { meta ->
            val pdc = meta.persistentDataContainer
            pdc.set(voucherUuidKey, PersistentDataType.STRING, voucherUuid)
            pdc.set(voucherSignatureKey, PersistentDataType.STRING,
                SurvivalFlight.sign(secret, SurvivalFlight.signedPayload(VOUCHER_VERSION, voucherUuid, verified.seconds)))
        }
        return copy
    }

    private data class Verified(val voucherUuid: String, val seconds: Long)

    /** The voucher's uuid and duration, or null unless every field is present, in range and signed by this server. */
    private fun verify(item: ItemStack): Verified? {
        val pdc = item.itemMeta?.persistentDataContainer ?: return null
        val version = pdc.get(voucherVersionKey, PersistentDataType.INTEGER) ?: return null
        val voucherUuid = pdc.get(voucherUuidKey, PersistentDataType.STRING) ?: return null
        val seconds = runCatching { pdc.get(voucherSecondsKey, PersistentDataType.LONG) }.getOrNull() ?: return null
        val signature = pdc.get(voucherSignatureKey, PersistentDataType.STRING)
        if (version != VOUCHER_VERSION) return null
        if (runCatching { UUID.fromString(voucherUuid) }.isFailure) return null
        if (seconds !in 1..SurvivalFlight.MAX_VOUCHER_SECONDS) return null
        if (!SurvivalFlight.verify(secret, SurvivalFlight.signedPayload(version, voucherUuid, seconds), signature)) return null
        return Verified(voucherUuid, seconds)
    }

    // ── Redemption (issue #1408: LuckPerms timed node) ───────────────────

    /** Voucher uuids whose LuckPerms write is still running: join recovery leaves them alone. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private fun failed(player: Player) {
        plugin.commsManager.send(
            player,
            Component.text("Your Fly Voucher could not be redeemed.", NamedTextColor.RED)
                .append(Component.newline())
                .append(Component.text("Please try again or contact staff.", NamedTextColor.GRAY))
        )
    }

    private fun refusal(player: Player, plan: FlyVoucherGrants.Plan) {
        val text = when (plan) {
            FlyVoucherGrants.Plan.Permanent ->
                Component.text("You already have permanent flight, so this voucher wasn't used.", NamedTextColor.YELLOW)
            FlyVoucherGrants.Plan.Denied ->
                Component.text("Your flight access has been disabled by staff, so this voucher wasn't used.", NamedTextColor.RED)
            FlyVoucherGrants.Plan.TooLong ->
                Component.text("You can't hold that much flight time (max ${SurvivalFlight.long(SurvivalFlight.MAX_BALANCE_MILLIS / 1000)}).", NamedTextColor.RED)
            else -> return failed(player)
        }
        plugin.commsManager.send(player, text)
    }

    private fun writer(): LuckPermsFlyGrant? = try {
        LuckPermsFlyGrant.current()
    } catch (e: Exception) {
        plugin.logger.warning("[FlyVouchers] LuckPerms API couldn't be used: ${e.message}")
        null
    }

    /**
     * Redeems exactly one voucher held in the main hand into a LuckPerms timed `joshymc.fly`
     * node (see [FlyVoucherGrants]). Steps:
     *  1. checks (gamemode, signature, permanent/denied flight, maximum) with nothing written;
     *  2. the voucher uuid is reserved as a PENDING `fly_voucher_redemptions` row (its primary
     *     key, so a copy of the voucher can never be redeemed alongside it);
     *  3. LuckPerms adds the node and saves the user (asynchronous; one change per player at
     *     a time, so clicks during it are ignored);
     *  4. only once LuckPerms confirmed it: the row becomes COMPLETED and the voucher is taken.
     * On a LuckPerms failure the row is deleted again and the voucher stays where it is. A
     * crash between 3 and 4 leaves the row PENDING; [recoverPending] settles it from what
     * LuckPerms actually holds when the player next joins. LuckPerms and SQLite are not one
     * atomic write, so that recovery is the safety net, not an atomic commit.
     */
    fun redeem(player: Player, heldItem: ItemStack) {
        if (!isFlyVoucher(heldItem)) return

        if (heldItem.amount != 1) {
            // Legit vouchers never stack (unique uuid); a stack can only come from NBT editing.
            plugin.commsManager.send(player, Component.text("This voucher stack is invalid and cannot be redeemed.", NamedTextColor.RED))
            plugin.logger.warning("[FlyVouchers] ${player.name} attempted to redeem a malformed voucher stack (amount=${heldItem.amount}).")
            return
        }

        val uuid = player.uniqueId
        // Silently ignored while another redemption of theirs is in progress (no message spam).
        if (!plugin.flightTimeManager.tryLock(uuid)) return
        var handedOff = false
        var reservedUuid: String? = null
        try {
            if (plugin.gamemodeLockManager.gamemodeOf(player) != ServerGamemode.SURVIVAL) {
                plugin.commsManager.send(player, Component.text("Fly Vouchers can only be redeemed in Survival.", NamedTextColor.RED))
                return
            }

            val voucher = verify(heldItem)
            if (voucher == null) {
                plugin.commsManager.send(player, Component.text("This voucher is invalid and cannot be redeemed.", NamedTextColor.RED))
                plugin.logger.warning("[FlyVouchers] ${player.name} attempted to redeem an invalid or tampered Fly Voucher.")
                return
            }

            val status = plugin.databaseManager.queryFirst(
                "SELECT status FROM fly_voucher_redemptions WHERE voucher_uuid = ?", voucher.voucherUuid
            ) { it.getString("status") }
            if (status != null) {
                alreadyRedeemed(player, voucher, status)
                return
            }

            // Permanent flight (rank, op, staff) already covers everything; temporary time runs
            // on real time, so adding it would only burn it. Keep the voucher (it can be traded).
            if (plugin.flightTimeManager.luckPermsAccess(player) == FlightTime.Access.Permanent) {
                refusal(player, FlyVoucherGrants.Plan.Permanent)
                return
            }

            val lp = writer()
            val nodes = lp?.let { runCatching { it.loadedNodes(uuid) }.getOrNull() }
            if (lp == null || nodes == null) {
                plugin.logger.warning("[FlyVouchers] ${player.name}'s Fly Voucher wasn't redeemed: LuckPerms isn't available or hasn't loaded them.")
                failed(player)
                return
            }

            val nowSec = FlyVoucherGrants.nowSeconds(System.currentTimeMillis())
            // An older JoshyMC balance (issue #1304) not yet moved into LuckPerms is carried over.
            val legacy = plugin.flightTimeManager.balance(uuid)
                ?.takeIf { !it.lpSynced && !it.ended }
                ?.let { Math.floorDiv(it.endsAt + 999, 1000L) }
            val plan = FlyVoucherGrants.extend(nodes, voucher.seconds, nowSec, legacy)
            if (plan !is FlyVoucherGrants.Plan.Grant) {
                refusal(player, plan)
                return
            }

            val reserved = plugin.databaseManager.executeUpdate(
                "INSERT OR IGNORE INTO fly_voucher_redemptions (voucher_uuid, uuid, player_name, seconds, ends_before, ends_after, timestamp, status) VALUES (?, ?, ?, ?, ?, ?, ?, '$PENDING')",
                voucher.voucherUuid, uuid.toString(), player.name, voucher.seconds,
                plan.previousExpiresAt?.times(1000), plan.expiresAt * 1000, System.currentTimeMillis()
            ) > 0
            if (!reserved) {
                alreadyRedeemed(player, voucher, PENDING)
                return
            }
            reservedUuid = voucher.voucherUuid
            inFlight += voucher.voucherUuid

            val future = lp.extend(uuid, voucher.seconds, nowSec, legacy)
            handedOff = true
            future.whenComplete { outcome, error ->
                val settle = Runnable {
                    try {
                        settle(uuid, voucher, plan.expiresAt, outcome, error)
                    } finally {
                        inFlight -= voucher.voucherUuid
                        plugin.flightTimeManager.unlock(uuid)
                    }
                }
                try {
                    Bukkit.getScheduler().runTask(plugin, settle)
                } catch (_: Exception) {
                    // Plugin disabling: the row stays PENDING and is settled on their next join.
                    inFlight -= voucher.voucherUuid
                    plugin.flightTimeManager.unlock(uuid)
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("[FlyVouchers] Redemption failed for ${player.name}: ${e.message}")
            if (!handedOff && reservedUuid != null) release(reservedUuid)
            failed(player)
        } finally {
            if (!handedOff) {
                reservedUuid?.let { inFlight -= it }
                plugin.flightTimeManager.unlock(uuid)
            }
        }
    }

    private fun alreadyRedeemed(player: Player, voucher: Verified, status: String) {
        if (status == PENDING) {
            plugin.commsManager.send(player, Component.text("This voucher is still being redeemed. Rejoin or contact staff if your flight time doesn't show up.", NamedTextColor.YELLOW))
            return
        }
        plugin.antiDupeManager.record(
            player,
            "Duplicate Voucher ID Redemption",
            AntiDupeManager.RiskLevel.CRITICAL,
            item = "Fly Voucher",
            amount = SurvivalFlight.short(voucher.seconds),
            source = "Fly Voucher",
            transactionId = voucher.voucherUuid
        )
        plugin.commsManager.send(player, Component.text("This voucher has already been redeemed.", NamedTextColor.RED))
    }

    /** Main thread, once LuckPerms answered. */
    private fun settle(uuid: UUID, voucher: Verified, plannedSec: Long, outcome: LuckPermsFlyGrant.Outcome?, error: Throwable?) {
        val player = Bukkit.getPlayer(uuid)
        val plan = outcome?.plan
        if (error == null && plan is FlyVoucherGrants.Plan.Grant && outcome.written) {
            complete(uuid, voucher, plan.expiresAt, plan.previousExpiresAt)
            plugin.logger.info("[FlyVouchers] ${player?.name ?: uuid} redeemed a ${SurvivalFlight.short(voucher.seconds)} Fly Voucher (${voucher.voucherUuid}): ${FlyVoucherGrants.commandEquivalent(uuid, voucher.seconds)}, expires at epoch second ${plan.expiresAt}.")
            player?.let { announce(it, voucher.seconds, plan.expiresAt) }
            return
        }
        if (error == null && plan != null) {
            // LuckPerms changed between the check and the write (e.g. staff made flight permanent): nothing written.
            release(voucher.voucherUuid)
            player?.let { refusal(it, plan) }
            return
        }

        // LuckPerms reported an error. An online user's change can already be live in memory
        // even when saving failed, so look at what LuckPerms actually holds before deciding.
        plugin.logger.warning("[FlyVouchers] LuckPerms failed to grant ${voucher.voucherUuid} to ${player?.name ?: uuid}: ${error?.message}")
        when (resolveFromLuckPerms(uuid, voucher.voucherUuid, plannedSec)) {
            FlyVoucherGrants.Recovery.COMPLETED -> player?.let {
                val now = FlyVoucherGrants.nowSeconds(System.currentTimeMillis())
                val current = writer()?.loadedNodes(uuid)?.let { n -> FlyVoucherGrants.currentExpiry(n, now) } ?: plannedSec
                announce(it, voucher.seconds, current)
            }
            else -> player?.let { failed(it) }
        }
    }

    /** Marks the row COMPLETED, mirrors the expiry for the landing grace, then takes the voucher. */
    private fun complete(uuid: UUID, voucher: Verified, expiresAtSec: Long, previousSec: Long?) {
        try {
            plugin.databaseManager.transaction {
                plugin.databaseManager.executeUpdate(
                    "UPDATE fly_voucher_redemptions SET status = '$COMPLETED', ends_before = ?, ends_after = ? WHERE voucher_uuid = ? AND status = '$PENDING'",
                    previousSec?.times(1000), expiresAtSec * 1000, voucher.voucherUuid
                )
                plugin.flightTimeManager.writeEnd(uuid, expiresAtSec * 1000)
            }
            plugin.flightTimeManager.applyWritten(uuid, expiresAtSec * 1000)
        } catch (e: Exception) {
            // LuckPerms already has the flight. The row stays PENDING and recovery marks it
            // COMPLETED on their next join (LuckPerms shows the planned expiry).
            plugin.logger.severe("[FlyVouchers] LuckPerms granted ${voucher.voucherUuid} but the redemption row couldn't be completed: ${e.message}")
        }
        val player = Bukkit.getPlayer(uuid)
        if (player != null && !takeVoucher(player, voucher.voucherUuid)) {
            // Moved out of their inventory while LuckPerms saved. Its uuid is redeemed, so the copy is worthless.
            plugin.logger.info("[FlyVouchers] Redeemed voucher ${voucher.voucherUuid} was no longer in ${player.name}'s inventory; that item can't be redeemed again.")
        }
    }

    private fun announce(player: Player, addedSeconds: Long, expiresAtSec: Long) {
        val remaining = (expiresAtSec - FlyVoucherGrants.nowSeconds(System.currentTimeMillis())).coerceAtLeast(0)
        plugin.commsManager.send(
            player,
            Component.text("Your Fly Voucher has been redeemed!", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true)
                .append(Component.newline())
                .append(Component.text("Flight Time Added: ", NamedTextColor.GRAY)).append(Component.text(SurvivalFlight.long(addedSeconds), NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("Total Flight Time Remaining: ", NamedTextColor.GRAY)).append(Component.text(SurvivalFlight.long(remaining), NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(Component.text("Use /fly to fly and /flytime to check your remaining time.", NamedTextColor.GRAY))
        )
    }

    /** Removes exactly one item: the voucher with [voucherUuid], wherever it is in their inventory or on the cursor. */
    private fun takeVoucher(player: Player, voucherUuid: String): Boolean {
        fun matches(item: ItemStack?): Boolean =
            isFlyVoucher(item) && item!!.itemMeta?.persistentDataContainer?.get(voucherUuidKey, PersistentDataType.STRING) == voucherUuid
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot)
            if (!matches(item)) continue
            if (item!!.amount > 1) item.amount -= 1 else inventory.setItem(slot, null)
            return true
        }
        val cursor = player.itemOnCursor
        if (matches(cursor)) {
            if (cursor.amount > 1) cursor.amount -= 1 else player.setItemOnCursor(null)
            return true
        }
        return false
    }

    private fun release(voucherUuid: String) {
        try {
            plugin.databaseManager.executeUpdate(
                "DELETE FROM fly_voucher_redemptions WHERE voucher_uuid = ? AND status = '$PENDING'", voucherUuid
            )
        } catch (e: Exception) {
            plugin.logger.severe("[FlyVouchers] Couldn't release PENDING voucher $voucherUuid: ${e.message}")
        }
    }

    /**
     * Settles one PENDING row from what LuckPerms holds for the (online, so loaded) player:
     * COMPLETED when it has the planned expiry, released when it clearly doesn't, INTERRUPTED
     * (left for staff, never retried) when that can no longer be told. Null when LuckPerms
     * can't be read (stays PENDING).
     */
    private fun resolveFromLuckPerms(uuid: UUID, voucherUuid: String, plannedSec: Long): FlyVoucherGrants.Recovery? {
        val nodes = writer()?.let { runCatching { it.loadedNodes(uuid) }.getOrNull() } ?: return null
        val nowSec = FlyVoucherGrants.nowSeconds(System.currentTimeMillis())
        val decision = FlyVoucherGrants.recover(plannedSec, FlyVoucherGrants.currentExpiry(nodes, nowSec), nowSec)
        try {
            when (decision) {
                FlyVoucherGrants.Recovery.COMPLETED -> plugin.databaseManager.executeUpdate(
                    "UPDATE fly_voucher_redemptions SET status = '$COMPLETED' WHERE voucher_uuid = ? AND status = '$PENDING'", voucherUuid
                )
                FlyVoucherGrants.Recovery.RELEASE -> release(voucherUuid)
                FlyVoucherGrants.Recovery.INTERRUPTED -> plugin.databaseManager.executeUpdate(
                    "UPDATE fly_voucher_redemptions SET status = '$INTERRUPTED' WHERE voucher_uuid = ? AND status = '$PENDING'", voucherUuid
                )
            }
        } catch (e: Exception) {
            plugin.logger.warning("[FlyVouchers] Couldn't settle PENDING voucher $voucherUuid: ${e.message}")
            return null
        }
        val log = "[FlyVouchers] Settled interrupted redemption $voucherUuid for $uuid: $decision (planned expiry epoch second $plannedSec)."
        if (decision == FlyVoucherGrants.Recovery.INTERRUPTED) plugin.logger.warning("$log Check LuckPerms by hand.") else plugin.logger.info(log)
        if (decision == FlyVoucherGrants.Recovery.COMPLETED) Bukkit.getPlayer(uuid)?.let { takeVoucher(it, voucherUuid) }
        return decision
    }

    /** On join: settles redemptions a crash or shutdown left PENDING (never ones still running). */
    fun recoverPending(player: Player) {
        val rows = try {
            plugin.databaseManager.query(
                "SELECT voucher_uuid, ends_after FROM fly_voucher_redemptions WHERE uuid = ? AND status = '$PENDING'",
                player.uniqueId.toString()
            ) { it.getString("voucher_uuid") to it.getLong("ends_after") }
        } catch (e: Exception) {
            plugin.logger.warning("[FlyVouchers] Couldn't read pending redemptions for ${player.name}: ${e.message}")
            return
        }
        for ((voucherUuid, endsAfterMillis) in rows) {
            if (voucherUuid in inFlight) continue
            resolveFromLuckPerms(player.uniqueId, voucherUuid, Math.floorDiv(endsAfterMillis + 999, 1000L))
        }
    }

    companion object {
        const val TYPE = "fly"
        const val VOUCHER_VERSION = 1
        const val MAX_QUANTITY = 64

        const val PENDING = "PENDING"
        const val COMPLETED = "COMPLETED"
        const val INTERRUPTED = "INTERRUPTED"
    }
}
