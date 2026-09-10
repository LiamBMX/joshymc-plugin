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
 * Physical Chat Tag Vouchers (issue #597) — real, tradeable Name Tag items
 * that unlock a Chat Tag created via `/voucher create <id> <display>` through
 * the existing [ChatTagManager] ownership system on right-click. Distinct
 * from the virtual Credit Shop voucher packages in [VoucherManager] and the
 * other physical voucher kinds ([PhysicalVoucherManager], [CreditVoucherManager],
 * [RankVoucherManager]) — redeeming one never touches money, credits, or ranks.
 *
 * Every voucher gets its own random [voucherUuidKey], the same non-stacking
 * trick as the other physical vouchers: distinct PDC per item means Bukkit's
 * stack-merge check never combines two of them.
 */
class ChatTagVoucherManager(private val plugin: Joshymc) {

    val voucherTypeKey = NamespacedKey(plugin, "voucher_type")
    val chatTagIdKey = NamespacedKey(plugin, "chattag_id")
    val voucherVersionKey = NamespacedKey(plugin, "voucher_version")
    val voucherUuidKey = NamespacedKey(plugin, "voucher_uuid")

    // Per-player redemption lock — closes the main-hand/offhand double-fire and
    // rapid-click-spam duplication windows, same idiom as the other physical vouchers.
    private val redeeming = ConcurrentHashMap.newKeySet<UUID>()

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS chattag_voucher_redemptions (
                voucher_uuid TEXT PRIMARY KEY,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                tag_id TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())
    }

    // ── Item building / identification ─────────────────────────────────────

    fun createVoucherStack(tagId: String): ItemStack? {
        val tag = plugin.chatTagManager.getVoucherTag(tagId) ?: return null
        val tagDisplay = plugin.commsManager.parseLegacy(tag.display.trim())
        val stack = ItemStack(Material.NAME_TAG, 1)
        stack.editMeta { meta ->
            meta.displayName(
                tagDisplay.append(Component.text(" Chat Tag Voucher", NamedTextColor.LIGHT_PURPLE))
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(listOf(
                Component.text("Right-click to redeem", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Unlocks Chat Tag: ", NamedTextColor.GRAY).append(tagDisplay).decoration(TextDecoration.ITALIC, false)
            ))
            meta.persistentDataContainer.set(voucherTypeKey, PersistentDataType.STRING, "chattag")
            meta.persistentDataContainer.set(chatTagIdKey, PersistentDataType.STRING, tag.id)
            meta.persistentDataContainer.set(voucherVersionKey, PersistentDataType.INTEGER, VOUCHER_VERSION)
            meta.persistentDataContainer.set(voucherUuidKey, PersistentDataType.STRING, UUID.randomUUID().toString())
        }
        return stack
    }

    fun isChatTagVoucher(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.get(voucherTypeKey, PersistentDataType.STRING) == "chattag"
    }

    /** Gives [quantity] separate 1-count voucher items (never a stack) for [tagId]. Returns false for an unknown/non-voucher tag. */
    fun give(player: Player, tagId: String, quantity: Int): Boolean {
        if (plugin.chatTagManager.getVoucherTag(tagId) == null) return false
        repeat(quantity) {
            val stack = createVoucherStack(tagId) ?: return@repeat
            val leftover = player.inventory.addItem(stack)
            for ((_, item) in leftover) {
                player.world.dropItemNaturally(player.location, item)
            }
        }
        return true
    }

    // ── Redemption ───────────────────────────────────────────────────────

    fun redeem(player: Player, heldItem: ItemStack, takeOne: () -> Unit) {
        if (!isChatTagVoucher(heldItem)) return

        // A legitimate voucher is always amount == 1 (non-stacking is enforced by the
        // unique PDC uuid). Amount > 1 can only happen via an external NBT-editing tool
        // that force-stacked identical PDC onto multiple items — refuse rather than
        // unlock the tag repeatedly.
        if (heldItem.amount != 1) {
            plugin.commsManager.send(
                player,
                Component.text("This voucher stack is invalid and cannot be redeemed.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            plugin.logger.warning("[ChatTagVouchers] ${player.name} attempted to redeem a malformed voucher stack (amount=${heldItem.amount}).")
            return
        }

        if (!redeeming.add(player.uniqueId)) return
        try {
            val pdc = heldItem.itemMeta?.persistentDataContainer
            val version = pdc?.get(voucherVersionKey, PersistentDataType.INTEGER)
            val tagId = pdc?.get(chatTagIdKey, PersistentDataType.STRING)
            val voucherUuid = pdc?.get(voucherUuidKey, PersistentDataType.STRING)
            val tag = tagId?.let { plugin.chatTagManager.getTag(it) }

            if (version != VOUCHER_VERSION || tagId == null || tag == null || voucherUuid == null) {
                plugin.commsManager.send(
                    player,
                    Component.text("This voucher is invalid and cannot be redeemed.", NamedTextColor.RED),
                    CommunicationsManager.Category.ECONOMY
                )
                plugin.logger.warning("[ChatTagVouchers] ${player.name} attempted to redeem a malformed voucher (version=$version, tag=$tagId, uuid=$voucherUuid).")
                return
            }

            val tagDisplay = plugin.commsManager.parseLegacy(tag.display.trim())

            // Already owned — leave the physical voucher untouched so it can still
            // be traded/given to someone else, per the issue's spec.
            if (plugin.chatTagManager.canUse(player, tag)) {
                plugin.commsManager.send(
                    player,
                    Component.text("You already own the ", NamedTextColor.YELLOW)
                        .append(tagDisplay)
                        .append(Component.text(" Chat Tag.", NamedTextColor.YELLOW)),
                    CommunicationsManager.Category.ECONOMY
                )
                return
            }

            // Atomically reserve this voucher's unique id first, same anti-dupe idiom as
            // CreditVoucherManager/RankVoucherManager — stops a duplicated physical item
            // (same PDC uuid copied via an external exploit) from ever redeeming twice.
            if (!reserveVoucherId(voucherUuid, player, tag.id)) {
                plugin.commsManager.send(
                    player,
                    Component.text("This voucher has already been redeemed.", NamedTextColor.RED),
                    CommunicationsManager.Category.ECONOMY
                )
                return
            }

            takeOne()

            try {
                plugin.chatTagManager.unlockTag(player.uniqueId, tag.id)
            } catch (ex: Exception) {
                plugin.logger.warning("[ChatTagVouchers] Unlock failed for ${player.name} (${tag.id}): ${ex.message}")
                give(player, tag.id, 1)
                releaseVoucherId(voucherUuid)
                plugin.commsManager.send(player, Component.text("Redemption failed, your voucher has been returned.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                return
            }

            plugin.commsManager.send(
                player,
                Component.text("You unlocked the ", NamedTextColor.GREEN)
                    .append(tagDisplay)
                    .append(Component.text(" Chat Tag!", NamedTextColor.GREEN)),
                CommunicationsManager.Category.ECONOMY
            )
        } finally {
            redeeming.remove(player.uniqueId)
        }
    }

    private fun reserveVoucherId(voucherUuid: String, player: Player, tagId: String): Boolean {
        val rows = plugin.databaseManager.executeUpdate(
            "INSERT OR IGNORE INTO chattag_voucher_redemptions (voucher_uuid, uuid, player_name, tag_id, timestamp) VALUES (?, ?, ?, ?, ?)",
            voucherUuid, player.uniqueId.toString(), player.name, tagId, System.currentTimeMillis()
        )
        return rows > 0
    }

    private fun releaseVoucherId(voucherUuid: String) {
        plugin.databaseManager.execute("DELETE FROM chattag_voucher_redemptions WHERE voucher_uuid = ?", voucherUuid)
    }

    companion object {
        const val VOUCHER_VERSION = 1
        const val MAX_QUANTITY = 10_000
    }
}
