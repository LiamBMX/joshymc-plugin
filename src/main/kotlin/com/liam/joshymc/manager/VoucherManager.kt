package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
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
 */
class VoucherManager(private val plugin: Joshymc) {

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
        val owned = voucher.rankId != null && voucher.rankId in plugin.rankManager.getPlayerRankIds(viewer.uniqueId)
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
            if (owned) {
                lore.add(Component.text("Already Owned", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
            } else {
                lore.add(Component.text("Click to redeem", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            }
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

            if (voucher.rankId != null) {
                val owned = plugin.rankManager.getPlayerRankIds(player.uniqueId)
                if (voucher.rankId in owned) {
                    plugin.commsManager.send(player, Component.text("You already own this rank.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                    logAttempt(player, voucher, success = false, reason = "already_owned")
                    return
                }
                val currentWeight = plugin.rankManager.getPlayerRankById(player.uniqueId)?.weight ?: Int.MIN_VALUE
                val targetWeight = plugin.rankManager.getRank(voucher.rankId)?.weight
                if (targetWeight != null && currentWeight >= targetWeight) {
                    plugin.commsManager.send(player, Component.text("You already have an equal or higher rank.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                    logAttempt(player, voucher, success = false, reason = "higher_rank_owned")
                    return
                }
            }

            val balance = plugin.creditsManager.getBalance(player)
            if (balance < voucher.price) {
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
                return
            }

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
            redeeming.remove(player.uniqueId)
        }
    }

    private fun logAttempt(player: Player, voucher: Voucher, success: Boolean, reason: String) {
        plugin.databaseManager.execute(
            "INSERT INTO voucher_redemptions (tx_id, uuid, player_name, voucher_id, category_id, rank_id, price, success, reason, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), player.uniqueId.toString(), player.name, voucher.id, voucher.categoryId,
            voucher.rankId ?: "", voucher.price, if (success) 1 else 0, reason, System.currentTimeMillis()
        )
    }
}
