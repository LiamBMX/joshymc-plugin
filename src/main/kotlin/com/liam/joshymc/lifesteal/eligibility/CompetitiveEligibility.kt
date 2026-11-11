package com.liam.joshymc.lifesteal.eligibility

import java.util.UUID

/**
 * Issue #1487 (audit finding D-H4), Bukkit-free: the one rule that decides whether an attacker may
 * earn a competitive Lifesteal reward (a heart, a bounty payout, stolen coins) from a kill.
 *
 * It looks at the attacker's actual gameplay state, never their rank or permissions: Creative,
 * Spectator, God Mode, Vanish, Mod Mode, or worn/used equipment with a vanilla enchantment above
 * its vanilla maximum. Owners and admins are judged exactly like everyone else. The abilities
 * themselves stay available for moderation; using them only means the kill earns nothing.
 *
 * Because a player can switch state between hits (Creative for the damage, Survival for the last
 * hit), every hit made while ineligible is remembered per attacker and victim ([HitMemory]) and
 * counts at settlement for one window ([memoryMillis]). `CompetitiveEligibilityTracker` feeds it
 * live data; `scripts/CompetitiveEligibilityCheck.kt` tests it without a server.
 */
object CompetitiveEligibility {

    enum class Reason(val id: String, val label: String) {
        CREATIVE("creative", "Creative mode"),
        SPECTATOR("spectator", "Spectator mode"),
        GOD_MODE("god_mode", "God Mode"),
        VANISHED("vanished", "Vanish"),
        MOD_MODE("mod_mode", "Mod Mode"),
        UNSAFE_ENCHANT("unsafe_enchant", "enchantments above vanilla limits"),
    }

    enum class Mode { SURVIVAL, ADVENTURE, CREATIVE, SPECTATOR }

    /** The attacker's live state. [unsafeEquipment] = the equipment used in this fight has an over-limit vanilla enchant. */
    data class State(
        val mode: Mode,
        val godMode: Boolean = false,
        val vanished: Boolean = false,
        val modMode: Boolean = false,
        val unsafeEquipment: Boolean = false,
    )

    fun problems(state: State): Set<Reason> = buildSet {
        if (state.mode == Mode.CREATIVE) add(Reason.CREATIVE)
        if (state.mode == Mode.SPECTATOR) add(Reason.SPECTATOR)
        if (state.godMode) add(Reason.GOD_MODE)
        if (state.vanished) add(Reason.VANISHED)
        if (state.modMode) add(Reason.MOD_MODE)
        if (state.unsafeEquipment) add(Reason.UNSAFE_ENCHANT)
    }

    sealed interface Verdict {
        data object Eligible : Verdict

        /**
         * [reasons] never empty. [duringCombat] = the attacker is fine right now, but hit this victim
         * while ineligible inside the memory window (switched back before the kill).
         */
        data class Ineligible(val reasons: Set<Reason>, val duringCombat: Boolean) : Verdict {
            init { require(reasons.isNotEmpty()) { "an ineligible verdict needs a reason" } }

            /** Stable, sorted ids for audit rows and logs, e.g. `creative,god_mode` (+ `@combat`). */
            val code: String get() = reasons.sortedBy { it.ordinal }.joinToString(",") { it.id } + if (duringCombat) "@combat" else ""

            /** Human list for the attacker's message: `Creative mode and God Mode`. */
            val labels: String get() = reasons.sortedBy { it.ordinal }.map { it.label }.let {
                if (it.size <= 1) it.joinToString("") else it.dropLast(1).joinToString(", ") + " and " + it.last()
            }
        }
    }

    val Verdict.eligible: Boolean get() = this == Verdict.Eligible

    /** [current] = the attacker's state at settlement; [recorded] = what their earlier hits on this victim were made with. */
    fun verdict(current: Set<Reason>, recorded: Set<Reason>): Verdict {
        val all = current + recorded
        if (all.isEmpty()) return Verdict.Eligible
        return Verdict.Ineligible(all, duringCombat = current.isEmpty())
    }

    // ── Enchantment limits ──────────────────────────────────

    /** One vanilla enchantment on an item: [key] (`minecraft:sharpness`), its [level] and the vanilla [maxLevel]. */
    data class EnchantLevel(val key: String, val level: Int, val maxLevel: Int)

    /**
     * The first enchantment above its vanilla maximum, or null. [allowed] lifts the limit for one
     * key to the level a registered JoshyMC custom item is built with (e.g. the Token's decorative
     * Mending), so existing custom items keep counting as legitimate; anything above that still counts.
     */
    fun overLimit(levels: List<EnchantLevel>, allowed: Map<String, Int> = emptyMap()): EnchantLevel? =
        levels.firstOrNull { it.level > maxOf(it.maxLevel, allowed[it.key] ?: 0) }

    // ── /enchant ────────────────────────────────────────────

    const val MAX_COMMAND_LEVEL = 255

    sealed interface EnchantRequest {
        /** [unsafe] = past a vanilla rule; only allowed with `joshymc.enchant.unsafe` (and always audited). */
        data class Allowed(val unsafe: Boolean) : EnchantRequest
        data class Refused(val message: String) : EnchantRequest
    }

    /**
     * `/enchant` (issue #1487): by default only what vanilla allows ([level] up to [maxLevel], an item
     * the enchantment [applies] to, no [conflict] with an existing enchantment). With [unsafePermitted]
     * all of those may be broken, up to [MAX_COMMAND_LEVEL]. Such an item still never earns Lifesteal
     * rewards ([overLimit]).
     */
    fun enchantRequest(name: String, level: Int, maxLevel: Int, applies: Boolean, conflict: String?, unsafePermitted: Boolean): EnchantRequest {
        if (level < 1 || level > MAX_COMMAND_LEVEL) return EnchantRequest.Refused("Level must be 1-$MAX_COMMAND_LEVEL.")
        val problem = when {
            level > maxLevel -> "$name only goes up to level $maxLevel."
            !applies -> "$name can't be applied to this item."
            conflict != null -> "$name conflicts with $conflict on this item."
            else -> null
        } ?: return EnchantRequest.Allowed(unsafe = false)
        return if (unsafePermitted) EnchantRequest.Allowed(unsafe = true) else EnchantRequest.Refused(problem)
    }

    // ── Hit memory ──────────────────────────────────────────

    /** Shortest memory window; the real one is the combat tag duration when that is longer. */
    const val MIN_MEMORY_MILLIS = 30_000L

    fun memoryMillis(tagMillis: Long): Long = maxOf(tagMillis, MIN_MEMORY_MILLIS)

    /**
     * Ineligible hits, per attacker and victim (a Combat NPC's hits count for its owner). Bounded
     * ([maxEntries], oldest dropped first), memory only, keyed by UUID, so a reconnect keeps it and a
     * restart (which ends every fight) clears it. Not thread-safe: main thread only.
     */
    class HitMemory(private val maxEntries: Int = 4096) {
        private data class Key(val attacker: UUID, val victim: UUID)
        private data class Entry(val reasons: Set<Reason>, val at: Long)

        private val entries = LinkedHashMap<Key, Entry>()

        val size: Int get() = entries.size

        /** Remembers one hit; an eligible hit ([reasons] empty) never erases an earlier ineligible one. */
        fun record(attacker: UUID, victim: UUID, reasons: Set<Reason>, now: Long, windowMillis: Long) {
            if (reasons.isEmpty() || attacker == victim) return
            prune(now, windowMillis)
            val key = Key(attacker, victim)
            val previous = entries.remove(key)?.takeIf { live(it, now, windowMillis) }
            entries[key] = Entry(reasons + previous?.reasons.orEmpty(), now)
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
        }

        /** What [attacker]'s hits on [victim] inside the window were made with (empty = none ineligible). */
        fun recorded(attacker: UUID, victim: UUID, now: Long, windowMillis: Long): Set<Reason> {
            val entry = entries[Key(attacker, victim)] ?: return emptySet()
            return if (live(entry, now, windowMillis)) entry.reasons else emptySet()
        }

        /** A hit "in the future" (clock went back) counts as just now, so it can never expire early. */
        private fun live(entry: Entry, now: Long, windowMillis: Long): Boolean = entry.at >= now || now - entry.at < windowMillis

        private fun prune(now: Long, windowMillis: Long) {
            val iterator = entries.values.iterator()
            while (iterator.hasNext()) if (!live(iterator.next(), now, windowMillis)) iterator.remove()
        }
    }

    // ── Audit throttle ──────────────────────────────────────

    /** One audit row (and one message) per attacker + reward kind per window, so repeated attempts don't spam. */
    const val AUDIT_WINDOW_MILLIS = 60_000L

    class Throttle(private val windowMillis: Long = AUDIT_WINDOW_MILLIS, private val maxEntries: Int = 1024) {
        private val last = LinkedHashMap<String, Long>()

        fun allow(key: String, now: Long): Boolean {
            val previous = last[key]
            if (previous != null && previous <= now && now - previous < windowMillis) return false
            last.remove(key)
            last[key] = now
            while (last.size > maxEntries) last.remove(last.keys.first())
            return true
        }
    }
}
