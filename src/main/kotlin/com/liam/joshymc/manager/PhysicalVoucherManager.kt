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
 * Physical money vouchers (issue #537) — real inventory items that redeem for
 * plain server money only. Unlike the virtual [VoucherManager] packages (which
 * are only ever "clicked" inside the Credit Shop GUI and can grant ranks,
 * perks, or commands), these are actual [ItemStack]s a player can hold, trade
 * away, or drop, and right-clicking one only ever deposits normal economy
 * balance — never credits, ranks, permissions, items, or commands.
 *
 * Identification is PDC-only (never material/name/lore) so a renamed PAPER
 * item can never be redeemed. Definitions are read once from `config.yml >
 * physical-vouchers` on [start] — the reward amount always comes from that
 * server-side config, keyed by [voucherIdKey], not from anything stored on
 * the item itself besides the id.
 */
class PhysicalVoucherManager(private val plugin: Joshymc) {

    data class PhysicalVoucher(
        val id: String,
        val enabled: Boolean,
        val material: Material,
        val displayName: String,
        val amount: Double,
        val lore: List<String>
    )

    val voucherTypeKey = NamespacedKey(plugin, "voucher_type")
    val voucherIdKey = NamespacedKey(plugin, "voucher_id")

    private val vouchers = mutableMapOf<String, PhysicalVoucher>()

    // Per-player redemption lock — closes the main-hand/offhand double-fire and
    // rapid-click-spam duplication windows described in the issue.
    private val redeeming = ConcurrentHashMap.newKeySet<UUID>()

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS physical_voucher_redemptions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                voucher_id TEXT NOT NULL,
                amount REAL NOT NULL,
                success INTEGER NOT NULL,
                reason TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())

        reload()
        plugin.logger.info("[PhysicalVouchers] Loaded ${vouchers.size} physical voucher definition(s).")
    }

    fun reload() {
        vouchers.clear()
        val section = plugin.config.getConfigurationSection("physical-vouchers") ?: return
        for (id in section.getKeys(false)) {
            val s = section.getConfigurationSection(id) ?: continue
            val material = Material.matchMaterial(s.getString("material", "PAPER") ?: "PAPER") ?: Material.PAPER
            vouchers[id] = PhysicalVoucher(
                id = id,
                enabled = s.getBoolean("enabled", true),
                material = material,
                displayName = s.getString("display-name", id) ?: id,
                amount = s.getDouble("amount", 0.0),
                lore = s.getStringList("lore")
            )
        }
    }

    fun getVoucher(id: String): PhysicalVoucher? = vouchers[id]

    fun getAllVouchers(): List<PhysicalVoucher> = vouchers.values.sortedBy { it.id }

    fun getEnabledVoucherIds(): List<String> = vouchers.values.filter { it.enabled }.map { it.id }

    // ── Item building / identification ─────────────────────────────────────

    fun createVoucherStack(id: String, amount: Int = 1): ItemStack? {
        val def = vouchers[id] ?: return null
        val stack = ItemStack(def.material, amount.coerceAtLeast(1))
        stack.editMeta { meta ->
            meta.displayName(plugin.commsManager.parseLegacy(def.displayName).decoration(TextDecoration.ITALIC, false))
            meta.lore(def.lore.map { plugin.commsManager.parseLegacy(it).decoration(TextDecoration.ITALIC, false) })
            meta.persistentDataContainer.set(voucherTypeKey, PersistentDataType.STRING, "money")
            meta.persistentDataContainer.set(voucherIdKey, PersistentDataType.STRING, id)
        }
        return stack
    }

    fun isPhysicalVoucher(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.get(voucherTypeKey, PersistentDataType.STRING) == "money"
    }

    fun getVoucherId(item: ItemStack): String? {
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(voucherIdKey, PersistentDataType.STRING)
    }

    /** Adds vouchers to the player's inventory, safely dropping any overflow at their feet. */
    fun give(player: Player, id: String, amount: Int = 1): Boolean {
        val stack = createVoucherStack(id, amount) ?: return false
        val leftover = player.inventory.addItem(stack)
        for ((_, item) in leftover) {
            player.world.dropItemNaturally(player.location, item)
        }
        return true
    }

    // ── Redemption ───────────────────────────────────────────────────────

    /**
     * Redeems exactly one voucher from the given inventory slot getter/setter pair
     * (the caller passes the actual main-hand or off-hand stack so we mutate the
     * real item, not a detached copy).
     */
    fun redeem(player: Player, heldItem: ItemStack, takeOne: () -> Unit) {
        if (!isPhysicalVoucher(heldItem)) return

        if (!redeeming.add(player.uniqueId)) return
        try {
            val id = getVoucherId(heldItem)
            val def = id?.let { vouchers[it] }
            if (def == null || !def.enabled) {
                plugin.commsManager.send(
                    player,
                    Component.text("This voucher can no longer be redeemed.", NamedTextColor.RED),
                    CommunicationsManager.Category.ECONOMY
                )
                logAttempt(player, id ?: "unknown", 0.0, success = false, reason = if (def == null) "unknown_voucher" else "disabled")
                return
            }

            // Consume exactly one before depositing — if the deposit somehow throws,
            // we give the voucher back rather than leave the player uncharged AND unpaid.
            takeOne()

            try {
                plugin.economyManager.deposit(player.uniqueId, def.amount)
            } catch (ex: Exception) {
                plugin.logger.warning("[PhysicalVouchers] Deposit failed for ${player.name} (${def.id}): ${ex.message}")
                give(player, def.id, 1)
                plugin.commsManager.send(player, Component.text("Redemption failed, your voucher has been returned.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                logAttempt(player, def.id, def.amount, success = false, reason = "deposit_failed")
                return
            }

            plugin.commsManager.send(
                player,
                Component.text("You redeemed a ", NamedTextColor.GREEN)
                    .append(Component.text(plugin.economyManager.format(def.amount), NamedTextColor.WHITE))
                    .append(Component.text(" voucher.", NamedTextColor.GREEN)),
                CommunicationsManager.Category.ECONOMY
            )
            logAttempt(player, def.id, def.amount, success = true, reason = "")
        } finally {
            redeeming.remove(player.uniqueId)
        }
    }

    private fun logAttempt(player: Player, voucherId: String, amount: Double, success: Boolean, reason: String) {
        plugin.databaseManager.execute(
            "INSERT INTO physical_voucher_redemptions (uuid, player_name, voucher_id, amount, success, reason, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)",
            player.uniqueId.toString(), player.name, voucherId, amount, if (success) 1 else 0, reason, System.currentTimeMillis()
        )
    }
}
