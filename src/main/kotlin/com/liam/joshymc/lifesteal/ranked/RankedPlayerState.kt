package com.liam.joshymc.lifesteal.ranked

import com.liam.joshymc.lifesteal.combat.npc.CombatNpcRules

/**
 * Issue #1490 (audit finding B-1): the experience and potion effects a participant had before a
 * ranked match, restored together with (and exactly like) their inventory snapshot. Bukkit-free.
 *
 * Without this, a player kept every XP point and status effect gained in the match (Bottles o'
 * Enchanting, potions, golden apples, Ominous Bottles, beacons) while the consumables themselves
 * came back from the snapshot, so each match was free, repeatable XP and buffs.
 *
 * Policy:
 * - **XP** is *set* back to the stored level, progress and total experience (never added), so
 *   applying a snapshot twice gives the same XP and nothing gained in the match survives.
 * - **Effects**: every effect the player has when restored is removed, then the pre-match effects
 *   are put back with the time they would have left now. Pre-match effects are **not frozen**: they
 *   keep counting down in real time during the match and while the player is offline (exactly like
 *   the Combat NPC), so a match never extends one; infinite effects stay infinite; ran-out ones are
 *   dropped. Effects gained or strengthened in the match (incl. Bad Omen / Trial Omen / Raid Omen)
 *   are never kept.
 * - **Older snapshots** (made before this issue) have no stored XP/effects: their XP and effects are
 *   left exactly as they are, never reset to an invented value.
 */
object RankedPlayerState {

    /** Experience as Paper reports it: level, progress toward the next level (0..1), total points. */
    data class Xp(val level: Int, val progress: Float, val total: Int)

    /** What restoring a snapshot does to XP and effects; null = leave that part untouched (older snapshot). */
    data class Plan(val xp: Xp?, val effects: List<CombatNpcRules.Effect>?)

    /**
     * Consumables refused while a match holds the player. An Ominous Bottle has no combat use: its
     * only effect (Bad Omen) acts *outside* the fight (raids, ominous trials), and a raid started
     * near the arena would keep going after the restore removed the omen. Everything else (potions,
     * golden apples, Bottles o' Enchanting, ...) stays usable; restoring undoes what it gave.
     */
    val REFUSED_CONSUMABLES = setOf("minecraft:ominous_bottle")

    fun consumeRefused(itemKey: String): Boolean = itemKey in REFUSED_CONSUMABLES

    class CorruptStateException(message: String) : RuntimeException(message)

    /** "" for no effects, so an empty list is stored as "had none" (NULL means an older snapshot). */
    fun encodeEffects(effects: List<CombatNpcRules.Effect>): String = CombatNpcRules.encodeEffects(effects)

    /**
     * The XP and effects to apply when restoring a snapshot taken at [snapshotAt], at [now].
     * Throws [CorruptStateException] when the stored XP is only partly there or out of range, so the
     * restore changes nothing and the snapshot stays pending (never a guessed XP value).
     */
    fun plan(xpLevel: Int?, xpProgress: Double?, xpTotal: Int?, effects: String?, snapshotAt: Long, now: Long): Plan =
        Plan(xp(xpLevel, xpProgress, xpTotal), effects?.let { CombatNpcRules.effectsAt(CombatNpcRules.decodeEffects(it), snapshotAt, now) })

    fun xp(level: Int?, progress: Double?, total: Int?): Xp? {
        if (level == null && progress == null && total == null) return null
        if (level == null || progress == null || total == null) throw CorruptStateException("the stored experience is incomplete")
        if (level < 0 || total < 0 || progress.isNaN() || progress < 0.0 || progress > 1.0) {
            throw CorruptStateException("the stored experience is out of range (level $level, progress $progress, total $total)")
        }
        return Xp(level, progress.toFloat(), total)
    }
}
