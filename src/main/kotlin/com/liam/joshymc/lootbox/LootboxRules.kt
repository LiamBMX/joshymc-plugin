package com.liam.joshymc.lootbox

/**
 * Weighted prize draws for Lootboxes (issue #1602), Bukkit-free.
 *
 * Each of the [PRIZES] draws picks one entry with probability `weight / (sum of weights still in
 * the pool)`. Draws are independent and **with replacement**, so the same reward can come up more
 * than once, except entries marked `unique`: once drawn, a unique entry leaves the pool for the rest
 * of that opening. The player's chosen slots never enter the draw.
 */
object LootboxDraw {

    const val PRIZES = 5

    data class Entry<T>(val value: T, val weight: Double, val unique: Boolean)

    /** True when [pool] can produce [count] prizes: something may repeat, or there are enough unique entries. */
    fun <T> canFill(pool: List<Entry<T>>, count: Int): Boolean {
        val usable = pool.filter { it.weight.isFinite() && it.weight > 0.0 }
        return usable.isNotEmpty() && (usable.any { !it.unique } || usable.size >= count)
    }

    /**
     * [count] prizes from [pool]; [random] returns a uniform value in [0, 1). Null when the pool
     * can't produce them (see [canFill]); nothing is ever substituted.
     */
    fun <T> draw(pool: List<Entry<T>>, count: Int, random: () -> Double): List<T>? {
        if (!canFill(pool, count)) return null
        val remaining = pool.filter { it.weight.isFinite() && it.weight > 0.0 }.toMutableList()
        val out = ArrayList<T>(count)
        repeat(count) {
            if (remaining.isEmpty()) return null
            val total = remaining.sumOf { it.weight }
            val roll = random().coerceIn(0.0, Math.nextDown(1.0)) * total
            var cumulative = 0.0
            var picked = remaining.last()
            for (entry in remaining) {
                cumulative += entry.weight
                if (roll < cumulative) {
                    picked = entry
                    break
                }
            }
            out += picked.value
            if (picked.unique) remaining.remove(picked)
        }
        return out
    }

    /** The chance a single draw (with every entry still in the pool) lands on each entry. */
    fun <T> chances(pool: List<Entry<T>>): List<Pair<T, Double>> {
        val total = pool.sumOf { it.weight }
        return pool.map { it.value to if (total > 0.0) it.weight / total else 0.0 }
    }
}

/**
 * One player's 5-of-27 pick (issue #1602). Only choices: no slot is tied to a prize. [lock] is
 * the one-shot confirm: it succeeds once, and only with exactly [required] picks.
 */
class LootboxSelection(val required: Int = LootboxDraw.PRIZES, val slots: Int = LootboxLayout.MYSTERY_SLOTS.count()) {

    enum class Toggle { SELECTED, DESELECTED, FULL, INVALID, LOCKED }

    private val picked = LinkedHashSet<Int>()
    var locked: Boolean = false
        private set

    val count: Int get() = picked.size
    val ready: Boolean get() = picked.size == required
    val picks: List<Int> get() = picked.sorted()

    fun isPicked(slot: Int): Boolean = slot in picked

    fun toggle(slot: Int): Toggle = when {
        locked -> Toggle.LOCKED
        slot !in 0 until slots -> Toggle.INVALID
        picked.remove(slot) -> Toggle.DESELECTED
        picked.size >= required -> Toggle.FULL
        else -> { picked += slot; Toggle.SELECTED }
    }

    /** True the first time it's called with exactly [required] picks; false otherwise (and for every later call). */
    fun lock(): Boolean {
        if (locked || !ready) return false
        locked = true
        return true
    }
}

/** Slot layout of the three Lootbox GUIs (54 slots each). */
object LootboxLayout {
    const val SIZE = 54
    /** Selection GUI: the 27 mystery slots. */
    val MYSTERY_SLOTS: IntRange = 0..26
    /** Selection GUI: decorative band with the selection info in its middle. */
    val INFO_BAND: IntRange = 27..44
    const val INFO_SLOT = 40
    const val CANCEL_SLOT = 45
    const val CONFIRM_SLOT = 49

    /** Rolling and summary GUIs: the five prize positions across the centre. */
    val REWARD_SLOTS: List<Int> = listOf(20, 21, 22, 23, 24)
    /** Indicators above and below each prize, lit when that reel stops. */
    fun indicatorSlots(reel: Int): List<Int> = listOf(REWARD_SLOTS[reel] - 9, REWARD_SLOTS[reel] + 9)
    const val TITLE_SLOT = 4
    const val CLAIM_SLOT = 49
}

/**
 * When each reel changes its preview and stops (issue #1602). Purely visual: the prizes are
 * already committed before the plan is made. Intervals grow from `start` to `end` (eased), and
 * reel `i` stops at `firstStop + i × stagger`, so they stop one after another.
 */
object LootboxAnimationPlan {

    data class Plan(
        /** Per reel: ticks at which a new (random) preview is shown, ascending, all before its stop. */
        val changeTicks: List<List<Int>>,
        /** Per reel: the tick at which it shows the real prize. */
        val stopTicks: List<Int>,
        /** Tick at which the summary opens. */
        val endTick: Int,
    ) {
        val reels: Int get() = stopTicks.size
    }

    fun plan(reels: Int, settings: LootboxAnimationSettings): Plan {
        val changes = ArrayList<List<Int>>(reels)
        val stops = ArrayList<Int>(reels)
        for (reel in 0 until reels) {
            val stop = settings.firstStopTicks + reel * settings.staggerTicks
            val ticks = ArrayList<Int>()
            var t = 0
            while (t < stop) {
                ticks += t
                val progress = t.toDouble() / stop
                val interval = settings.startIntervalTicks + (settings.endIntervalTicks - settings.startIntervalTicks) * progress * progress
                t += Math.round(interval).toInt().coerceAtLeast(1)
            }
            changes += ticks
            stops += stop
        }
        val end = (stops.maxOrNull() ?: 0) + settings.summaryDelayTicks
        return Plan(changes, stops, end)
    }
}

/**
 * Where a Lootbox may be opened (issue #1602): never where the inventory is about to be replaced,
 * rolled back or is temporary, so a reward can't be duplicated or lost (the same states Lifesteal
 * kits refuse).
 */
object LootboxRules {

    data class State(
        val dead: Boolean,
        val modMode: Boolean,
        val rankedHeld: Boolean,
        val rankedSpectating: Boolean,
        val rankedRestorePending: Boolean,
        val inEventWorld: Boolean,
        val eventInventoryUnsettled: Boolean,
        val combatTagged: Boolean,
        val spectatorMode: Boolean,
    )

    enum class Refusal(val message: String) {
        DEAD("You can't open a Lootbox right now."),
        MOD_MODE("You can't open Lootboxes in Mod Mode."),
        RANKED_MATCH("You can't open Lootboxes during a Ranked match."),
        RANKED_SPECTATING("You can't open Lootboxes while spectating a Ranked match."),
        RANKED_RESTORE("You can't open Lootboxes until your Ranked inventory is restored."),
        EVENT_WORLD("You can't open Lootboxes in the event world."),
        EVENT_INVENTORY("You can't open Lootboxes until your event inventory is restored."),
        COMBAT("You can't open Lootboxes while in combat."),
        SPECTATOR("You can't open Lootboxes in Spectator mode."),
    }

    fun refusal(state: State): Refusal? = when {
        state.dead -> Refusal.DEAD
        state.modMode -> Refusal.MOD_MODE
        state.rankedHeld -> Refusal.RANKED_MATCH
        state.rankedSpectating -> Refusal.RANKED_SPECTATING
        state.rankedRestorePending -> Refusal.RANKED_RESTORE
        state.inEventWorld -> Refusal.EVENT_WORLD
        state.eventInventoryUnsettled -> Refusal.EVENT_INVENTORY
        state.combatTagged -> Refusal.COMBAT
        state.spectatorMode -> Refusal.SPECTATOR
        else -> null
    }

    /** The signed text a Lootbox item's signature covers: level and item format version, nothing a player can edit. */
    fun signaturePayload(version: Int, level: Int): String = "joshymc-lootbox|v$version|level=$level"

    /** [template] with `{key}` placeholders replaced; unknown placeholders stay as written. */
    fun fill(template: String, values: Map<String, String>): String {
        var out = template
        for ((key, value) in values) out = out.replace("{$key}", value)
        return out
    }

    /**
     * A command reward's command for one player. Only `{player}` (a validated Minecraft name)
     * and `{uuid}` are substituted, so nothing a player types ever reaches the console.
     */
    fun command(template: String, playerName: String, uuid: String): String? {
        if (!Regex("^[A-Za-z0-9_.*]{1,32}$").matches(playerName)) return null
        return template.replace("{player}", playerName).replace("{uuid}", uuid)
    }
}
