package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.giveItemSafely
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * Physical Rank Vouchers (issue #595) — real, tradeable Name Tag items that
 * upgrade a player's donor tier through [RankManager.grantDonorTier]. Only the
 * seven [DonorTiers] are supported; staff ranks are never voucher-redeemable.
 * Vouchers never downgrade and are never consumed if the player already holds
 * an equal or higher tier, which [RankManager.currentDonorTier] resolves from
 * JoshyMC's records plus live LuckPerms groups, so it survives restarts.
 *
 * A redemption only becomes COMPLETED once LuckPerms shows the new group; until
 * then its rank_voucher_redemptions row is PENDING. If the grant can't be
 * confirmed, the voucher comes back and its id is released. If the player
 * leaves (or the server stops) mid-confirmation, the row is settled when they
 * next join (issue #1173).
 */
class RankVoucherManager(private val plugin: Joshymc) : Listener {

    val voucherTypeKey = NamespacedKey(plugin, "voucher_type")
    val voucherRankKey = NamespacedKey(plugin, "voucher_rank")
    val voucherVersionKey = NamespacedKey(plugin, "voucher_version")
    val voucherUuidKey = NamespacedKey(plugin, "voucher_uuid")

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS rank_voucher_redemptions (
                voucher_uuid TEXT PRIMARY KEY,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                rank_id TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())
        // Rows written before issue #1173 were all finished redemptions, hence the default.
        try {
            plugin.databaseManager.execute(
                "ALTER TABLE rank_voucher_redemptions ADD COLUMN status TEXT NOT NULL DEFAULT '$STATUS_COMPLETED'"
            )
        } catch (_: Exception) {}

        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            Bukkit.getOnlinePlayers().forEach { resolvePending(it, 0) }
        }, PENDING_RESOLVE_DELAY_TICKS)
    }

    // The hierarchy lives in DonorTiers so vouchers, the Credit Shop and /rank agree (issue #1173).
    fun getSupportedRankIds(): List<String> = DonorTiers.ORDER

    fun isSupportedRank(rankId: String): Boolean = DonorTiers.isTier(rankId)

    fun displayName(rankId: String): String = DonorTiers.displayName(rankId)

    // ── Item building / identification ─────────────────────────────────────

    fun createVoucherStack(rankId: String): ItemStack? {
        val id = rankId.lowercase()
        if (!isSupportedRank(id)) return null
        val name = displayName(id)
        val stack = ItemStack(Material.NAME_TAG, 1)
        stack.editMeta { meta ->
            meta.displayName(
                Component.text("$name Rank Voucher", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(listOf(
                Component.text("Right-click to redeem", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Rank: $name", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
            ))
            meta.persistentDataContainer.set(voucherTypeKey, PersistentDataType.STRING, "rank")
            meta.persistentDataContainer.set(voucherRankKey, PersistentDataType.STRING, id)
            meta.persistentDataContainer.set(voucherVersionKey, PersistentDataType.INTEGER, VOUCHER_VERSION)
            meta.persistentDataContainer.set(voucherUuidKey, PersistentDataType.STRING, UUID.randomUUID().toString())
        }
        return stack
    }

    fun isRankVoucher(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.get(voucherTypeKey, PersistentDataType.STRING) == "rank"
    }

    /** Gives [quantity] separate 1-count voucher items (never a stack) for [rankId]. Returns false for an unsupported rank. */
    fun give(player: Player, rankId: String, quantity: Int): Boolean {
        if (!isSupportedRank(rankId)) return false
        repeat(quantity) {
            val stack = createVoucherStack(rankId) ?: return@repeat
            plugin.giveItemSafely(player, stack)
        }
        return true
    }

    // ── Redemption ───────────────────────────────────────────────────────

    fun redeem(player: Player, heldItem: ItemStack, takeOne: () -> Unit) {
        if (!isRankVoucher(heldItem)) return

        // A legitimate voucher is always amount == 1 (non-stacking is enforced by the
        // unique PDC uuid). Amount > 1 can only happen via an external NBT-editing tool
        // that force-stacked identical PDC onto multiple items — refuse rather than
        // apply the rank upgrade repeatedly.
        if (heldItem.amount != 1) {
            send(player, "This voucher stack is invalid and cannot be redeemed.", NamedTextColor.RED)
            plugin.logger.warning("[RankVouchers] ${player.name} attempted to redeem a malformed voucher stack (amount=${heldItem.amount}).")
            return
        }

        val pdc = heldItem.itemMeta?.persistentDataContainer
        val version = pdc?.get(voucherVersionKey, PersistentDataType.INTEGER)
        val rawRank = pdc?.get(voucherRankKey, PersistentDataType.STRING)
        val rankId = DonorTiers.normalize(rawRank)
        val voucherUuid = pdc?.get(voucherUuidKey, PersistentDataType.STRING)

        if (version != VOUCHER_VERSION || rankId == null || voucherUuid == null) {
            send(player, "This voucher is invalid and cannot be redeemed.", NamedTextColor.RED)
            plugin.logger.warning("[RankVouchers] ${player.name} attempted to redeem a malformed voucher (version=$version, rank=$rawRank, uuid=$voucherUuid).")
            return
        }

        // Shared with Credit Shop rank packages: one donor change per player at a time,
        // held until the grant is confirmed or rolled back.
        if (!plugin.rankManager.tryLockDonorChange(player.uniqueId)) {
            send(player, "Your last rank change is still processing.", NamedTextColor.RED)
            return
        }
        var handedOff = false
        try {
            if (hasPendingDonorGrant(player.uniqueId)) {
                send(player, "A previous rank upgrade is still being confirmed. Try again in a moment.", NamedTextColor.RED)
                return
            }

            val currentTier = plugin.rankManager.currentDonorTier(player.uniqueId)
            when (DonorTiers.compare(currentTier, rankId)) {
                DonorTiers.Comparison.SAME_TIER -> {
                    send(player, "You already have the ${displayName(rankId)} rank. Your voucher was not used.", NamedTextColor.YELLOW)
                    plugin.logger.info("[RankVouchers] Rejected ${displayName(rankId)} voucher from ${player.name} (${player.uniqueId}): already holds that tier.")
                    return
                }
                DonorTiers.Comparison.LOWER_TIER -> {
                    send(player, "You already have ${displayName(currentTier!!)}, which is higher than ${displayName(rankId)}. Your voucher was not used.", NamedTextColor.YELLOW)
                    plugin.logger.info("[RankVouchers] Rejected ${displayName(rankId)} voucher from ${player.name} (${player.uniqueId}): holds higher tier $currentTier.")
                    return
                }
                DonorTiers.Comparison.UPGRADE -> {}
            }

            if (!plugin.rankManager.isLuckPermsAvailable()) {
                send(player, "Rank vouchers can't be redeemed right now. Your voucher was not used.", NamedTextColor.RED)
                plugin.logger.warning("[RankVouchers] ${player.name} tried to redeem a ${displayName(rankId)} voucher but LuckPerms is not enabled.")
                return
            }

            // Atomically reserve this voucher's unique id first, same anti-dupe idiom as
            // CreditVoucherManager — stops a duplicated physical item (same PDC uuid) from
            // ever redeeming twice. The row stays PENDING until LuckPerms confirms.
            if (!reserveVoucherId(voucherUuid, player, rankId)) {
                plugin.antiDupeManager.record(
                    player,
                    "Duplicate Voucher ID Redemption",
                    AntiDupeManager.RiskLevel.CRITICAL,
                    item = "Rank Voucher (${displayName(rankId)})",
                    source = "Rank Voucher",
                    transactionId = voucherUuid
                )
                send(player, "This voucher has already been redeemed.", NamedTextColor.RED)
                return
            }

            takeOne()
            send(player, "Applying the ${displayName(rankId)} rank...", NamedTextColor.GRAY)
            handedOff = true
            plugin.rankManager.grantDonorTier(player, rankId) { result ->
                try {
                    finishRedemption(player, voucherUuid, rankId, currentTier, result)
                } finally {
                    plugin.rankManager.unlockDonorChange(player.uniqueId)
                }
            }
        } finally {
            if (!handedOff) plugin.rankManager.unlockDonorChange(player.uniqueId)
        }
    }

    private fun finishRedemption(
        player: Player,
        voucherUuid: String,
        rankId: String,
        previousTier: String?,
        result: RankManager.DonorGrantResult
    ) {
        when (result) {
            RankManager.DonorGrantResult.CONFIRMED -> {
                markCompleted(voucherUuid)
                send(player, "You redeemed the ${displayName(rankId)} rank!", NamedTextColor.GREEN)
                plugin.logger.info("[RankVouchers] ${player.name} (${player.uniqueId}) redeemed ${displayName(rankId)} (was ${previousTier ?: "none"}), voucher $voucherUuid.")
            }
            // Stays PENDING; resolvePending settles it on their next join.
            RankManager.DonorGrantResult.PLAYER_LEFT -> {}
            else -> {
                if (returnVoucher(player, voucherUuid, rankId)) {
                    send(player, "Your ${displayName(rankId)} rank couldn't be confirmed, so your voucher has been returned.", NamedTextColor.RED)
                }
                plugin.logger.warning("[RankVouchers] Rank upgrade to $rankId failed for ${player.name} (${player.uniqueId}): $result. Voucher returned.")
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { resolvePending(player, 0) }, PENDING_RESOLVE_DELAY_TICKS)
    }

    /**
     * Settles PENDING redemptions left by a player who quit (or a server that stopped)
     * before LuckPerms confirmed. LuckPerms has loaded their groups by now: if the tier is
     * there the redemption completes, otherwise the request is undone and the voucher is
     * returned. Each row is claimed with a conditional update, so it settles exactly once.
     */
    private fun resolvePending(player: Player, attempt: Int) {
        if (!player.isOnline) return
        val rows = plugin.databaseManager.query(
            "SELECT voucher_uuid, rank_id FROM rank_voucher_redemptions WHERE uuid = ? AND status = ?",
            player.uniqueId.toString(), STATUS_PENDING
        ) { rs -> rs.getString("voucher_uuid") to rs.getString("rank_id") }
        if (rows.isEmpty()) return
        if (!plugin.rankManager.isLuckPermsAvailable()) {
            plugin.logger.warning("[RankVouchers] ${player.name} has ${rows.size} pending voucher redemption(s) but LuckPerms is not enabled; leaving them pending.")
            return
        }
        if (!plugin.rankManager.tryLockDonorChange(player.uniqueId)) {
            if (attempt < PENDING_RESOLVE_MAX_ATTEMPTS) {
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { resolvePending(player, attempt + 1) }, PENDING_RESOLVE_DELAY_TICKS)
            }
            return
        }
        try {
            for ((voucherUuid, rawRank) in rows) {
                val rankId = DonorTiers.normalize(rawRank)
                if (rankId == null) {
                    plugin.logger.warning("[RankVouchers] Pending voucher $voucherUuid for ${player.name} names unknown tier '$rawRank'; left for an admin.")
                    continue
                }
                if (plugin.rankManager.isDonorGroupConfirmed(player, rankId)) {
                    if (markCompleted(voucherUuid)) {
                        plugin.rankManager.completeDonorGrant(player.uniqueId, rankId)
                        send(player, "Your ${displayName(rankId)} rank voucher redemption is complete.", NamedTextColor.GREEN)
                        plugin.logger.info("[RankVouchers] Settled pending voucher $voucherUuid for ${player.name}: $rankId confirmed in LuckPerms.")
                    }
                } else {
                    plugin.rankManager.revokeUnconfirmedDonorGrant(player.uniqueId, rankId)
                    if (returnVoucher(player, voucherUuid, rankId)) {
                        send(player, "Your ${displayName(rankId)} rank upgrade didn't go through, so your voucher has been returned.", NamedTextColor.RED)
                        plugin.logger.warning("[RankVouchers] Settled pending voucher $voucherUuid for ${player.name}: $rankId not in LuckPerms, voucher returned.")
                    }
                }
            }
        } finally {
            plugin.rankManager.unlockDonorChange(player.uniqueId)
        }
    }

    /** True while [uuid] has an unconfirmed voucher redemption or Credit Shop rank purchase. */
    private fun hasPendingDonorGrant(uuid: UUID): Boolean =
        hasPendingRedemption(uuid) || plugin.voucherManager.hasPendingRankPurchase(uuid) ||
            plugin.freeRankManager.hasPending(uuid)

    fun hasPendingRedemption(uuid: UUID): Boolean =
        plugin.databaseManager.queryFirst(
            "SELECT 1 AS found FROM rank_voucher_redemptions WHERE uuid = ? AND status = ? LIMIT 1",
            uuid.toString(), STATUS_PENDING
        ) { it.getInt("found") } != null

    private fun reserveVoucherId(voucherUuid: String, player: Player, rankId: String): Boolean {
        val rows = plugin.databaseManager.executeUpdate(
            "INSERT OR IGNORE INTO rank_voucher_redemptions (voucher_uuid, uuid, player_name, rank_id, timestamp, status) VALUES (?, ?, ?, ?, ?, ?)",
            voucherUuid, player.uniqueId.toString(), player.name, rankId, System.currentTimeMillis(), STATUS_PENDING
        )
        return rows > 0
    }

    private fun markCompleted(voucherUuid: String): Boolean =
        plugin.databaseManager.executeUpdate(
            "UPDATE rank_voucher_redemptions SET status = ? WHERE voucher_uuid = ? AND status = ?",
            STATUS_COMPLETED, voucherUuid, STATUS_PENDING
        ) == 1

    /**
     * Releases a PENDING reservation (so the id isn't burned when no rank was granted) and
     * hands the player a fresh voucher of the same tier. False if the row was already settled.
     */
    private fun returnVoucher(player: Player, voucherUuid: String, rankId: String): Boolean {
        val released = plugin.databaseManager.executeUpdate(
            "DELETE FROM rank_voucher_redemptions WHERE voucher_uuid = ? AND status = ?",
            voucherUuid, STATUS_PENDING
        ) == 1
        if (released) give(player, rankId, 1)
        return released
    }

    private fun send(player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.ECONOMY)
    }

    companion object {
        const val VOUCHER_VERSION = 1
        const val MAX_QUANTITY = 10_000
        private const val STATUS_PENDING = "PENDING"
        private const val STATUS_COMPLETED = "COMPLETED"
        private const val PENDING_RESOLVE_DELAY_TICKS = 40L
        private const val PENDING_RESOLVE_MAX_ATTEMPTS = 5
    }
}
