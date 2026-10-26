package com.liam.joshymc.flight

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.AntiDupeManager
import com.liam.joshymc.util.giveItemSafely
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Physical Fly Vouchers (issue #1304, Survival only): Feather items that add temporary flight
 * time to [FlightTimeManager] on right-click. Built like the Credit Vouchers
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

    // Per-player redemption lock: closes the main-hand/off-hand double-fire and click spam.
    private val redeeming = ConcurrentHashMap.newKeySet<UUID>()

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

    // ── Redemption ───────────────────────────────────────────────────────

    /**
     * Redeems exactly one voucher held in the main hand. The redemption row and the new flight
     * end time are written in one transaction, and the voucher is only taken once that commit
     * succeeded; any failure leaves the voucher in the player's hand and their time unchanged.
     */
    fun redeem(player: Player, heldItem: ItemStack, takeOne: () -> Unit) {
        if (!isFlyVoucher(heldItem)) return

        if (heldItem.amount != 1) {
            // Legit vouchers never stack (unique uuid); a stack can only come from NBT editing.
            plugin.commsManager.send(player, Component.text("This voucher stack is invalid and cannot be redeemed.", NamedTextColor.RED))
            plugin.logger.warning("[FlyVouchers] ${player.name} attempted to redeem a malformed voucher stack (amount=${heldItem.amount}).")
            return
        }

        if (!redeeming.add(player.uniqueId)) return
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

            // Permanent flight already covers everything; temporary time runs on real time,
            // so storing it would only burn it. Keep the voucher (it can be traded instead).
            if (plugin.flightTimeManager.luckPermsAccess(player) == FlightTime.Access.Permanent) {
                plugin.commsManager.send(player, Component.text("You already have permanent flight, so this voucher wasn't used.", NamedTextColor.YELLOW))
                return
            }

            val now = System.currentTimeMillis()
            val current = plugin.flightTimeManager.balance(player.uniqueId)
            val newEnd = SurvivalFlight.stackedEnd(
                current?.endsAt, current?.ended ?: true,
                plugin.flightTimeManager.luckPermsTemporaryEnd(player, now),
                voucher.seconds, now
            )
            if (newEnd == null) {
                plugin.commsManager.send(
                    player,
                    Component.text("You can't hold that much flight time (max ${SurvivalFlight.long(SurvivalFlight.MAX_BALANCE_MILLIS / 1000)}).", NamedTextColor.RED)
                )
                return
            }

            var reserved = false
            try {
                plugin.databaseManager.transaction {
                    reserved = plugin.databaseManager.executeUpdate(
                        "INSERT OR IGNORE INTO fly_voucher_redemptions (voucher_uuid, uuid, player_name, seconds, ends_before, ends_after, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        voucher.voucherUuid, player.uniqueId.toString(), player.name, voucher.seconds,
                        current?.endsAt, newEnd, now
                    ) > 0
                    if (reserved) plugin.flightTimeManager.writeEnd(player.uniqueId, newEnd)
                }
            } catch (e: Exception) {
                plugin.logger.warning("[FlyVouchers] Redemption failed for ${player.name}: ${e.message}")
                plugin.commsManager.send(player, Component.text("Redemption failed. Your voucher was not used.", NamedTextColor.RED))
                return
            }

            if (!reserved) {
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
                return
            }

            plugin.flightTimeManager.applyWritten(player.uniqueId, newEnd)
            takeOne()

            val total = SurvivalFlight.short(SurvivalFlight.ceilSeconds(newEnd - now))
            plugin.commsManager.send(
                player,
                Component.text("Fly Voucher Redeemed!", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true)
                    .append(Component.newline())
                    .append(Component.text("Added: ", NamedTextColor.GRAY)).append(Component.text(SurvivalFlight.short(voucher.seconds), NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Total Flight Time: ", NamedTextColor.GRAY)).append(Component.text(total, NamedTextColor.YELLOW))
                    .append(Component.newline())
                    .append(Component.text("Use /fly to fly and /flytime to check your remaining time.", NamedTextColor.GRAY))
            )
        } finally {
            redeeming.remove(player.uniqueId)
        }
    }

    companion object {
        const val TYPE = "fly"
        const val VOUCHER_VERSION = 1
        const val MAX_QUANTITY = 64
    }
}
