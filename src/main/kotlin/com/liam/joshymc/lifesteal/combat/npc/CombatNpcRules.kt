package com.liam.joshymc.lifesteal.combat.npc

/**
 * The Bukkit-free rules of the Lifesteal Combat NPC (issue #1402). Everything here is decided from
 * plain values (states, epoch millis, ticks), so the checks in `scripts/LifestealCombatNpcCheck.kt`
 * exercise exactly what the server runs.
 *
 * A session moves only forward:
 *
 * ```
 * ACTIVE ──death──────────> DEAD ──(drops spawned, then owner joins)──> COMPLETE
 *    │──expiry/removed────> SURVIVED ──owner joins──> RECOVERING ──> COMPLETE
 *    └──owner reconnects──────────────────────────> RECOVERING ──> COMPLETE
 * ```
 *
 * Every arrow is a conditional UPDATE on the expected state, so of two racing transitions (a death
 * and a reconnect in the same tick, a duplicate death event, a restart) exactly one wins.
 */
object CombatNpcRules {

    enum class State {
        /** The NPC stands (or should, until its deadline) and holds the items. */
        ACTIVE,
        /** It lived to its deadline (or was removed without dying): the saved state waits for its owner. */
        SURVIVED,
        /** It was killed; the death is committed. The items drop once ([CombatNpcStore.Session.dropsDone]). */
        DEAD,
        /** The saved state is being handed to its owner (applying it again is harmless: a set, not an add). */
        RECOVERING,
        /** Settled. Nothing of it is used again. */
        COMPLETE,
    }

    /** How a session that stayed alive ended. */
    enum class Outcome { SURVIVED, RECONNECTED, REMOVED, DEAD }

    /** Main hand slot range and the fixed `PlayerInventory.contents` layout (36 storage, 4 armor, off hand). */
    const val INVENTORY_SIZE = 41
    const val FIRST_ARMOR_SLOT = 36
    const val OFF_HAND_SLOT = 40

    /** Armor slot indexes in `PlayerInventory.contents` order: boots, leggings, chestplate, helmet. */
    val ARMOR_SLOTS = listOf(36, 37, 38, 39)

    /** A restarted NPC needs at least this much of its lifetime left, else it simply survived. */
    const val MIN_RESPAWN_MILLIS = 1_000L

    const val TICK_MILLIS = 50L

    /** Effects with this duration never run out (vanilla's infinite duration). */
    const val INFINITE_DURATION = -1

    fun expiresAt(disconnectedAt: Long, lifetimeSeconds: Int): Long = disconnectedAt + lifetimeSeconds * 1_000L

    // ── Startup ─────────────────────────────────────────────

    enum class StartupAction {
        /** Spawn the NPC again for the rest of its original lifetime. */
        RESPAWN,
        /** Its deadline passed while the server was down (or its world is gone): it survived. */
        SURVIVE,
        /** A committed death whose drops were never confirmed: drop them once. */
        DROP,
        /** Nothing to do until the owner joins. */
        KEEP,
    }

    /**
     * What startup does with an unfinished session. Downtime never extends a deadline and never
     * invents damage: an ACTIVE session past its deadline survived with its last saved state.
     */
    fun startupAction(state: State, expiresAt: Long, dropsDone: Boolean, worldLoaded: Boolean, now: Long): StartupAction = when (state) {
        State.ACTIVE -> if (worldLoaded && expiresAt - now >= MIN_RESPAWN_MILLIS) StartupAction.RESPAWN else StartupAction.SURVIVE
        State.DEAD -> if (dropsDone) StartupAction.KEEP else StartupAction.DROP
        State.SURVIVED, State.RECOVERING, State.COMPLETE -> StartupAction.KEEP
    }

    // ── Join ────────────────────────────────────────────────

    enum class JoinAction {
        /** No open session: nothing to do. */
        NONE,
        /** The NPC is still standing: end it (RECONNECTED) and hand its state over. */
        TAKE_OVER,
        /** It survived: hand the saved state over. */
        RESTORE,
        /** A hand-over was interrupted (crash): apply the same saved state again. */
        RESTORE_AGAIN,
        /** It was killed: the normal death recovery (empty inventory, respawn point, full health). */
        DEATH,
        /** It was killed but the drops weren't confirmed: drop them, then the death recovery. */
        DROP_THEN_DEATH,
    }

    fun joinAction(state: State?, dropsDone: Boolean): JoinAction = when (state) {
        null, State.COMPLETE -> JoinAction.NONE
        State.ACTIVE -> JoinAction.TAKE_OVER
        State.SURVIVED -> JoinAction.RESTORE
        State.RECOVERING -> JoinAction.RESTORE_AGAIN
        State.DEAD -> if (dropsDone) JoinAction.DEATH else JoinAction.DROP_THEN_DEATH
    }

    // ── Effects and timers ──────────────────────────────────

    /**
     * Ticks left of an effect that had [durationTicks] left at [measuredAt], at [now]. Real time is
     * used so a restart or the owner being offline never extends it; null once it ran out.
     */
    fun remainingTicks(durationTicks: Int, measuredAt: Long, now: Long): Int? {
        if (durationTicks == INFINITE_DURATION) return INFINITE_DURATION
        if (durationTicks <= 0) return null
        val elapsed = ((now - measuredAt).coerceAtLeast(0) / TICK_MILLIS)
        val left = durationTicks - elapsed
        return if (left > 0) left.toInt() else null
    }

    /** The combat tag to give back on reconnect: the original expiry if it hasn't passed, never a fresh one. */
    fun remainingTag(tagExpiresAt: Long?, now: Long): Long? = tagExpiresAt?.takeIf { it > now }

    /** Vanilla's XP drop on death: 7 per level, at most 100 (the rest is lost). */
    fun deathXpDrop(level: Int): Int = (level.coerceAtLeast(0) * 7).coerceAtMost(100)

    /** Health a restored player gets: the NPC's, within (0, max]; never full health as a bonus. */
    fun restoredHealth(saved: Double, max: Double): Double {
        if (max <= 0.0 || saved.isNaN()) return max.coerceAtLeast(1.0)
        return saved.coerceIn(0.5, max)
    }

    // ── Encoding ────────────────────────────────────────────

    /** One saved potion effect; [type] is the effect's namespaced key. */
    data class Effect(
        val type: String,
        val amplifier: Int,
        val durationTicks: Int,
        val ambient: Boolean,
        val particles: Boolean,
        val icon: Boolean,
    )

    fun encodeEffects(effects: List<Effect>): String = effects.joinToString(";") {
        listOf(it.type, it.amplifier, it.durationTicks, flag(it.ambient), flag(it.particles), flag(it.icon)).joinToString("|")
    }

    /** Malformed entries are skipped (an effect is never invented). */
    fun decodeEffects(raw: String?): List<Effect> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { entry ->
            val p = entry.split('|')
            if (p.size != 6 || p[0].isBlank()) return@mapNotNull null
            val amplifier = p[1].toIntOrNull() ?: return@mapNotNull null
            val duration = p[2].toIntOrNull() ?: return@mapNotNull null
            Effect(p[0], amplifier, duration, p[3] == "1", p[4] == "1", p[5] == "1")
        }
    }

    /** Effects as they stand at [now] (ran-out ones dropped), given they were measured at [measuredAt]. */
    fun effectsAt(effects: List<Effect>, measuredAt: Long, now: Long): List<Effect> = effects.mapNotNull { effect ->
        remainingTicks(effect.durationTicks, measuredAt, now)?.let { effect.copy(durationTicks = it) }
    }

    /**
     * The inventory as [INVENTORY_SIZE] Base64 item blobs, "" for an empty slot. Base64 never
     * contains ',', so a plain join is unambiguous.
     */
    fun encodeInventory(slots: List<String?>): String {
        require(slots.size == INVENTORY_SIZE) { "an inventory snapshot has $INVENTORY_SIZE slots, not ${slots.size}" }
        return slots.joinToString(",") { it ?: "" }
    }

    /** Null when the stored text isn't a complete snapshot (the session is then never restored or dropped blindly). */
    fun decodeInventory(raw: String?): List<String?>? {
        if (raw == null) return null
        val parts = raw.split(',')
        if (parts.size != INVENTORY_SIZE) return null
        return parts.map { it.ifEmpty { null } }
    }

    /**
     * The snapshot after the NPC's fight: armor and off hand become what the NPC wears now (same
     * item, legitimate durability loss, or gone when it broke). A slot whose worn item is a different
     * item than the snapshot's keeps the snapshot (an item can't be swapped in through the NPC).
     * [sameItem] compares two blobs ignoring durability; [worn] is null for an empty slot.
     */
    fun withWornEquipment(
        slots: List<String?>,
        worn: Map<Int, String?>,
        sameItem: (String, String) -> Boolean,
    ): List<String?> {
        val result = slots.toMutableList()
        for ((slot, now) in worn) {
            if (slot !in ARMOR_SLOTS && slot != OFF_HAND_SLOT) continue
            val before = slots.getOrNull(slot) ?: continue // Nothing was there: nothing can appear.
            result[slot] = when {
                now == null -> null // Broke while the NPC wore it.
                sameItem(before, now) -> now
                else -> before
            }
        }
        return result
    }

    private fun flag(b: Boolean) = if (b) "1" else "0"
}
