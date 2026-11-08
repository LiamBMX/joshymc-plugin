package com.liam.joshymc.milestone

/**
 * One player's milestone progress in memory (issue #1420), Bukkit-free. Every track (objective or
 * category level) starts at Tier 1 with zero progress. Progress stops at the tier's requirement:
 * the track is then claimable and stays paused until [applyClaim] moves it to the next tier with
 * progress 0. Objectives and the category level fill independently: an activity still counts toward
 * the category level while one of its objectives waits for a claim, and the other way round.
 *
 * Not thread-safe: the manager only touches it on the main thread.
 */
class MilestoneBook(private var catalog: MilestoneCatalog) {

    class Track(tier: Int, progress: Long) {
        var tier: Int = MilestoneMath.clampTier(tier)
            internal set
        var progress: Long = progress.coerceAtLeast(0L)
            internal set
    }

    data class Snapshot(val key: String, val tier: Int, val progress: Long)

    /** What one recorded activity did. [completed] are the tracks that just reached their requirement. */
    data class RecordResult(val counted: Boolean, val completed: List<String>)

    private val tracks = HashMap<String, Track>()
    private val dirty = LinkedHashSet<String>()

    /** Loaded rows; unknown keys (removed objectives) are kept untouched, invalid values are clamped. */
    fun load(rows: Map<String, Pair<Int, Long>>) {
        for ((key, value) in rows) {
            tracks[key] = Track(value.first, value.second)
        }
    }

    fun updateCatalog(newCatalog: MilestoneCatalog) {
        catalog = newCatalog
    }

    fun tier(key: String): Int = tracks[key]?.tier ?: 1
    fun progress(key: String): Long {
        val track = tracks[key] ?: return 0L
        val required = catalog.requiredScaled(key, track.tier) ?: return track.progress
        return track.progress.coerceAtMost(required)
    }

    fun required(key: String): Long = catalog.requiredScaled(key, tier(key)) ?: Long.MAX_VALUE

    fun isClaimable(key: String): Boolean {
        if (!catalog.isKnown(key)) return false
        return progress(key) >= required(key)
    }

    /** Started = any progress on the current tier or any tier already claimed. */
    fun isStarted(key: String): Boolean = tier(key) > 1 || progress(key) > 0L

    /**
     * Adds [amountScaled] to the matched objectives and once to the category level. Tracks that are
     * already complete don't move (paused until claimed).
     */
    fun record(category: MilestoneCategory, tokens: Set<String>, amountScaled: Long): RecordResult {
        if (amountScaled <= 0L) return RecordResult(false, emptyList())
        val amount = amountScaled.coerceAtMost(MilestoneMath.MAX_ACTIVITY_SCALED)
        val matched = catalog.matching(category, tokens)
        if (matched.isEmpty()) return RecordResult(false, emptyList())
        val completed = ArrayList<String>(2)
        for (objective in matched) {
            if (add(objective.key, amount)) completed += objective.key
        }
        if (add(category.levelKey, amount)) completed += category.levelKey
        return RecordResult(true, completed)
    }

    /** True when this add completed the track. */
    private fun add(key: String, amount: Long): Boolean {
        val required = catalog.requiredScaled(key, tier(key)) ?: return false
        val track = tracks.getOrPut(key) { Track(1, 0L) }
        if (track.progress >= required) return false
        val next = if (amount >= required - track.progress) required else track.progress + amount
        track.progress = next
        dirty += key
        return next >= required
    }

    /** After a committed claim of [claimedTier]: next tier, progress 0. False when the book is elsewhere. */
    fun applyClaim(key: String, claimedTier: Int): Boolean {
        val track = tracks.getOrPut(key) { Track(1, 0L) }
        if (track.tier != claimedTier) return false
        track.tier = MilestoneMath.clampTier(claimedTier + 1)
        track.progress = 0L
        dirty -= key // the claim transaction already wrote this row
        return true
    }

    /** Dirty rows to save; clears the dirty set. */
    fun drainDirty(): List<Snapshot> {
        if (dirty.isEmpty()) return emptyList()
        val out = dirty.mapNotNull { key -> tracks[key]?.let { Snapshot(key, it.tier, it.progress) } }
        dirty.clear()
        return out
    }

    /** Put rows back after a failed save so the next flush retries them. */
    fun markDirty(keys: Collection<String>) {
        for (key in keys) if (key in tracks) dirty += key
    }

    val hasDirty: Boolean get() = dirty.isNotEmpty()

    /** Claimable tracks of [category] (category level first). */
    fun claimable(category: MilestoneCategory): List<String> {
        val def = catalog.category(category) ?: return emptyList()
        return (listOf(category.levelKey) + def.objectives.map { it.key }).filter { isClaimable(it) }
    }

    /**
     * The category screen order (issue #1420): completed milestones awaiting a claim, then in-progress
     * ones with the highest completion first, then unstarted ones (config order within each group).
     */
    fun sortedObjectives(category: MilestoneCategory): List<ObjectiveDef> {
        val def = catalog.category(category) ?: return emptyList()
        val indexed = def.objectives.withIndex()
        return indexed.sortedWith(
            compareBy<IndexedValue<ObjectiveDef>> { group(it.value.key) }
                .thenByDescending { fraction(it.value.key) }
                .thenBy { it.index }
        ).map { it.value }
    }

    private fun group(key: String): Int = when {
        isClaimable(key) -> 0
        isStarted(key) -> 1
        else -> 2
    }

    private fun fraction(key: String): Double {
        val required = required(key)
        if (required <= 0L || required == Long.MAX_VALUE) return 0.0
        return progress(key).toDouble() / required.toDouble()
    }
}
