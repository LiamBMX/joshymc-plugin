package com.liam.joshymc.staff

/**
 * Signed Credit and Rank Vouchers (issue #1383). Bukkit-free.
 *
 * Both voucher kinds already carry a random per-item UUID (single use), but nothing proved the
 * server made them, so a modded Creative client could make a new one with any value. New
 * vouchers carry an HMAC-SHA256 (same scheme as Fly Vouchers, `SurvivalFlight.sign`) over
 * [payload], keyed by the per-server `premium_voucher_secret`.
 *
 * Vouchers made before this change have no signature. They keep working while
 * `config.yml > vouchers.accept-unsigned-legacy` is true (the default), and every such
 * redemption is logged and alerted so the Owner can see them run out and then turn it off.
 * A voucher that *has* a signature that doesn't match is always refused.
 */
object PremiumVoucherRules {
    enum class Kind(val id: String) { CREDIT("credit"), RANK("rank") }

    fun payload(kind: Kind, version: Int, voucherUuid: String, value: String): String =
        "${kind.id}|$version|$voucherUuid|$value"

    enum class Verdict { SIGNED, FORGED, LEGACY_ACCEPTED, LEGACY_REFUSED }

    /** [signature] = the item's signature (null = unsigned legacy); [valid] = it verified. */
    fun verdict(signature: String?, valid: Boolean, acceptUnsigned: Boolean): Verdict = when {
        signature == null -> if (acceptUnsigned) Verdict.LEGACY_ACCEPTED else Verdict.LEGACY_REFUSED
        valid -> Verdict.SIGNED
        else -> Verdict.FORGED
    }

    fun redeemable(verdict: Verdict): Boolean = verdict == Verdict.SIGNED || verdict == Verdict.LEGACY_ACCEPTED
}
