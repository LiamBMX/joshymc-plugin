package com.liam.joshymc.lifesteal.hearts

import com.liam.joshymc.lifesteal.ranked.RankedIntegrity
import java.util.UUID

/**
 * Issue #1537 (audit #1532, G-1/G-2/F-1), Bukkit-free so `scripts/LifestealHeartCheck.kt` can test it.
 *
 * - [Operation]: every `/lifesteal hearts` action that changes hearts or makes Heart items, with
 *   the `admin_logs` action it is mirrored as (AdminAudit) and the ranked [RankedIntegrity.StaffAction]
 *   that guards it. `lifesteal_heart_audit` stays the detailed record; `admin_logs` gets one row per
 *   committed transaction (never per retry: a refused or rolled-back command writes no success row).
 * - The staff checks run in a fixed order before anything is written (`LifestealHeartsCommand.guard`):
 *   the existing staff protection (`StaffRankManager.protectionProblem`: self, equal and higher
 *   staff refused, console allowed), then the ranked hold (`refusesStaffAction`).
 * - [saveOutcome]: whether the player-data save right after a Heart item was issued can be
 *   trusted, so a lost item can be found and recovered by staff (never automatically).
 */
object HeartAdminRules {

    enum class Operation(val id: String, val auditAction: String, val rankedAction: RankedIntegrity.StaffAction) {
        SET("set", "HEART_SET", RankedIntegrity.StaffAction.HEARTS),
        ADD("add", "HEART_ADD", RankedIntegrity.StaffAction.HEARTS),
        REMOVE("remove", "HEART_REMOVE", RankedIntegrity.StaffAction.HEARTS),
        GIVE("give", "HEART_GIVE", RankedIntegrity.StaffAction.HEART_ITEMS),
        VOID("void", "HEART_VOID", RankedIntegrity.StaffAction.HEARTS),
        REFUND("refund", "HEART_REFUND", RankedIntegrity.StaffAction.HEARTS);

        companion object {
            fun of(op: HeartRules.AdminOp): Operation = when (op) {
                HeartRules.AdminOp.SET -> SET
                HeartRules.AdminOp.ADD -> ADD
                HeartRules.AdminOp.REMOVE -> REMOVE
            }
        }
    }

    /** `admin_logs` action for a heart command refused by staff protection (ranked refusals log `RANKED_STAFF_ACTION_BLOCKED`). */
    const val REFUSED_ACTION = "HEART_ADMIN_REFUSED"

    /** `admin_logs` action (system actor) for a Heart item whose player-data save failed right after it was issued. */
    const val SAVE_FAILED_ACTION = "HEART_ITEM_SAVE_FAILED"

    /**
     * The `admin_logs` details of a committed heart change: operation, bounded numbers, serial(s)
     * and the heart transaction id. Never anything about connections.
     */
    fun details(
        operation: Operation,
        transactionId: String?,
        amount: Int? = null,
        before: Int? = null,
        after: Int? = null,
        clamped: Boolean = false,
        serial: String? = null,
        itemCount: Int? = null,
        result: String = "OK",
    ): String = listOfNotNull(
        "op=${operation.id}",
        amount?.let { "amount=${it.coerceIn(0, HeartRules.MAX_ADMIN_AMOUNT)}" },
        before?.let { "before=$it" },
        after?.let { "after=$it" },
        "clamped=true".takeIf { clamped },
        itemCount?.let { "items=${it.coerceIn(0, HeartRules.MAX_STACKABLE_ITEM_GRANT)}" },
        serial?.let { HeartRules.parseSerial(it) }?.let { "serial=$it" },
        "result=$result",
        transactionId?.let { "tx=$it" },
    ).joinToString(" ")

    /** Details of a refused heart command: the operation and the refusal kind only. */
    fun refusedDetails(operation: Operation, reason: String): String = "op=${operation.id} result=REFUSED reason=${reasonCode(reason)}"

    /** Short code for a refusal message (the messages are StaffProtection's, so match on them). */
    fun reasonCode(message: String): String = when {
        message.contains("yourself", ignoreCase = true) -> "SELF"
        message.contains("above you", ignoreCase = true) -> "HIGHER_RANK"
        message.contains("own rank", ignoreCase = true) -> "EQUAL_RANK"
        message.contains("staff member", ignoreCase = true) -> "STAFF_TARGET"
        else -> "OTHER"
    }

    // ── Player-data save after a Heart item was issued (F-1) ──

    enum class SaveOutcome {
        /** `saveData` returned and the data file was written. */
        SAVED,
        /** `saveData` threw, or the data file is visibly older than the save: the item may be lost on a crash. */
        FAILED,
        /** `saveData` returned but the data file couldn't be checked (not found / unknown layout): no alert. */
        UNCONFIRMED,
    }

    /** File timestamps are coarse on some file systems; a file this much older than the save start counts as not written. */
    const val SAVE_TIMESTAMP_SLACK_MILLIS = 2_000L

    /**
     * [threw] = `saveData` threw on every attempt; [fileModifiedAt] = the player data file's
     * modification time afterwards (null = not found); [startedAt] = just before the save.
     */
    fun saveOutcome(threw: Boolean, fileModifiedAt: Long?, startedAt: Long): SaveOutcome = when {
        threw -> SaveOutcome.FAILED
        fileModifiedAt == null || fileModifiedAt <= 0 -> SaveOutcome.UNCONFIRMED
        fileModifiedAt < startedAt - SAVE_TIMESTAMP_SLACK_MILLIS -> SaveOutcome.FAILED
        else -> SaveOutcome.SAVED
    }

    /**
     * The console/admin_logs line for a failed save. Recovery is manual and never automatic: staff
     * check whether the player still has the item (`/lifesteal hearts items`, `recent`), and only
     * when it is certainly gone void it with refund. An ISSUED serial alone proves nothing.
     */
    fun saveFailedDetails(player: UUID, serials: List<String>, transactionId: String, source: HeartStore.ItemSource): String =
        listOf(
            "player_uuid=$player",
            "source=${source.name}",
            "serials=${serials.mapNotNull { HeartRules.parseSerial(it) }.take(HeartRules.MAX_ADMIN_ITEM_GRANT).joinToString(",")}",
            "tx=$transactionId",
            "recovery=manual",
        ).joinToString(" ")
}
