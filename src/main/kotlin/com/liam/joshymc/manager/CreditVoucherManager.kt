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
 * Physical Credit Vouchers (issue #595) — real, tradeable Emerald items that
 * deposit into the existing [CreditsManager] balance on right-click. Distinct
 * from the virtual Credit Shop voucher packages in [VoucherManager] (GUI-only,
 * never physical items) and from the physical money vouchers in
 * [PhysicalVoucherManager] (deposit economy money, not Credits).
 *
 * Every voucher gets its own random [voucherUuidKey], so no two vouchers ever
 * carry identical PersistentDataContainer contents — Bukkit's stack-merge
 * check compares full ItemMeta, so distinct PDC means same-amount vouchers
 * never merge into a stack of more than 1.
 */
class CreditVoucherManager(private val plugin: Joshymc) {

    val voucherTypeKey = NamespacedKey(plugin, "voucher_type")
    val voucherAmountKey = NamespacedKey(plugin, "voucher_amount")
    val voucherVersionKey = NamespacedKey(plugin, "voucher_version")
    val voucherUuidKey = NamespacedKey(plugin, "voucher_uuid")

    // Per-player redemption lock — closes the main-hand/offhand double-fire and
    // rapid-click-spam duplication windows.
    private val redeeming = ConcurrentHashMap.newKeySet<UUID>()

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS credit_voucher_redemptions (
                voucher_uuid TEXT PRIMARY KEY,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                amount REAL NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())
    }

    // ── Item building / identification ─────────────────────────────────────

    fun createVoucherStack(amount: Int): ItemStack {
        val stack = ItemStack(Material.EMERALD, 1)
        stack.editMeta { meta ->
            meta.displayName(
                Component.text("$amount Credit Voucher", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(listOf(
                Component.text("Right-click to redeem", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Reward: $amount Credits", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
            ))
            meta.persistentDataContainer.set(voucherTypeKey, PersistentDataType.STRING, "credit")
            meta.persistentDataContainer.set(voucherAmountKey, PersistentDataType.INTEGER, amount)
            meta.persistentDataContainer.set(voucherVersionKey, PersistentDataType.INTEGER, VOUCHER_VERSION)
            meta.persistentDataContainer.set(voucherUuidKey, PersistentDataType.STRING, UUID.randomUUID().toString())
        }
        return stack
    }

    fun isCreditVoucher(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.get(voucherTypeKey, PersistentDataType.STRING) == "credit"
    }

    /** Gives [quantity] separate 1-count voucher items (never a stack) worth [amount] Credits each. */
    fun give(player: Player, amount: Int, quantity: Int) {
        repeat(quantity) {
            val leftover = player.inventory.addItem(createVoucherStack(amount))
            for ((_, item) in leftover) {
                player.world.dropItemNaturally(player.location, item)
            }
        }
    }

    // ── Redemption ───────────────────────────────────────────────────────

    /**
     * Redeems exactly one voucher from the given held stack (mutated in place via
     * [takeOne] so the caller's real main-hand item shrinks, not a detached copy).
     */
    fun redeem(player: Player, heldItem: ItemStack, takeOne: () -> Unit) {
        if (!isCreditVoucher(heldItem)) return

        // A legitimate voucher is always amount == 1 (non-stacking is enforced by the
        // unique PDC uuid). Amount > 1 can only happen via an external NBT-editing tool
        // that force-stacked identical PDC onto multiple items — refuse rather than pay
        // out the whole stack.
        if (heldItem.amount != 1) {
            plugin.commsManager.send(
                player,
                Component.text("This voucher stack is invalid and cannot be redeemed.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            plugin.logger.warning("[CreditVouchers] ${player.name} attempted to redeem a malformed voucher stack (amount=${heldItem.amount}).")
            return
        }

        if (!redeeming.add(player.uniqueId)) return
        try {
            val pdc = heldItem.itemMeta?.persistentDataContainer
            val version = pdc?.get(voucherVersionKey, PersistentDataType.INTEGER)
            val amount = pdc?.get(voucherAmountKey, PersistentDataType.INTEGER)
            val voucherUuid = pdc?.get(voucherUuidKey, PersistentDataType.STRING)

            if (version != VOUCHER_VERSION || amount == null || amount <= 0 || voucherUuid == null) {
                plugin.commsManager.send(
                    player,
                    Component.text("This voucher is invalid and cannot be redeemed.", NamedTextColor.RED),
                    CommunicationsManager.Category.ECONOMY
                )
                plugin.logger.warning("[CreditVouchers] ${player.name} attempted to redeem a malformed voucher (version=$version, amount=$amount, uuid=$voucherUuid).")
                return
            }

            // Atomically reserve this voucher's unique id first — an INSERT OR IGNORE
            // that only succeeds once per uuid. This is what actually stops a duplicated
            // physical item (same PDC uuid copied via an external exploit) from ever
            // redeeming twice, on top of the per-player lock above.
            if (!reserveVoucherId(voucherUuid, player, amount.toDouble())) {
                plugin.commsManager.send(
                    player,
                    Component.text("This voucher has already been redeemed.", NamedTextColor.RED),
                    CommunicationsManager.Category.ECONOMY
                )
                return
            }

            // Consume exactly one before depositing — if the deposit somehow throws,
            // we give the voucher back rather than leave the player uncharged AND unpaid.
            takeOne()

            try {
                plugin.creditsManager.deposit(player.uniqueId, amount.toDouble())
            } catch (ex: Exception) {
                plugin.logger.warning("[CreditVouchers] Deposit failed for ${player.name} ($amount): ${ex.message}")
                give(player, amount, 1)
                releaseVoucherId(voucherUuid)
                plugin.commsManager.send(player, Component.text("Redemption failed, your voucher has been returned.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                return
            }

            plugin.commsManager.send(
                player,
                Component.text("You redeemed ${plugin.creditsManager.format(amount.toDouble())} Credits!", NamedTextColor.GREEN),
                CommunicationsManager.Category.ECONOMY
            )
        } finally {
            redeeming.remove(player.uniqueId)
        }
    }

    private fun reserveVoucherId(voucherUuid: String, player: Player, amount: Double): Boolean {
        val rows = plugin.databaseManager.executeUpdate(
            "INSERT OR IGNORE INTO credit_voucher_redemptions (voucher_uuid, uuid, player_name, amount, timestamp) VALUES (?, ?, ?, ?, ?)",
            voucherUuid, player.uniqueId.toString(), player.name, amount, System.currentTimeMillis()
        )
        return rows > 0
    }

    private fun releaseVoucherId(voucherUuid: String) {
        plugin.databaseManager.execute("DELETE FROM credit_voucher_redemptions WHERE voucher_uuid = ?", voucherUuid)
    }

    companion object {
        const val VOUCHER_VERSION = 1
        const val MAX_AMOUNT = 1_000_000_000
        const val MAX_QUANTITY = 10_000
    }
}
