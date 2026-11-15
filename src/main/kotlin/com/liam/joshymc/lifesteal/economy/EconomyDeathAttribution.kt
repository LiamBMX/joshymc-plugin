package com.liam.joshymc.lifesteal.economy

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.eligible
import com.liam.joshymc.lifesteal.hearts.HeartRules
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import java.util.UUID

/**
 * The one death attribution of the Lifesteal economy (issue #1548): PvP coin stealing
 * (LifestealBankManager) and bounty claims (LifestealBountyManager) both read [resolve] at
 * MONITOR, so they always see the same killer, the same ranked match and the same tick (their
 * dedupe keys are victim + tick, whoever is credited).
 *
 * [capture] runs at LOWEST of the same PlayerDeathEvent (registered with the bank listener,
 * wherever Lifesteal is hosted) and copies the victim's combat tag, its recent PvP attackers and
 * their ranked match before CombatListener (NORMAL) and the ranked death handler (HIGHEST) clear
 * them. Main thread only; at most one copy and one resolution per online player, each used only in
 * its own tick and dropped on quit. Combat NPC deaths never come through here: they are judged by
 * their direct killer only (no environmental credit).
 */
class EconomyDeathAttribution(private val plugin: Joshymc) {

    private val captures = HashMap<UUID, EconomyDeathRules.Capture>()
    private val resolved = HashMap<UUID, EconomyDeathRules.Resolution>()

    private fun isLifesteal(player: Player): Boolean = plugin.gamemodeLockManager.gamemodeOf(player.world) == ServerGamemode.LIFESTEAL

    /** PlayerDeathEvent at LOWEST. */
    fun capture(event: PlayerDeathEvent) {
        val uuid = event.player.uniqueId
        captures.remove(uuid)
        resolved.remove(uuid)
        if (!isLifesteal(event.player)) return
        val combat = plugin.combatManager
        val ranked = try { plugin.lifestealRankedManager.matchOf(uuid)?.id } catch (_: UninitializedPropertyAccessException) { null }
        captures[uuid] = EconomyDeathRules.Capture(
            tick = Bukkit.getCurrentTick(),
            at = System.currentTimeMillis(),
            tagExpiresAt = combat.tagExpiresAt(uuid),
            hits = combat.recentPvpAttackers(uuid).map { HeartRules.RecentHit(it.attacker, it.at) },
            rankedMatch = ranked,
            combatLogged = combat.isCombatLogged(event.player),
        )
    }

    fun forget(uuid: UUID) {
        captures.remove(uuid)
        resolved.remove(uuid)
    }

    /**
     * The heart system's attribution ([HeartRules.attribute]) without heart records: coins and
     * bounties don't need a loaded heart record, so both sides count as loaded.
     */
    fun attribute(victim: UUID, victimInLifesteal: Boolean, causing: UUID?, killer: Player?): HeartRules.Attribution =
        HeartRules.attribute(
            HeartRules.Death(
                victim = victim,
                victimInLifesteal = victimInLifesteal,
                victimLoaded = true,
                causingPlayer = causing,
                killerOnline = killer?.isOnline == true,
                killerInLifesteal = killer != null && isLifesteal(killer),
                killerLoaded = true,
                killerIsNpc = killer != null && killer.hasMetadata("NPC"),
            )
        )

    /** The economy's answer for this death, computed once per death and shared by coins and bounties. */
    fun resolve(event: PlayerDeathEvent): EconomyDeathRules.Resolution {
        val victim = event.player
        val tick = Bukkit.getCurrentTick()
        resolved[victim.uniqueId]?.takeIf { it.tick == tick }?.let { return it }
        // Kept (not removed) until the player's next death or quit: a victim who dies later in the
        // same tick may still need this player's copy as their killer's ([EconomyDeathRules.rankedMatch]).
        val capture = captures[victim.uniqueId]
        val causing = event.damageSource.causingEntity as? Player
        val directKiller = causing?.takeIf { it.uniqueId != victim.uniqueId }
        val victimInLifesteal = isLifesteal(victim)
        val direct = attribute(victim.uniqueId, victimInLifesteal, causing?.uniqueId, directKiller)
        val ranked = try { plugin.lifestealRankedManager } catch (_: UninitializedPropertyAccessException) { null }
        val rankedMatch = EconomyDeathRules.rankedMatch(
            victim = capture,
            killer = directKiller?.let { captures[it.uniqueId] },
            currentTick = tick,
            victimHeld = ranked?.matchOf(victim.uniqueId)?.id,
            killerHeld = directKiller?.let { ranked?.matchOf(it.uniqueId)?.id },
        )
        val damageType = runCatching { event.damageSource.damageType.key.toString() }.getOrNull()
        val resolution = EconomyDeathRules.resolve(
            victim = victim.uniqueId,
            direct = direct,
            directKiller = directKiller?.uniqueId,
            capture = capture,
            currentTick = tick,
            now = System.currentTimeMillis(),
            tagMillis = plugin.combatManager.tagDurationMillis,
            rankedMatch = rankedMatch,
            combatLoggedNow = plugin.combatManager.isCombatLogged(victim),
            damageType = damageType,
            eligible = { attacker ->
                val candidate = Bukkit.getPlayer(attacker)
                candidate != null && attribute(victim.uniqueId, victimInLifesteal, attacker, candidate) == HeartRules.Attribution.PVP_KILL &&
                    // Issue #1487: an attacker who used staff abilities can't be credited through the tag either.
                    plugin.competitiveEligibility.verdict(candidate, victim.uniqueId).eligible
            },
            reattribute = { attacker -> attribute(victim.uniqueId, victimInLifesteal, attacker, Bukkit.getPlayer(attacker)) },
        )
        resolved[victim.uniqueId] = resolution
        return resolution
    }
}
