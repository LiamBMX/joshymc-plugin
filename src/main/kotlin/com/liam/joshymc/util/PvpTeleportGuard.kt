package com.liam.joshymc.util

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CombatManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the PvP world isolated from player-driven teleports (issue #1075).
 *
 * Two layers:
 *  - [blocks] — called by JoshyMC's own player teleport commands (/spawn,
 *    /home, /warp, /tpa, /back, /rtp, ...) before and at the end of their
 *    warmups. Refuses any teleport where the player is in, or the destination
 *    is in, the PvP world — including moves *within* it.
 *  - [com.liam.joshymc.listener.PvpTeleportListener] — cancels any
 *    player-controlled teleport (command, plugin, ender pearl) that
 *    crosses the PvP world boundary, whichever plugin or alias issued it.
 *
 * Staff/internal teleports pass through either by running inside [forced]
 * (JoshyMC staff tools, AFK round-trip, admin-built portals) or by happening in
 * the same tick as a console command or a command typed by staff holding
 * [BYPASS_PERMISSION] or a staff teleport permission (vanilla /tp, other
 * plugins' admin teleports). Players holding [BYPASS_PERMISSION] are never
 * restricted.
 */
object PvpTeleportGuard {

    const val BYPASS_PERMISSION = "joshymc.pvp.teleport.bypass"
    private const val NOTIFY_COOLDOWN_MS = 1000L

    /** Permissions that make a command issuer a staff teleporter. */
    private val STAFF_TELEPORT_PERMISSIONS = listOf(
        BYPASS_PERMISSION, "joshymc.tp", "joshymc.tphere", "minecraft.command.teleport"
    )

    @PublishedApi internal var forcedDepth = 0
    private var authorizedTick = -1
    private val lastNotified = ConcurrentHashMap<UUID, Long>()

    fun isEnabled(plugin: Joshymc): Boolean = plugin.config.getBoolean("pvp.block-player-teleports", true)

    fun isPvpWorld(world: World?): Boolean = world?.name == CombatManager.PVP_WORLD_NAME

    fun canBypass(player: Player): Boolean = player.hasPermission(BYPASS_PERMISSION)

    /** Runs [block] with PvP-world teleport checks lifted — staff and internal teleports only. */
    inline fun <T> forced(block: () -> T): T {
        forcedDepth++
        try {
            return block()
        } finally {
            forcedDepth--
        }
    }

    /** True while inside [forced] or in the tick of an authorized staff/console command. */
    fun isForced(): Boolean = forcedDepth > 0 || authorizedTick == Bukkit.getCurrentTick()

    /** Records that [sender] is issuing a command this tick; staff and console authorize its teleports. */
    fun onCommandIssued(sender: CommandSender) {
        if (sender is Player && STAFF_TELEPORT_PERMISSIONS.none { sender.hasPermission(it) }) return
        authorizedTick = Bukkit.getCurrentTick()
    }

    /**
     * Player-driven teleport of [player] to [destination]. Returns true (and
     * tells [notify]) when it must be refused: the player or the destination
     * is in the PvP world. A null [destination] checks only the player's side.
     */
    fun blocks(plugin: Joshymc, player: Player, destination: Location?, notify: Player = player): Boolean {
        if (!isEnabled(plugin) || canBypass(player)) return false
        if (!isPvpWorld(player.world) && !isPvpWorld(destination?.world)) return false
        notify(plugin, notify)
        return true
    }

    /** Sends the block message, at most once per second per player. */
    fun notify(plugin: Joshymc, player: Player) {
        val now = System.currentTimeMillis()
        val last = lastNotified[player.uniqueId]
        if (last != null && now - last < NOTIFY_COOLDOWN_MS) return
        lastNotified[player.uniqueId] = now
        plugin.commsManager.send(player, Component.text("You cannot teleport into or out of the PvP world.", NamedTextColor.RED))
    }

    fun clear(player: Player) {
        lastNotified.remove(player.uniqueId)
    }
}
