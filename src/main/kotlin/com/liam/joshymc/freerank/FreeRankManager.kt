package com.liam.joshymc.freerank

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.DonorTiers
import com.liam.joshymc.manager.RankManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.util.UUID

/**
 * `/freerank` (issue #1295): claim the free starter rank of the gamemode the player is in,
 * once per gamemode. The grant is the same checked one Rank Vouchers and Credit Shop rank
 * packages use ([RankManager.grantDonorTier]: one `parent add` by UUID, success only once
 * LuckPerms shows the group), under the shared donor-change lock, so it never downgrades,
 * never removes another group and can't race a voucher or a second `/freerank`.
 *
 * The claim is reserved PENDING before the grant and only becomes COMPLETED on confirmation;
 * any failure deletes the reservation so it can be claimed again. A claim left PENDING by a
 * player who quit (or a stop) mid-confirmation is settled on their next join.
 */
class FreeRankManager(private val plugin: Joshymc) : Listener {

    private val store = FreeRankStore(plugin.databaseManager)

    fun start() {
        store.createTables()
        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            Bukkit.getOnlinePlayers().forEach { resolvePending(it, 0) }
        }, PENDING_RESOLVE_DELAY_TICKS)
    }

    fun hasPending(uuid: UUID): Boolean = store.hasPending(uuid)

    fun claim(player: Player) {
        val uuid = player.uniqueId
        val gamemode = plugin.gamemodeLockManager.gamemodeOf(player)
        val starter = FreeRanks.starterRank(gamemode)
        if (starter == null) {
            Msg.error(player, "There is no free rank to claim on ${gamemode.displayName} yet.")
            return
        }
        val rankName = DonorTiers.displayName(starter)

        if (!plugin.rankManager.tryLockDonorChange(uuid)) {
            Msg.error(player, "Your last rank change is still processing. Try again in a moment.")
            return
        }
        var handedOff = false
        try {
            val current = plugin.rankManager.currentDonorTier(uuid)
            when (FreeRanks.decide(starter, current, store.status(uuid, gamemode))) {
                FreeRanks.Decision.ALREADY_OWNED -> {
                    Msg.error(player, "You already have this rank or a higher rank!")
                    return
                }
                FreeRanks.Decision.ALREADY_CLAIMED -> {
                    Msg.error(player, "You have already claimed your free $rankName rank.")
                    return
                }
                FreeRanks.Decision.PENDING -> {
                    Msg.error(player, "Your free $rankName rank is still being applied. Try again in a moment.")
                    return
                }
                FreeRanks.Decision.CLAIM -> {}
            }
            if (plugin.rankVoucherManager.hasPendingRedemption(uuid) || plugin.voucherManager.hasPendingRankPurchase(uuid)) {
                Msg.error(player, "A previous rank change is still being confirmed. Try again in a moment.")
                return
            }
            if (!plugin.rankManager.isLuckPermsAvailable()) {
                Msg.error(player, "Free ranks can't be claimed right now. Please try again later.")
                plugin.logger.warning("[FreeRank] ${player.name} tried to claim $starter but LuckPerms is not enabled.")
                return
            }
            if (!store.reserve(uuid, player.name, gamemode, starter, System.currentTimeMillis())) {
                Msg.error(player, "You have already claimed your free $rankName rank.")
                return
            }

            Msg.reply(player, Component.text("Applying your free $rankName rank...", NamedTextColor.GRAY))
            handedOff = true
            plugin.rankManager.grantDonorTier(player, starter) { result ->
                try {
                    finishClaim(player, gamemode, starter, current, result)
                } finally {
                    plugin.rankManager.unlockDonorChange(uuid)
                }
            }
        } finally {
            if (!handedOff) plugin.rankManager.unlockDonorChange(uuid)
        }
    }

    private fun finishClaim(player: Player, gamemode: ServerGamemode, rank: String, previous: String?, result: RankManager.DonorGrantResult) {
        val rankName = DonorTiers.displayName(rank)
        when (result) {
            RankManager.DonorGrantResult.CONFIRMED -> {
                store.markCompleted(player.uniqueId, gamemode)
                Msg.success(player, "You successfully claimed your free $rankName rank!")
                plugin.logger.info("[FreeRank] ${player.name} (${player.uniqueId}) claimed $rank on ${gamemode.id} (was ${previous ?: "none"}).")
            }
            // Stays PENDING; resolvePending settles it when they rejoin.
            RankManager.DonorGrantResult.PLAYER_LEFT -> {}
            else -> {
                store.release(player.uniqueId, gamemode)
                if (player.isOnline) Msg.error(player, "Your free $rankName rank couldn't be applied. You can try /freerank again later.")
                plugin.logger.warning("[FreeRank] Granting $rank to ${player.name} (${player.uniqueId}) on ${gamemode.id} failed: $result. Claim released.")
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { resolvePending(player, 0) }, PENDING_RESOLVE_DELAY_TICKS)
    }

    /**
     * Settles claims left PENDING by a player who quit (or a server that stopped) before
     * LuckPerms confirmed: completed when the group is there, otherwise the request is undone
     * and the claim released. Each row changes through a conditional update, so once only.
     */
    private fun resolvePending(player: Player, attempt: Int) {
        if (!player.isOnline) return
        val rows = store.pending(player.uniqueId)
        if (rows.isEmpty()) return
        if (!plugin.rankManager.isLuckPermsAvailable()) {
            plugin.logger.warning("[FreeRank] ${player.name} has ${rows.size} pending free rank claim(s) but LuckPerms is not enabled; leaving them pending.")
            return
        }
        if (!plugin.rankManager.tryLockDonorChange(player.uniqueId)) {
            if (attempt < PENDING_RESOLVE_MAX_ATTEMPTS) {
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { resolvePending(player, attempt + 1) }, PENDING_RESOLVE_DELAY_TICKS)
            }
            return
        }
        try {
            for (claim in rows) {
                val rank = DonorTiers.normalize(claim.rankId)
                if (rank == null) {
                    plugin.logger.warning("[FreeRank] Pending claim for ${player.name} on ${claim.gamemode.id} names unknown rank '${claim.rankId}'; left for an admin.")
                    continue
                }
                val rankName = DonorTiers.displayName(rank)
                if (plugin.rankManager.isDonorGroupConfirmed(player, rank)) {
                    if (store.markCompleted(player.uniqueId, claim.gamemode)) {
                        plugin.rankManager.completeDonorGrant(player.uniqueId, rank)
                        Msg.success(player, "You successfully claimed your free $rankName rank!")
                        plugin.logger.info("[FreeRank] Settled pending claim for ${player.name} on ${claim.gamemode.id}: $rank confirmed in LuckPerms.")
                    }
                } else {
                    plugin.rankManager.revokeUnconfirmedDonorGrant(player.uniqueId, rank)
                    if (store.release(player.uniqueId, claim.gamemode)) {
                        Msg.error(player, "Your free $rankName rank didn't go through. You can claim it again with /freerank.")
                        plugin.logger.warning("[FreeRank] Settled pending claim for ${player.name} on ${claim.gamemode.id}: $rank not in LuckPerms, claim released.")
                    }
                }
            }
        } finally {
            plugin.rankManager.unlockDonorChange(player.uniqueId)
        }
    }

    companion object {
        private const val PENDING_RESOLVE_DELAY_TICKS = 40L
        private const val PENDING_RESOLVE_MAX_ATTEMPTS = 5
    }
}
