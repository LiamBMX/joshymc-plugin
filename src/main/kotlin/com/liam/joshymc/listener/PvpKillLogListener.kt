package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.dv8tion.jda.api.EmbedBuilder
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Posts a Discord embed for every PvP kill (issue #974) — killer, victim, weapon,
 * location, and time. Read-only reporting: never touches kill streaks, bounties,
 * drops, or any other combat mechanic (see [com.liam.joshymc.manager.KillStreakManager]
 * / [CombatListener] for those).
 *
 * `LivingEntity.killer` (used here via [PlayerDeathEvent.getEntity]) already resolves the
 * shooter for indirect/projectile kills — same assumption [CombatListener] and
 * [com.liam.joshymc.manager.KillStreakManager] rely on — and is null for mob kills,
 * environmental deaths, and suicides, which is exactly what should stay unannounced.
 *
 * Sending is fire-and-forget via [com.liam.joshymc.discord.DiscordManager.sendEmbedToChannel],
 * which queues onto the existing async-flushed bot connection — never a second bot, never a
 * blocking call on the main thread, and a Discord outage only logs a warning there.
 */
class PvpKillLogListener(private val plugin: Joshymc) : Listener {

    private val enabled: Boolean get() = plugin.config.getBoolean("pvp-kill-logs.enabled", true)
    private val discordChannelId: String get() = plugin.config.getString("pvp-kill-logs.discord.channel-id") ?: ""

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        if (!enabled || discordChannelId.isEmpty()) return

        val victim = event.entity
        val killer = victim.killer ?: return
        if (killer.uniqueId == victim.uniqueId) return

        val weapon = describeWeapon(victim, killer)
        val location = victim.location
        val world = location.world?.name ?: "unknown"
        val time = ZonedDateTime.now(plugin.timezoneManager.zoneFor(killer)).format(TIME_FORMAT)

        val embed = EmbedBuilder()
            .setTitle("⚔️ Player Kill")
            .setColor(0xED4245)
            .addField("Killer", killer.name, true)
            .addField("Victim", victim.name, true)
            .addField("Weapon", weapon, true)
            .addField("Location", "$world (${location.blockX}, ${location.blockY}, ${location.blockZ})", false)
            .addField("Time", time, true)
            .setTimestamp(Instant.now())
            .build()

        plugin.discordManager.sendEmbedToChannel(discordChannelId, embed)
    }

    /**
     * Best-effort weapon label. A thrown trident is reported as "Trident" outright since
     * the killer's hands are empty mid-flight; everything else — melee or a
     * bow/crossbow-fired arrow — reads the killer's main hand, which is still the weapon
     * they killed with at the moment the death event fires.
     */
    private fun describeWeapon(victim: Player, killer: Player): String {
        val damager = (victim.lastDamageCause as? EntityDamageByEntityEvent)?.damager
        if (damager?.type == EntityType.TRIDENT) return "Trident"
        val weapon = killer.inventory.itemInMainHand
        if (weapon.type == Material.AIR) return "Fists"
        return weapon.type.name.split("_").joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
    }

    companion object {
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    }
}
