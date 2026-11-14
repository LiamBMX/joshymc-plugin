package com.liam.joshymc.staff

import com.liam.joshymc.Joshymc
import com.liam.joshymc.flight.SurvivalFlight
import com.liam.joshymc.manager.AntiDupeManager
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import java.security.SecureRandom

/**
 * Signs and checks Credit and Rank Vouchers (issue #1383, rules in [PremiumVoucherRules]).
 * The secret is random per server and lives only in `premium_voucher_secret`; it is never
 * logged or put on an item.
 */
class PremiumVoucherSigner(private val plugin: Joshymc) {

    val signatureKey = NamespacedKey(plugin, "voucher_signature")
    private var secret: ByteArray? = null

    private fun secret(): ByteArray {
        secret?.let { return it }
        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS premium_voucher_secret (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                secret TEXT NOT NULL
            )
            """.trimIndent()
        )
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        plugin.databaseManager.execute(
            "INSERT OR IGNORE INTO premium_voucher_secret (id, secret) VALUES (1, ?)",
            fresh.joinToString("") { "%02x".format(it) }
        )
        val hex = plugin.databaseManager.queryFirst("SELECT secret FROM premium_voucher_secret WHERE id = 1") { it.getString("secret") }
            ?: error("premium_voucher_secret is empty")
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { secret = it }
    }

    fun start() {
        secret()
    }

    fun sign(meta: ItemMeta, kind: PremiumVoucherRules.Kind, version: Int, voucherUuid: String, value: String) {
        meta.persistentDataContainer.set(
            signatureKey, PersistentDataType.STRING,
            SurvivalFlight.sign(secret(), PremiumVoucherRules.payload(kind, version, voucherUuid, value))
        )
    }

    /**
     * Issue #1529: the verdict for a voucher *template* (no player; nothing logged or alerted).
     * Used before a template is copied with a fresh uuid, see [PremiumVoucherRules.reissue].
     */
    fun templateVerdict(meta: ItemMeta, kind: PremiumVoucherRules.Kind, version: Int, voucherUuid: String, value: String): PremiumVoucherRules.Verdict {
        val signature = meta.persistentDataContainer.get(signatureKey, PersistentDataType.STRING)
        val valid = signature != null &&
            SurvivalFlight.verify(secret(), PremiumVoucherRules.payload(kind, version, voucherUuid, value), signature)
        return PremiumVoucherRules.verdict(signature, valid, acceptUnsigned)
    }

    /**
     * Issue #1529: gives [meta] (a copy of a voucher template) the fresh [voucherUuid] and the
     * signature [reissue] calls for: signed again for RESIGN, no signature for UNSIGNED. A
     * REFUSE template is never handed out, so it is rejected here too.
     */
    fun applyReissue(
        meta: ItemMeta, reissue: PremiumVoucherRules.Reissue, uuidKey: NamespacedKey,
        kind: PremiumVoucherRules.Kind, version: Int, voucherUuid: String, value: String
    ) {
        require(reissue != PremiumVoucherRules.Reissue.REFUSE) { "a refused voucher template is never reissued" }
        meta.persistentDataContainer.set(uuidKey, PersistentDataType.STRING, voucherUuid)
        if (reissue == PremiumVoucherRules.Reissue.RESIGN) sign(meta, kind, version, voucherUuid, value)
        else meta.persistentDataContainer.remove(signatureKey)
    }

    private val acceptUnsigned: Boolean get() = plugin.config.getBoolean("vouchers.accept-unsigned-legacy", true)

    /**
     * Checks a voucher before redemption. Returns true when it may be redeemed. Forged or
     * (when legacy acceptance is off) unsigned vouchers are refused and recorded as anti-dupe
     * alerts; accepted legacy vouchers are logged.
     */
    fun check(player: Player, meta: ItemMeta, kind: PremiumVoucherRules.Kind, version: Int, voucherUuid: String, value: String, label: String): Boolean {
        val signature = meta.persistentDataContainer.get(signatureKey, PersistentDataType.STRING)
        val valid = signature != null &&
            SurvivalFlight.verify(secret(), PremiumVoucherRules.payload(kind, version, voucherUuid, value), signature)
        val verdict = PremiumVoucherRules.verdict(signature, valid, acceptUnsigned)
        when (verdict) {
            PremiumVoucherRules.Verdict.SIGNED -> {}
            PremiumVoucherRules.Verdict.LEGACY_ACCEPTED ->
                plugin.logger.info("[Vouchers] ${player.name} (${player.uniqueId}) redeemed an unsigned legacy $label ($voucherUuid).")
            PremiumVoucherRules.Verdict.FORGED, PremiumVoucherRules.Verdict.LEGACY_REFUSED -> {
                plugin.logger.warning("[Vouchers] Refused ${verdict.name.lowercase()} $label from ${player.name} (${player.uniqueId}), id $voucherUuid.")
                try {
                    plugin.antiDupeManager.record(
                        player,
                        if (verdict == PremiumVoucherRules.Verdict.FORGED) "Forged Voucher Signature" else "Unsigned Voucher Refused",
                        AntiDupeManager.RiskLevel.CRITICAL,
                        item = label,
                        source = label,
                        transactionId = voucherUuid
                    )
                } catch (e: Exception) {
                    plugin.logger.warning("[Vouchers] Could not record the anti-dupe alert: ${e.message}")
                }
            }
        }
        return PremiumVoucherRules.redeemable(verdict)
    }
}
