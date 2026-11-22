package com.liam.joshymc.crate

import java.security.MessageDigest

/**
 * Issue #1541 (audit CR-P2-06): the `admin_logs` rows `/crateeditor` writes through
 * `AdminAudit.log`. Bukkit-free. One row per attempted change (never one per redraw or per
 * retry): the action names the operation, the details always start with `crate=<id>` and
 * `result=SUCCESS|REJECTED|FAILED`, then the reward (index, material, amount, weight, name,
 * custom item id, voucher flag, a short fingerprint of the stored snapshot), and `previous=` /
 * `new=` where a value changes. The actor UUID/name and the timestamp are `admin_logs` columns.
 *
 * Never logged: the Base64 snapshot itself, lore, PDC values, voucher ids or signatures. The
 * fingerprint is the first 16 hex digits of SHA-256 over the snapshot, enough to tell two
 * rewards apart and to match a row against crates.yml, not to rebuild the item.
 */
object CrateEditorAudit {

    enum class Operation(val action: String) {
        CREATE("CRATE_EDITOR_CREATE"),
        RENAME("CRATE_EDITOR_RENAME"),
        DELETE("CRATE_EDITOR_DELETE"),
        REWARD_ADD("CRATE_EDITOR_REWARD_ADD"),
        REWARD_REMOVE("CRATE_EDITOR_REWARD_REMOVE"),
        REWARD_WEIGHT("CRATE_EDITOR_REWARD_WEIGHT"),
        KEY_ITEM("CRATE_EDITOR_KEY_ITEM"),
        MODE("CRATE_EDITOR_MODE"),
        ANIMATION("CRATE_EDITOR_ANIMATION"),
        ANIMATION_GLASS("CRATE_EDITOR_ANIMATION_GLASS"),
        IDLE_PARTICLE("CRATE_EDITOR_IDLE_PARTICLE"),
        WIN_PARTICLE("CRATE_EDITOR_WIN_PARTICLE"),
        PARTICLE_COLOR("CRATE_EDITOR_PARTICLE_COLOR"),
        ;

        /** Changes what a crate can pay out or how likely each reward is. */
        val rewardAffecting: Boolean get() = this in REWARD_AFFECTING
    }

    private val REWARD_AFFECTING = setOf(Operation.CREATE, Operation.DELETE, Operation.REWARD_ADD,
        Operation.REWARD_REMOVE, Operation.REWARD_WEIGHT, Operation.KEY_ITEM, Operation.MODE)

    /** SUCCESS = saved; REJECTED = refused before anything changed; FAILED = the save itself failed. */
    enum class Result { SUCCESS, REJECTED, FAILED }

    /** Why an edit was refused (the `reason=` value). */
    enum class Reason(val id: String) {
        NO_PERMISSION("no_permission"),
        STALE("reward_changed"),
        CRATE_MISSING("crate_missing"),
        INVALID_WEIGHT("invalid_weight"),
        ENCHANT_PROBLEM("enchant_problem"),
        DUPLICATE_ID("duplicate_id"),
        NEEDS_THREE_REWARDS("needs_three_rewards"),
        INVALID_INPUT("invalid_input"),
        /** Issue #1601: the item is, or holds, a Lifesteal Heart item. */
        HEART_ITEM("heart_item"),
    }

    /** What the row says about one reward; never the item data itself. */
    data class RewardRef(
        val index: Int?,
        val material: String,
        val amount: Int,
        val weight: Int?,
        val name: String,
        val customItem: String? = null,
        val voucher: Boolean = false,
        val fingerprint: String? = null,
    )

    /** First 16 hex digits of SHA-256 over [snapshot], or null when there is none. */
    fun fingerprint(snapshot: String?): String? {
        if (snapshot.isNullOrEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * A value safe for the space-separated `key=value` details: control characters and
     * `&`/`§` colour codes removed, whitespace and `=` turned into `_`, at most [max] characters.
     */
    fun clean(value: String?, max: Int = 48): String {
        if (value == null) return "-"
        val stripped = value.replace(Regex("[&§][0-9a-fk-orA-FK-OR#]"), "")
            .replace(Regex("[\\s=]+"), "_")
            .filter { !it.isISOControl() }
            .trim('_')
        if (stripped.isEmpty()) return "-"
        return if (stripped.length > max) stripped.take(max) + "~" else stripped
    }

    /** The `admin_logs.details` text for one attempted edit. */
    fun details(
        crateId: String,
        result: Result,
        reason: Reason? = null,
        reward: RewardRef? = null,
        previous: String? = null,
        new: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val parts = mutableListOf("crate=${clean(crateId, 64)}", "result=${result.name}")
        reason?.let { parts += "reason=${it.id}" }
        reward?.let { r ->
            r.index?.let { parts += "reward_index=$it" }
            parts += "material=${clean(r.material, 64)}"
            parts += "amount=${r.amount}"
            r.weight?.let { parts += "weight=$it" }
            parts += "name=${clean(r.name)}"
            r.customItem?.let { parts += "custom_item=${clean(it, 64)}" }
            if (r.voucher) parts += "voucher=true"
            r.fingerprint?.let { parts += "fingerprint=$it" }
        }
        previous?.let { parts += "previous=${clean(it)}" }
        new?.let { parts += "new=${clean(it)}" }
        for ((k, v) in extra) parts += "${clean(k, 32)}=${clean(v)}"
        return parts.joinToString(" ")
    }
}
