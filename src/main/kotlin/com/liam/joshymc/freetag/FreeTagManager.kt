package com.liam.joshymc.freetag

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.Msg
import com.liam.joshymc.util.depositItemSafely
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * `/freetag` (issue #1339, Survival only): hands out one free [JoshyMC] Chat Tag Voucher per
 * player, ever. The voucher is the existing Chat Tag voucher ([com.liam.joshymc.manager.ChatTagVoucherManager],
 * the same item `/voucher give <player> chattag_joshymc` gives); this only adds the one-time claim.
 *
 * Order: reserve the claim row (PENDING, primary key = UUID) → deposit the voucher through
 * [depositItemSafely] (never dropped on the ground) → COMPLETED. Anything that keeps the voucher
 * from landing deletes the reservation, so a failed delivery never uses up the claim. The whole
 * claim runs synchronously on the main thread, so spam and reconnects can't interleave with it.
 */
class FreeTagManager(private val plugin: Joshymc) {

    private val store = FreeTagStore(plugin.databaseManager)

    fun start() {
        store.createTables()
        val released = store.releaseAllPending()
        if (released > 0) plugin.logger.warning("[FreeTag] Released $released claim(s) left pending by an interrupted delivery.")
    }

    fun claim(player: Player) {
        val uuid = player.uniqueId
        val inModMode = plugin.modModeManager.isModMode(player)
        val decision = FreeTags.decide(
            gamemode = plugin.gamemodeLockManager.gamemodeOf(player),
            claimed = store.hasClaimed(uuid),
            tagAvailable = plugin.chatTagManager.getVoucherTag(FreeTags.TAG_ID) != null,
            // Mod Mode deposits go to the backed-up inventory, which depositItemSafely checks itself.
            hasRoom = inModMode || player.inventory.firstEmpty() != -1,
        )
        when (decision) {
            FreeTags.Decision.WRONG_GAMEMODE -> {
                Msg.error(player, "/freetag is only available in Survival.")
                return
            }
            FreeTags.Decision.ALREADY_CLAIMED -> {
                send(player, FreeTags.MSG_ALREADY_CLAIMED)
                return
            }
            FreeTags.Decision.TAG_MISSING -> {
                send(player, FreeTags.MSG_DELIVERY_FAILED)
                plugin.logger.warning(
                    "[FreeTag] ${player.name} ran /freetag but the '${FreeTags.TAG_ID}' Chat Tag voucher doesn't exist. " +
                        "Create it with /voucher create ${FreeTags.VOUCHER_ID} <display>."
                )
                return
            }
            FreeTags.Decision.NO_ROOM -> {
                send(player, FreeTags.MSG_DELIVERY_FAILED)
                Msg.reply(player, Component.text("Free up an inventory slot and run /freetag again.", NamedTextColor.GRAY))
                return
            }
            FreeTags.Decision.CLAIM -> {}
        }

        if (!store.reserve(uuid, player.name, FreeTags.VOUCHER_ID, System.currentTimeMillis())) {
            send(player, FreeTags.MSG_ALREADY_CLAIMED)
            return
        }

        val delivered = try {
            val voucher = plugin.chatTagVoucherManager.createVoucherStack(FreeTags.TAG_ID)
            voucher != null && plugin.depositItemSafely(player, voucher) == null
        } catch (ex: Exception) {
            plugin.logger.warning("[FreeTag] Delivering the free chat tag voucher to ${player.name} failed: ${ex.message}")
            false
        }

        if (!delivered) {
            store.release(uuid)
            send(player, FreeTags.MSG_DELIVERY_FAILED)
            plugin.logger.warning("[FreeTag] Couldn't deliver the free chat tag voucher to ${player.name} ($uuid); claim released.")
            return
        }

        store.markCompleted(uuid)
        send(player, FreeTags.MSG_SUCCESS)
        plugin.logger.info("[FreeTag] ${player.name} ($uuid) claimed the free ${FreeTags.VOUCHER_ID} voucher.")
    }

    private fun send(player: Player, legacy: String) {
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy(legacy))
    }
}
