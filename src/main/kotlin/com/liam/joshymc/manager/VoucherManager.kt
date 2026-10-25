package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Virtual voucher/redemption packages for the Credit Shop (issue #510).
 * A voucher is NEVER a physical item — redeeming one deducts Credits and
 * dispatches a configured console command (e.g. "rank add %player% vip").
 * Vouchers are listed inside the existing [CreditShopManager] category
 * system (see [CreditShopManager.getOrCreateCategory]) so the Credit Shop
 * stays the single front end for both physical items and virtual packages.
 *
 * Donor-tier packages (issue #1173) don't dispatch their reward command: a package linked to
 * a [DonorTiers] tier (or whose command is just a recognizable donor-tier grant) charges into a
 * PENDING voucher_rank_purchases row and grants the tier through [RankManager.grantDonorTier].
 * Only LuckPerms confirming the group completes the purchase; anything else refunds it, and a
 * purchase left pending by a quit or a stop is settled on the player's next join.
 */
class VoucherManager(private val plugin: Joshymc) : Listener {

    data class Voucher(
        val id: String,
        val name: String,
        val categoryId: String,
        val price: Double,
        val rankId: String?,
        val rewardCommand: String,
        val enabled: Boolean,
        val description: String,
        val icon: Material
    )

    // Per-player redemption lock — makes double-clicks/packet spam/rapid GUI
    // clicks unable to ever process two redemptions for the same player at once.
    private val redeeming = ConcurrentHashMap.newKeySet<UUID>()

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS vouchers (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                category_id TEXT NOT NULL,
                price REAL NOT NULL,
                rank_id TEXT NOT NULL DEFAULT '',
                reward_command TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                description TEXT NOT NULL DEFAULT '',
                icon TEXT NOT NULL DEFAULT 'PAPER'
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS voucher_redemptions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tx_id TEXT NOT NULL,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                voucher_id TEXT NOT NULL,
                category_id TEXT NOT NULL,
                rank_id TEXT NOT NULL DEFAULT '',
                price REAL NOT NULL,
                success INTEGER NOT NULL,
                reason TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())

        // One row per charged donor-tier purchase: PENDING until LuckPerms confirms, then
        // COMPLETED, or REFUNDED when the Credits went back (issue #1173).
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS voucher_rank_purchases (
                tx_id TEXT PRIMARY KEY,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                voucher_id TEXT NOT NULL,
                category_id TEXT NOT NULL,
                rank_id TEXT NOT NULL,
                price REAL NOT NULL,
                status TEXT NOT NULL,
                reason TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        plugin.databaseManager.createTable(
            "CREATE INDEX IF NOT EXISTS idx_voucher_rank_purchases_pending ON voucher_rank_purchases (uuid, status)"
        )

        auditRankPackages()

        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            Bukkit.getOnlinePlayers().forEach { resolvePendingRankPurchases(it, 0) }
        }, PENDING_RESOLVE_DELAY_TICKS)
    }

    /** Logs, once at startup, packages that can't be sold and legacy ones now treated as donor tiers. */
    private fun auditRankPackages() {
        for (voucher in getAllVouchers()) {
            val linked = voucher.rankId
            if (linked != null && !DonorTiers.isTier(linked)) {
                plugin.logger.warning("[Vouchers] Package '${voucher.id}' is linked to '$linked', which is not a donor tier (${DonorTiers.ORDER.joinToString(", ")}); it will refuse purchases until fixed with /voucher setrank.")
            } else if (linked == null) {
                DonorTiers.tierFromRewardCommand(voucher.rewardCommand)?.let {
                    plugin.logger.info("[Vouchers] Package '${voucher.id}' grants donor tier $it by command; it is granted and confirmed directly instead of dispatching that command.")
                }
            }
        }
    }

    /**
     * The donor tier [voucher] sells, or null for an ordinary command package: its linked
     * rank, or a legacy unlinked package whose command is just a donor-tier grant. Also null
     * for a package linked to a non-donor rank, which [redeem] refuses.
     */
    fun rankPackageTier(voucher: Voucher): String? {
        voucher.rankId?.let { return DonorTiers.normalize(it) }
        return DonorTiers.tierFromRewardCommand(voucher.rewardCommand)
    }

    // ── Admin management ─────────────────────────────────────────────────

    fun createVoucher(id: String, price: Double, categoryName: String, rewardCommand: String): Voucher? {
        if (getVoucher(id) != null) return null
        val category = plugin.creditShopManager.getOrCreateCategory(categoryName, Material.PAPER)
        plugin.databaseManager.execute(
            "INSERT INTO vouchers (id, name, category_id, price, rank_id, reward_command, enabled, description, icon) VALUES (?, ?, ?, ?, '', ?, 1, '', 'PAPER')",
            id, id, category.id, price, rewardCommand
        )
        return getVoucher(id)
    }

    fun deleteVoucher(id: String): Boolean {
        return plugin.databaseManager.executeUpdate("DELETE FROM vouchers WHERE id = ?", id) > 0
    }

    /** Called when a Credit Shop category is removed so its vouchers aren't left orphaned. */
    fun deleteVouchersByCategory(categoryId: String) {
        plugin.databaseManager.execute("DELETE FROM vouchers WHERE category_id = ?", categoryId)
    }

    fun countVouchersByCategory(categoryId: String): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) AS count FROM vouchers WHERE category_id = ?", categoryId
        ) { it.getInt("count") } ?: 0
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        return plugin.databaseManager.executeUpdate("UPDATE vouchers SET enabled = ? WHERE id = ?", if (enabled) 1 else 0, id) > 0
    }

    fun setPrice(id: String, price: Double): Boolean {
        return plugin.databaseManager.executeUpdate("UPDATE vouchers SET price = ? WHERE id = ?", price, id) > 0
    }

    fun setName(id: String, name: String): Boolean {
        return plugin.databaseManager.executeUpdate("UPDATE vouchers SET name = ? WHERE id = ?", name, id) > 0
    }

    fun setDescription(id: String, description: String): Boolean {
        return plugin.databaseManager.executeUpdate("UPDATE vouchers SET description = ? WHERE id = ?", description, id) > 0
    }

    fun setCategory(id: String, categoryName: String): Boolean {
        if (getVoucher(id) == null) return false
        val category = plugin.creditShopManager.getOrCreateCategory(categoryName, Material.PAPER)
        return plugin.databaseManager.executeUpdate("UPDATE vouchers SET category_id = ? WHERE id = ?", category.id, id) > 0
    }

    fun setRewardCommand(id: String, command: String): Boolean {
        return plugin.databaseManager.executeUpdate("UPDATE vouchers SET reward_command = ? WHERE id = ?", command, id) > 0
    }

    /** Pass null to unlink the voucher from a rank (no ownership/downgrade checks). */
    fun setRank(id: String, rankId: String?): Boolean {
        return plugin.databaseManager.executeUpdate("UPDATE vouchers SET rank_id = ? WHERE id = ?", rankId ?: "", id) > 0
    }

    fun setIcon(id: String, icon: Material): Boolean {
        return plugin.databaseManager.executeUpdate("UPDATE vouchers SET icon = ? WHERE id = ?", icon.name, id) > 0
    }

    fun getVoucher(id: String): Voucher? {
        return plugin.databaseManager.queryFirst("SELECT * FROM vouchers WHERE id = ?", id) { mapVoucher(it) }
    }

    fun getAllVouchers(): List<Voucher> {
        return plugin.databaseManager.query("SELECT * FROM vouchers ORDER BY id") { mapVoucher(it) }
    }

    fun getVouchersByCategory(categoryId: String): List<Voucher> {
        return plugin.databaseManager.query(
            "SELECT * FROM vouchers WHERE category_id = ? AND enabled = 1 ORDER BY price", categoryId
        ) { mapVoucher(it) }
    }

    private fun mapVoucher(rs: java.sql.ResultSet): Voucher {
        return Voucher(
            id = rs.getString("id"),
            name = rs.getString("name"),
            categoryId = rs.getString("category_id"),
            price = rs.getDouble("price"),
            rankId = rs.getString("rank_id").let { if (it.isNullOrEmpty()) null else it },
            rewardCommand = rs.getString("reward_command"),
            enabled = rs.getInt("enabled") != 0,
            description = rs.getString("description") ?: "",
            icon = Material.matchMaterial(rs.getString("icon") ?: "PAPER") ?: Material.PAPER
        )
    }

    // ── GUI icon ─────────────────────────────────────────────────────────

    fun buildIcon(voucher: Voucher, viewer: Player): ItemStack {
        val tier = rankPackageTier(voucher)
        val comparison = tier?.let { DonorTiers.compare(plugin.rankManager.currentDonorTier(viewer.uniqueId), it) }
        val icon = ItemStack(voucher.icon)
        icon.editMeta { meta ->
            meta.displayName(
                Component.text(voucher.name, NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false)
            )
            val lore = mutableListOf<Component>()
            if (voucher.description.isNotBlank()) {
                lore.add(plugin.commsManager.parseLegacy("&7${voucher.description}").decoration(TextDecoration.ITALIC, false))
                lore.add(Component.empty())
            }
            lore.add(
                plugin.commsManager.parseLegacy("&7Price: &b${plugin.creditsManager.format(voucher.price)} credits")
                    .decoration(TextDecoration.ITALIC, false)
            )
            lore.add(Component.empty())
            val status = when (comparison) {
                DonorTiers.Comparison.SAME_TIER -> Component.text("Already Owned", NamedTextColor.GREEN)
                DonorTiers.Comparison.LOWER_TIER -> Component.text("You own a higher rank", NamedTextColor.GREEN)
                else -> Component.text("Click to redeem", NamedTextColor.YELLOW)
            }
            lore.add(status.decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)
        }
        return icon
    }

    // ── Redemption ───────────────────────────────────────────────────────

    fun redeem(player: Player, voucherId: String) {
        if (!redeeming.add(player.uniqueId)) {
            plugin.commsManager.send(
                player,
                Component.text("Your last redemption is still processing.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        var handedOff = false
        try {
            // Re-fetch fresh from the DB so we never act on a stale price/enabled
            // state captured when the GUI was opened.
            val voucher = getVoucher(voucherId)
            if (voucher == null || !voucher.enabled) {
                plugin.commsManager.send(
                    player,
                    Component.text("That package is no longer available.", NamedTextColor.RED),
                    CommunicationsManager.Category.ECONOMY
                )
                return
            }

            // Rank packages may only sell the donor tiers; anything else linked here (a staff
            // rank, a typo, an arbitrary group) is refused before any charge (issue #1173).
            if (voucher.rankId != null && !DonorTiers.isTier(voucher.rankId)) {
                send(player, "This package is not available right now. Please tell staff.", NamedTextColor.RED)
                plugin.logger.warning("[Vouchers] Refused package '${voucher.id}' for ${player.name}: linked rank '${voucher.rankId}' is not a donor tier.")
                logAttempt(player, voucher, success = false, reason = "unsupported_rank")
                return
            }
            val tier = rankPackageTier(voucher)
            if (tier != null) {
                handedOff = redeemRankPackage(player, voucher, tier)
                return
            }

            if (!checkFunds(player, voucher)) return

            if (!plugin.creditsManager.withdraw(player.uniqueId, voucher.price)) {
                logAttempt(player, voucher, success = false, reason = "withdraw_failed")
                return
            }

            val command = voucher.rewardCommand.replace("%player%", player.name)
            val executed = try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
            } catch (ex: Exception) {
                plugin.logger.warning("[Vouchers] Reward command threw for ${player.name} (${voucher.id}): ${ex.message}")
                false
            }

            if (!executed) {
                // Refund — the player must never be charged without the reward landing.
                plugin.creditsManager.deposit(player.uniqueId, voucher.price)
                plugin.commsManager.send(player, Component.text("Redemption failed, you have not been charged.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                logAttempt(player, voucher, success = false, reason = "reward_command_failed")
                return
            }

            plugin.commsManager.send(
                player,
                Component.text("Redeemed ", NamedTextColor.GREEN)
                    .append(Component.text(voucher.name, NamedTextColor.WHITE))
                    .append(Component.text(" for ", NamedTextColor.GREEN))
                    .append(Component.text("${plugin.creditsManager.format(voucher.price)} credits", NamedTextColor.AQUA))
                    .append(Component.text(".", NamedTextColor.GREEN)),
                CommunicationsManager.Category.ECONOMY
            )
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f)
            logAttempt(player, voucher, success = true, reason = "")
        } finally {
            if (!handedOff) redeeming.remove(player.uniqueId)
        }
    }

    private fun checkFunds(player: Player, voucher: Voucher): Boolean {
        val balance = plugin.creditsManager.getBalance(player)
        if (balance >= voucher.price) return true
        plugin.commsManager.send(
            player,
            Component.text("You need ", NamedTextColor.RED)
                .append(Component.text("${plugin.creditsManager.format(voucher.price)} credits", NamedTextColor.AQUA))
                .append(Component.text(" but only have ", NamedTextColor.RED))
                .append(Component.text("${plugin.creditsManager.format(balance)} credits", NamedTextColor.AQUA))
                .append(Component.text(".", NamedTextColor.RED)),
            CommunicationsManager.Category.ECONOMY
        )
        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
        logAttempt(player, voucher, success = false, reason = "insufficient_credits")
        return false
    }

    // ── Donor-tier packages (issue #1173) ────────────────────────────────

    /**
     * Sells donor [tier]: checks ownership through [RankManager.currentDonorTier], charges the
     * Credits and writes a PENDING purchase in one transaction, then grants the tier with
     * [RankManager.grantDonorTier]. The purchase only completes once LuckPerms shows the group;
     * any failure refunds it exactly once. Returns true when the grant took over releasing
     * [redeeming] (it runs past this call), false when the caller should release it.
     */
    private fun redeemRankPackage(player: Player, voucher: Voucher, tier: String): Boolean {
        val uuid = player.uniqueId
        if (!plugin.rankManager.tryLockDonorChange(uuid)) {
            send(player, "Your last rank change is still processing.", NamedTextColor.RED)
            return false
        }
        var handedOff = false
        try {
            if (hasPendingRankPurchase(uuid) || plugin.rankVoucherManager.hasPendingRedemption(uuid) ||
                plugin.freeRankManager.hasPending(uuid)) {
                send(player, "A previous rank purchase is still being confirmed. Try again in a moment.", NamedTextColor.RED)
                logAttempt(player, voucher, success = false, reason = "pending_rank_change")
                return false
            }

            val current = plugin.rankManager.currentDonorTier(uuid)
            when (DonorTiers.compare(current, tier)) {
                DonorTiers.Comparison.SAME_TIER -> {
                    send(player, "You already own the ${DonorTiers.displayName(tier)} rank. You have not been charged.", NamedTextColor.RED)
                    logAttempt(player, voucher, success = false, reason = "already_owned")
                    plugin.logger.info("[Vouchers] Rejected ${voucher.id} ($tier) for ${player.name} ($uuid): already holds that tier.")
                    return false
                }
                DonorTiers.Comparison.LOWER_TIER -> {
                    send(player, "You already own ${DonorTiers.displayName(current!!)}, which is higher than ${DonorTiers.displayName(tier)}. You have not been charged.", NamedTextColor.RED)
                    logAttempt(player, voucher, success = false, reason = "higher_rank_owned")
                    plugin.logger.info("[Vouchers] Rejected ${voucher.id} ($tier) for ${player.name} ($uuid): holds higher tier $current.")
                    return false
                }
                DonorTiers.Comparison.UPGRADE -> {}
            }

            if (!plugin.rankManager.isLuckPermsAvailable()) {
                send(player, "Ranks can't be purchased right now. You have not been charged.", NamedTextColor.RED)
                logAttempt(player, voucher, success = false, reason = "luckperms_unavailable")
                plugin.logger.warning("[Vouchers] ${player.name} tried to buy ${voucher.id} ($tier) but LuckPerms is not enabled.")
                return false
            }

            if (!checkFunds(player, voucher)) return false

            val txId = UUID.randomUUID().toString()
            var charged = false
            try {
                plugin.databaseManager.transaction {
                    if (!plugin.creditsManager.withdraw(uuid, voucher.price)) return@transaction
                    val now = System.currentTimeMillis()
                    plugin.databaseManager.execute(
                        "INSERT INTO voucher_rank_purchases (tx_id, uuid, player_name, voucher_id, category_id, rank_id, price, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        txId, uuid.toString(), player.name, voucher.id, voucher.categoryId, tier, voucher.price, STATUS_PENDING, now, now
                    )
                    charged = true
                }
            } catch (e: Exception) {
                plugin.logger.warning("[Vouchers] Charging ${player.name} for ${voucher.id} failed: ${e.message}")
                charged = false
            }
            if (!charged) {
                send(player, "Purchase failed, you have not been charged.", NamedTextColor.RED)
                logAttempt(player, voucher, success = false, reason = "withdraw_failed")
                return false
            }
            plugin.logger.info("[Vouchers] Charged ${player.name} ($uuid) ${plugin.creditsManager.format(voucher.price)} credits for ${voucher.id} ($tier), tx $txId; waiting for LuckPerms.")

            send(player, "Applying the ${DonorTiers.displayName(tier)} rank...", NamedTextColor.GRAY)
            handedOff = true
            plugin.rankManager.grantDonorTier(player, tier) { result ->
                try {
                    finishRankPurchase(player, txId, voucher, tier, result)
                } finally {
                    plugin.rankManager.unlockDonorChange(uuid)
                    redeeming.remove(uuid)
                }
            }
            return true
        } finally {
            if (!handedOff) plugin.rankManager.unlockDonorChange(uuid)
        }
    }

    private fun finishRankPurchase(player: Player, txId: String, voucher: Voucher, tier: String, result: RankManager.DonorGrantResult) {
        when (result) {
            RankManager.DonorGrantResult.CONFIRMED -> {
                if (!settle(txId, STATUS_COMPLETED, "")) return
                logRedemption(player.uniqueId, player.name, voucher.id, voucher.categoryId, tier, voucher.price, success = true, reason = "")
                plugin.commsManager.send(
                    player,
                    Component.text("Purchased the ", NamedTextColor.GREEN)
                        .append(Component.text(DonorTiers.displayName(tier), NamedTextColor.WHITE))
                        .append(Component.text(" rank for ", NamedTextColor.GREEN))
                        .append(Component.text("${plugin.creditsManager.format(voucher.price)} credits", NamedTextColor.AQUA))
                        .append(Component.text(".", NamedTextColor.GREEN)),
                    CommunicationsManager.Category.ECONOMY
                )
                player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f)
                plugin.logger.info("[Vouchers] ${player.name} (${player.uniqueId}) bought $tier via ${voucher.id}, tx $txId confirmed.")
            }
            // Stays PENDING; resolvePendingRankPurchases settles it on their next join.
            RankManager.DonorGrantResult.PLAYER_LEFT -> {}
            else -> {
                val reason = "grant_${result.name.lowercase()}"
                if (refund(txId, player.uniqueId, voucher.price, reason)) {
                    logRedemption(player.uniqueId, player.name, voucher.id, voucher.categoryId, tier, voucher.price, success = false, reason = reason)
                    send(player, "Your ${DonorTiers.displayName(tier)} rank couldn't be confirmed, so your Credits have been refunded.", NamedTextColor.RED)
                }
                plugin.logger.warning("[Vouchers] Rank delivery failed for ${player.name} (${player.uniqueId}), ${voucher.id} ($tier), tx $txId: $result.")
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { resolvePendingRankPurchases(player, 0) }, PENDING_RESOLVE_DELAY_TICKS)
    }

    /**
     * Settles purchases left PENDING by a quit or a server stop. LuckPerms has loaded the
     * player's groups by now: if the tier is there the purchase completes, otherwise the
     * request is undone and the Credits are refunded. Each row settles exactly once.
     */
    private fun resolvePendingRankPurchases(player: Player, attempt: Int) {
        if (!player.isOnline) return
        data class Pending(val txId: String, val voucherId: String, val categoryId: String, val rankId: String, val price: Double)
        val rows = plugin.databaseManager.query(
            "SELECT tx_id, voucher_id, category_id, rank_id, price FROM voucher_rank_purchases WHERE uuid = ? AND status = ?",
            player.uniqueId.toString(), STATUS_PENDING
        ) { rs -> Pending(rs.getString("tx_id"), rs.getString("voucher_id"), rs.getString("category_id"), rs.getString("rank_id"), rs.getDouble("price")) }
        if (rows.isEmpty()) return
        if (!plugin.rankManager.isLuckPermsAvailable()) {
            plugin.logger.warning("[Vouchers] ${player.name} has ${rows.size} pending rank purchase(s) but LuckPerms is not enabled; leaving them pending.")
            return
        }
        if (!plugin.rankManager.tryLockDonorChange(player.uniqueId)) {
            if (attempt < PENDING_RESOLVE_MAX_ATTEMPTS) {
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { resolvePendingRankPurchases(player, attempt + 1) }, PENDING_RESOLVE_DELAY_TICKS)
            }
            return
        }
        try {
            for (row in rows) {
                val tier = DonorTiers.normalize(row.rankId)
                if (tier != null && plugin.rankManager.isDonorGroupConfirmed(player, tier)) {
                    if (settle(row.txId, STATUS_COMPLETED, "confirmed_on_join")) {
                        plugin.rankManager.completeDonorGrant(player.uniqueId, tier)
                        logRedemption(player.uniqueId, player.name, row.voucherId, row.categoryId, tier, row.price, success = true, reason = "confirmed_on_join")
                        send(player, "Your ${DonorTiers.displayName(tier)} rank purchase is complete.", NamedTextColor.GREEN)
                        plugin.logger.info("[Vouchers] Settled pending tx ${row.txId} for ${player.name}: $tier confirmed in LuckPerms.")
                    }
                } else {
                    tier?.let { plugin.rankManager.revokeUnconfirmedDonorGrant(player.uniqueId, it) }
                    if (refund(row.txId, player.uniqueId, row.price, "unconfirmed_on_join")) {
                        logRedemption(player.uniqueId, player.name, row.voucherId, row.categoryId, row.rankId, row.price, success = false, reason = "unconfirmed_on_join")
                        send(player, "Your ${DonorTiers.displayName(row.rankId)} rank purchase didn't go through, so your Credits have been refunded.", NamedTextColor.RED)
                        plugin.logger.warning("[Vouchers] Settled pending tx ${row.txId} for ${player.name}: ${row.rankId} not in LuckPerms, refunded.")
                    }
                }
            }
        } finally {
            plugin.rankManager.unlockDonorChange(player.uniqueId)
        }
    }

    fun hasPendingRankPurchase(uuid: UUID): Boolean =
        plugin.databaseManager.queryFirst(
            "SELECT 1 AS found FROM voucher_rank_purchases WHERE uuid = ? AND status = ? LIMIT 1",
            uuid.toString(), STATUS_PENDING
        ) { it.getInt("found") } != null

    /** Moves a PENDING purchase to [status]. False if it was already settled, so nothing runs twice. */
    private fun settle(txId: String, status: String, reason: String): Boolean =
        plugin.databaseManager.executeUpdate(
            "UPDATE voucher_rank_purchases SET status = ?, reason = ?, updated_at = ? WHERE tx_id = ? AND status = ?",
            status, reason, System.currentTimeMillis(), txId, STATUS_PENDING
        ) == 1

    /**
     * Marks the purchase REFUNDED and returns the Credits in one transaction, so a purchase is
     * refunded at most once and never marked refunded without the deposit.
     */
    private fun refund(txId: String, uuid: UUID, price: Double, reason: String): Boolean {
        var refunded = false
        try {
            plugin.databaseManager.transaction {
                if (!settle(txId, STATUS_REFUNDED, reason)) return@transaction
                if (!plugin.creditsManager.deposit(uuid, price)) throw IllegalStateException("deposit of $price rejected")
                refunded = true
            }
        } catch (e: Exception) {
            plugin.logger.severe("[Vouchers] Refund of tx $txId ($price credits to $uuid) failed and was rolled back; it stays PENDING: ${e.message}")
            return false
        }
        if (refunded) plugin.logger.info("[Vouchers] Refunded ${plugin.creditsManager.format(price)} credits to $uuid for tx $txId ($reason).")
        return refunded
    }

    private fun send(player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.ECONOMY)
    }

    private fun logAttempt(player: Player, voucher: Voucher, success: Boolean, reason: String) {
        logRedemption(player.uniqueId, player.name, voucher.id, voucher.categoryId, voucher.rankId ?: "", voucher.price, success, reason)
    }

    private fun logRedemption(uuid: UUID, name: String, voucherId: String, categoryId: String, rankId: String, price: Double, success: Boolean, reason: String) {
        plugin.databaseManager.execute(
            "INSERT INTO voucher_redemptions (tx_id, uuid, player_name, voucher_id, category_id, rank_id, price, success, reason, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), uuid.toString(), name, voucherId, categoryId,
            rankId, price, if (success) 1 else 0, reason, System.currentTimeMillis()
        )
    }

    companion object {
        private const val STATUS_PENDING = "PENDING"
        private const val STATUS_COMPLETED = "COMPLETED"
        private const val STATUS_REFUNDED = "REFUNDED"
        private const val PENDING_RESOLVE_DELAY_TICKS = 40L
        private const val PENDING_RESOLVE_MAX_ATTEMPTS = 5
    }
}
