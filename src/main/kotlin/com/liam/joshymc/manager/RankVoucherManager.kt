package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Physical Rank Vouchers (issue #595) — real, tradeable Name Tag items that
 * upgrade a player's purchasable rank tier through the existing [RankManager]
 * add/remove-rank + LuckPerms sync logic. Only the seven purchasable tiers
 * below are supported; staff ranks are never voucher-redeemable. Vouchers
 * never downgrade and are never consumed if the player already holds an
 * equal or higher tier.
 */
class RankVoucherManager(private val plugin: Joshymc) {

    // Ordered lowest -> highest. This ordering IS the voucher hierarchy —
    // these seven purchasable tiers live entirely in LuckPerms/permissions
    // (joshymc.rankperk.<id> in config.yml), never in RankManager's own
    // ranks.list, so position in this list is the only place the hierarchy
    // is defined.
    private val tierOrder = listOf("scout", "pathfinder", "lumberjack", "trailblazer", "voyager", "ranger", "pioneer")

    val voucherTypeKey = NamespacedKey(plugin, "voucher_type")
    val voucherRankKey = NamespacedKey(plugin, "voucher_rank")
    val voucherVersionKey = NamespacedKey(plugin, "voucher_version")
    val voucherUuidKey = NamespacedKey(plugin, "voucher_uuid")

    private val redeeming = ConcurrentHashMap.newKeySet<UUID>()

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
    }

    fun getSupportedRankIds(): List<String> = tierOrder

    fun isSupportedRank(rankId: String): Boolean = rankId.lowercase() in tierOrder

    fun displayName(rankId: String): String = rankId.lowercase().replaceFirstChar { it.uppercase() }

    private fun tierIndex(rankId: String): Int = tierOrder.indexOf(rankId.lowercase())

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
            val leftover = player.inventory.addItem(stack)
            for ((_, item) in leftover) {
                player.world.dropItemNaturally(player.location, item)
            }
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
            plugin.commsManager.send(
                player,
                Component.text("This voucher stack is invalid and cannot be redeemed.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            plugin.logger.warning("[RankVouchers] ${player.name} attempted to redeem a malformed voucher stack (amount=${heldItem.amount}).")
            return
        }

        if (!redeeming.add(player.uniqueId)) return
        try {
            val pdc = heldItem.itemMeta?.persistentDataContainer
            val version = pdc?.get(voucherVersionKey, PersistentDataType.INTEGER)
            val rankId = pdc?.get(voucherRankKey, PersistentDataType.STRING)
            val voucherUuid = pdc?.get(voucherUuidKey, PersistentDataType.STRING)

            if (version != VOUCHER_VERSION || rankId == null || !isSupportedRank(rankId) || voucherUuid == null) {
                plugin.commsManager.send(
                    player,
                    Component.text("This voucher is invalid and cannot be redeemed.", NamedTextColor.RED),
                    CommunicationsManager.Category.ECONOMY
                )
                plugin.logger.warning("[RankVouchers] ${player.name} attempted to redeem a malformed voucher (version=$version, rank=$rankId, uuid=$voucherUuid).")
                return
            }

            val currentTier = currentTierId(player.uniqueId)
            val currentIndex = currentTier?.let { tierIndex(it) } ?: -1
            val voucherIndex = tierIndex(rankId)

            if (voucherIndex <= currentIndex) {
                val message = if (voucherIndex == currentIndex) {
                    "You already have the ${displayName(rankId)} rank."
                } else {
                    "You already have a higher rank than ${displayName(rankId)}."
                }
                plugin.commsManager.send(player, Component.text(message, NamedTextColor.YELLOW), CommunicationsManager.Category.ECONOMY)
                return
            }

            // Atomically reserve this voucher's unique id first, same anti-dupe idiom as
            // CreditVoucherManager — stops a duplicated physical item (same PDC uuid) from
            // ever redeeming twice.
            if (!reserveVoucherId(voucherUuid, player, rankId)) {
                plugin.commsManager.send(
                    player,
                    Component.text("This voucher has already been redeemed.", NamedTextColor.RED),
                    CommunicationsManager.Category.ECONOMY
                )
                return
            }

            takeOne()

            try {
                // Reuse RankManager's own add/remove-rank logic (issue's "LuckPerms safety"
                // requirement) — this preserves every unrelated LuckPerms group/permission
                // and only ever touches the one purchasable tier being replaced.
                if (currentTier != null) plugin.rankManager.removeRank(player.uniqueId, currentTier)
                plugin.rankManager.addRank(player.uniqueId, rankId)
            } catch (ex: Exception) {
                plugin.logger.warning("[RankVouchers] Rank upgrade failed for ${player.name} ($rankId): ${ex.message}")
                give(player, rankId, 1)
                releaseVoucherId(voucherUuid)
                plugin.commsManager.send(player, Component.text("Redemption failed, your voucher has been returned.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                return
            }

            plugin.commsManager.send(
                player,
                Component.text("You redeemed the ${displayName(rankId)} rank!", NamedTextColor.GREEN),
                CommunicationsManager.Category.ECONOMY
            )
        } finally {
            redeeming.remove(player.uniqueId)
        }
    }

    /** The player's current highest voucher-tier rank, or null if they hold none of the seven. */
    private fun currentTierId(uuid: UUID): String? {
        return plugin.rankManager.getPlayerRankIds(uuid)
            .filter { isSupportedRank(it) }
            .maxByOrNull { tierIndex(it) }
    }

    private fun reserveVoucherId(voucherUuid: String, player: Player, rankId: String): Boolean {
        val rows = plugin.databaseManager.executeUpdate(
            "INSERT OR IGNORE INTO rank_voucher_redemptions (voucher_uuid, uuid, player_name, rank_id, timestamp) VALUES (?, ?, ?, ?, ?)",
            voucherUuid, player.uniqueId.toString(), player.name, rankId, System.currentTimeMillis()
        )
        return rows > 0
    }

    private fun releaseVoucherId(voucherUuid: String) {
        plugin.databaseManager.execute("DELETE FROM rank_voucher_redemptions WHERE voucher_uuid = ?", voucherUuid)
    }

    companion object {
        const val VOUCHER_VERSION = 1
        const val MAX_QUANTITY = 10_000
    }
}
