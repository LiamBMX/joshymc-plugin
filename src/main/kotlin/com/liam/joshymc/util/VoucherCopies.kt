package com.liam.joshymc.util

import com.liam.joshymc.Joshymc
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * Issue #1529: the one way a stored item *template* (a crate reward snapshot, a kit item) that
 * is a single-use voucher becomes the item a player actually receives. Each voucher kind burns
 * its `voucher_uuid` in its own redemptions table, so every copy needs a fresh uuid; Credit,
 * Rank and Fly Vouchers also carry a signature over that uuid, so the copy is signed again by
 * the voucher's own manager (never here). A template that doesn't verify is refused rather
 * than signed.
 */
sealed interface VoucherCopy {
    /** Not a single-use voucher: hand out the template unchanged. */
    data object NotVoucher : VoucherCopy
    data class Fresh(val item: ItemStack) : VoucherCopy
    data class Refused(val reason: String) : VoucherCopy
}

/** Why [template] is a voucher that can't be copied, or null (also for non-vouchers). */
fun Joshymc.voucherTemplateProblem(template: ItemStack): String? = when {
    creditVoucherManager.isCreditVoucher(template) -> creditVoucherManager.templateProblem(template)
    rankVoucherManager.isRankVoucher(template) -> rankVoucherManager.templateProblem(template)
    flyVoucherManager.isFlyVoucher(template) -> flyVoucherManager.templateProblem(template)
    else -> null
}

/** A copy of [template] to hand out; see [VoucherCopy]. [template] itself is never changed. */
fun Joshymc.freshVoucherCopy(template: ItemStack): VoucherCopy {
    fun result(copy: ItemStack?): VoucherCopy =
        copy?.let { VoucherCopy.Fresh(it) } ?: VoucherCopy.Refused(voucherTemplateProblem(template) ?: "can't be copied")
    return when {
        creditVoucherManager.isCreditVoucher(template) -> result(creditVoucherManager.reissue(template))
        rankVoucherManager.isRankVoucher(template) -> result(rankVoucherManager.reissue(template))
        flyVoucherManager.isFlyVoucher(template) -> result(flyVoucherManager.reissue(template))
        // Chat Tag Vouchers are unsigned: only the single-use id has to be fresh.
        chatTagVoucherManager.isChatTagVoucher(template) -> VoucherCopy.Fresh(template.clone().apply {
            amount = 1
            editMeta { it.persistentDataContainer.set(chatTagVoucherManager.voucherUuidKey, PersistentDataType.STRING, UUID.randomUUID().toString()) }
        })
        else -> VoucherCopy.NotVoucher
    }
}
