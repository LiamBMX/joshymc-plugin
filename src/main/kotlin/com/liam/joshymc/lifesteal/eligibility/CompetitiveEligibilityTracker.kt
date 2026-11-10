package com.liam.joshymc.lifesteal.eligibility

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.Reason
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility.Verdict
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * Issue #1487 (D-H4): live side of [CompetitiveEligibility]. Reads the attacker's real state
 * (game mode, `/god`, `/vanish`, Mod Mode, enchantment levels of the equipment used), remembers
 * hits made while ineligible, stamps projectiles with the shooter's state at launch, and is asked
 * by every competitive reward at settlement: the heart store request (normal kills, combat-tag
 * credit, ranked kills and forfeits, Combat NPC kills), bounty claims and PvP coin stealing.
 * Main thread only.
 */
class CompetitiveEligibilityTracker(private val plugin: Joshymc) {

    private val memory = CompetitiveEligibility.HitMemory()
    private val auditThrottle = CompetitiveEligibility.Throttle()
    private val messageThrottle = CompetitiveEligibility.Throttle()

    /** Projectile PDC: the shooter's ineligibility reasons at launch (`creative,god_mode`). */
    val projectileKey = NamespacedKey(plugin, "competitive_ineligible")

    /** Custom item id -> the enchantment levels that item is built with (lifts [CompetitiveEligibility.overLimit] for it). */
    private val customItemLevels = HashMap<String, Map<String, Int>>()

    private val windowMillis: Long get() = CompetitiveEligibility.memoryMillis(plugin.combatManager.tagDurationMillis)

    // ── State ───────────────────────────────────────────────

    /**
     * What makes [player] ineligible right now. [weapons] are the items used for this hit (main
     * hand for melee, both hands at a projectile launch); worn armor always counts. Items in the
     * inventory or storage never do.
     */
    fun currentReasons(player: Player, weapons: List<ItemStack?> = emptyList()): Set<Reason> {
        val mode = when (player.gameMode) {
            GameMode.CREATIVE -> CompetitiveEligibility.Mode.CREATIVE
            GameMode.SPECTATOR -> CompetitiveEligibility.Mode.SPECTATOR
            GameMode.ADVENTURE -> CompetitiveEligibility.Mode.ADVENTURE
            else -> CompetitiveEligibility.Mode.SURVIVAL
        }
        val equipment = weapons + player.inventory.armorContents.toList()
        return CompetitiveEligibility.problems(
            CompetitiveEligibility.State(
                mode = mode,
                godMode = safe { player.uniqueId in plugin.godCommand.godPlayers },
                vanished = safe { plugin.vanishCommand.isVanished(player) },
                modMode = safe { plugin.modModeManager.isModMode(player) },
                unsafeEquipment = equipment.any { unsafe(it) },
            )
        )
    }

    /** Whether [stack] carries a vanilla enchantment above its vanilla maximum (custom items: above their own build). */
    fun unsafe(stack: ItemStack?): Boolean {
        if (stack == null || stack.type.isAir) return false
        val enchants = stack.enchantments
        if (enchants.isEmpty()) return false
        val levels = enchants.map { (enchant, level) -> CompetitiveEligibility.EnchantLevel(enchant.key.toString(), level, enchant.maxLevel) }
        val allowed = safe(emptyMap()) { plugin.itemManager.getCustomItemId(stack)?.let(::builtLevels) } ?: emptyMap()
        return CompetitiveEligibility.overLimit(levels, allowed) != null
    }

    private fun builtLevels(id: String): Map<String, Int> = customItemLevels.getOrPut(id) {
        val built = runCatching { plugin.itemManager.getItem(id)?.createItemStack() }.getOrNull()
        built?.enchantments?.entries?.associate { (enchant, level) -> enchant.key.toString() to level } ?: emptyMap()
    }

    // ── Hits ────────────────────────────────────────────────

    /**
     * A hit by [attacker] on [victim] (a player, or a Combat NPC's owner) landed. [projectile] is the
     * entity that hit when it isn't the attacker: its launch stamp counts instead of the held item.
     */
    fun onHit(attacker: Player, victim: UUID, projectile: Entity?) {
        if (attacker.uniqueId == victim) return
        val reasons = if (projectile != null) {
            currentReasons(attacker) + stamped(projectile)
        } else {
            currentReasons(attacker, listOf(attacker.inventory.itemInMainHand))
        }
        memory.record(attacker.uniqueId, victim, reasons, System.currentTimeMillis(), windowMillis)
    }

    /** At launch: remember what the shooter was using, so switching state while it flies changes nothing. */
    fun stampProjectile(projectile: Entity, shooter: Player) {
        val reasons = currentReasons(shooter, listOf(shooter.inventory.itemInMainHand, shooter.inventory.itemInOffHand))
        if (reasons.isEmpty()) return
        projectile.persistentDataContainer.set(projectileKey, PersistentDataType.STRING, reasons.joinToString(",") { it.id })
    }

    private fun stamped(projectile: Entity): Set<Reason> {
        val raw = projectile.persistentDataContainer.get(projectileKey, PersistentDataType.STRING) ?: return emptySet()
        return raw.split(',').mapNotNull { id -> Reason.entries.firstOrNull { it.id == id } }.toSet()
    }

    // ── Settlement ──────────────────────────────────────────

    /**
     * Whether [attacker] may earn a competitive reward from [victim]'s death (a Combat NPC's owner
     * for an NPC kill): their state now (worn armor included) plus every hit they made on this
     * victim inside the window.
     */
    fun verdict(attacker: Player, victim: UUID): Verdict =
        CompetitiveEligibility.verdict(currentReasons(attacker), memory.recorded(attacker.uniqueId, victim, System.currentTimeMillis(), windowMillis))

    /**
     * A reward of [reward] kind (`HEART`, `BOUNTY`, `COINS`) for [attacker] killing [victim] was
     * refused because of [verdict]. Written to `admin_logs` (reused AdminAudit infrastructure) and
     * the console at most once per attacker + reward per minute; the attacker is told once per minute.
     */
    fun reportBlocked(attacker: Player, victim: UUID, victimName: String, reward: String, verdict: Verdict.Ineligible, reference: String?) {
        val now = System.currentTimeMillis()
        if (auditThrottle.allow("${attacker.uniqueId}:$reward", now)) {
            val details = listOfNotNull("reward=$reward", "reasons=${verdict.code}", reference?.let { "ref=$it" }).joinToString(" ")
            try {
                plugin.adminManager.logAction(attacker.uniqueId, attacker.name, "LIFESTEAL_REWARD_BLOCKED", victim, victimName, details)
            } catch (e: Exception) {
                plugin.logger.warning("[Eligibility] Couldn't store a blocked $reward reward for ${attacker.name}: ${e.message}")
            }
            plugin.logger.info("[Eligibility] ${attacker.name} (${attacker.uniqueId}) killed $victimName: no $reward reward ($details).")
        }
        if (messageThrottle.allow(attacker.uniqueId.toString(), now)) {
            plugin.commsManager.send(
                attacker,
                plugin.commsManager.parseLegacy("&7Kills made with ${verdict.labels} don't earn hearts, bounties or coins."),
            )
        }
    }

    private inline fun safe(block: () -> Boolean): Boolean = try { block() } catch (_: UninitializedPropertyAccessException) { false }

    private inline fun <T> safe(fallback: T, block: () -> T): T = try { block() } catch (_: UninitializedPropertyAccessException) { fallback }
}
