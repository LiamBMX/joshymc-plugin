package com.liam.joshymc.punish

import com.liam.joshymc.gamemode.ServerGamemode

/** Tunables from punish-rules.yml > settings. */
data class PunishSettings(
    val minorWindowMs: Long = 90 * PunishDurations.DAY,
    val seriousWindowMs: Long = 365 * PunishDurations.DAY,
    val confirmationExpiryMs: Long = 2 * PunishDurations.MINUTE,
    /** Longest network-wide mute/ban an Admin may issue without Manager approval. */
    val emergencyNetworkMaxMs: Long = 24 * PunishDurations.HOUR,
    /** Senior review deadline for those emergency suspensions. */
    val emergencyReviewMs: Long = 6 * PunishDurations.HOUR,
    val requireEvidenceForBans: Boolean = true,
) {
    fun windowMs(window: EscalationWindow): Long = when (window) {
        EscalationWindow.MINOR -> minorWindowMs
        EscalationWindow.SERIOUS -> seriousWindowMs
    }
}

/** The ban an EXTEND_BAN tier would lengthen. */
data class ActiveBanInfo(val permanent: Boolean, val originalDurationMs: Long?)

/** Everything the planner needs, gathered by the caller. */
data class PlanRequest(
    val rule: PunishRule,
    /** The staff member's current level, or null without any `joshymc.punish.<level>`. */
    val level: StaffLevel?,
    /** Holds `joshymc.punish.network` (Manager / Owner). */
    val networkApprover: Boolean,
    /** Gamemode the violation happened in. */
    val gamemode: ServerGamemode,
    /** Network-wide instead of only [gamemode]. */
    val network: Boolean,
    /** Qualifying earlier offenses of this rule inside its escalation window. */
    val previousCount: Int,
    /** Sr Admin tier override (1..5), or null. */
    val overrideTier: Int? = null,
    /** Use the tier's documented alternative (attempted-exploit tier 1). */
    val alternate: Boolean = false,
    val targetOnline: Boolean,
    val activeBan: ActiveBanInfo?,
)

data class PunishPlan(
    val rule: PunishRule,
    val level: StaffLevel,
    val gamemode: ServerGamemode,
    val network: Boolean,
    val previousCount: Int,
    val tierNumber: Int,
    val tier: PunishTier,
    val alternate: Boolean,
    val overridden: Boolean,
    /** EXTEND_BAN only: how much is added (null when the ban is permanent, so nothing is). */
    val extensionMs: Long?,
    /** Evidence / a written justification must be attached before confirming. */
    val needsEvidence: Boolean,
    /** An Admin's emergency network-wide suspension: Sr Admin review is due. */
    val needsReview: Boolean,
) {
    val offense: Int get() = previousCount + 1

    /** "Survival", "Lifesteal" or "Network". */
    val scopeLabel: String get() = if (network) "Network" else gamemode.displayName

    /** Stored scope value: a gamemode id, or "network". */
    val scopeId: String get() = if (network) NETWORK_SCOPE else gamemode.id

    /** Readable punishment, e.g. "14-Day Ban" or "+7-Day Ban Extension". */
    fun describe(): String = tier.describe()

    /**
     * Everything that decides what gets applied. A confirmation stores it; at confirm time the
     * plan is rebuilt and must produce the same fingerprint, otherwise the confirmation is stale.
     */
    fun fingerprint(): String =
        listOf(rule.id, gamemode.id, scopeId, previousCount, tierNumber, tier.code(), alternate, overridden, extensionMs ?: "-")
            .joinToString("|")

    companion object {
        const val NETWORK_SCOPE = "network"
    }
}

sealed interface PlanResult {
    data class Allowed(val plan: PunishPlan) : PlanResult
    data class Denied(val kind: Kind, val reason: String) : PlanResult

    enum class Kind {
        INFORMATIONAL, NO_LEVEL, RULE_LEVEL, GAMEMODE, OVERRIDE, ALTERNATE,
        TARGET_OFFLINE, NO_ACTIVE_BAN, TIER_AUTHORITY, NETWORK_APPROVAL,
    }
}

/**
 * Decides what `/punish <player> <rule>` does (issue #1298). Bukkit-free. Never substitutes a
 * weaker punishment: when the calculated tier is above the staff member's authority the result
 * is a denial naming the level needed.
 */
object PunishPlanner {

    private val NETWORK_GATED = setOf(PunishAction.MUTE, PunishAction.TEMPBAN, PunishAction.PERMBAN, PunishAction.EXTEND_BAN)

    fun plan(req: PlanRequest, settings: PunishSettings): PlanResult {
        val rule = req.rule
        val tiers = rule.tiers
        if (tiers == null || rule.minLevel == null) {
            return deny(PlanResult.Kind.INFORMATIONAL, "${rule.keyword} is informational only and cannot be punished.")
        }
        val level = req.level
            ?: return deny(PlanResult.Kind.NO_LEVEL, "You don't have a staff punishment level.")
        if (!level.atLeast(rule.minLevel)) {
            return deny(PlanResult.Kind.RULE_LEVEL, "${rule.displayName} needs ${rule.minLevel.displayName} or higher.")
        }
        if (req.gamemode !in rule.gamemodes) {
            return deny(PlanResult.Kind.GAMEMODE, "${rule.displayName} doesn't apply on ${req.gamemode.displayName}.")
        }

        val overridden = req.overrideTier != null
        if (overridden) {
            if (!level.isSenior) return deny(PlanResult.Kind.OVERRIDE, "Only Sr Admins may override punishment tiers.")
            if (req.overrideTier !in 1..tiers.size) return deny(PlanResult.Kind.OVERRIDE, "Tier must be 1-${tiers.size}.")
        }
        val tierNumber = req.overrideTier ?: (req.previousCount + 1).coerceIn(1, tiers.size)
        val slot = tiers[tierNumber - 1]
        var tier = if (req.alternate) {
            slot.alternate ?: return deny(PlanResult.Kind.ALTERNATE, "Tier $tierNumber of ${rule.displayName} has no alternative punishment.")
        } else slot.primary

        var extensionMs: Long? = null
        if (tier.action == PunishAction.EXTEND_BAN) {
            val ban = req.activeBan
            if (ban == null) {
                // Nothing to extend: a fixed extension becomes a ban of that length (never shorter
                // than what the tier asks for); "extend the existing ban" has nothing to work on.
                tier = PunishTier(
                    PunishAction.TEMPBAN,
                    tier.durationMs ?: return deny(PlanResult.Kind.NO_ACTIVE_BAN, "There's no active ban to extend. Punish the banned account."),
                )
            } else if (!ban.permanent) {
                extensionMs = tier.durationMs ?: ban.originalDurationMs
                    ?: return deny(PlanResult.Kind.NO_ACTIVE_BAN, "The active ban has no recorded length to extend by.")
            }
        }

        if (tier.action == PunishAction.KICK && !req.targetOnline) {
            return deny(PlanResult.Kind.TARGET_OFFLINE, "Tier $tierNumber is a kick, and the player isn't online.")
        }

        if (!level.allows(tier, extensionMs)) {
            val needed = StaffLevel.entries.firstOrNull { it.atLeast(rule.minLevel) && it.allows(tier, extensionMs) }
            val escalate = needed?.let { " Escalate to a ${it.displayName} or higher." } ?: ""
            return deny(PlanResult.Kind.TIER_AUTHORITY, "Offense ${req.previousCount + 1} calls for ${tier.describe()}, which is above your authority.$escalate")
        }

        var needsReview = false
        if (req.network && tier.action in NETWORK_GATED && !req.networkApprover) {
            val emergency = level.atLeast(StaffLevel.ADMIN) &&
                (tier.action == PunishAction.TEMPBAN || tier.action == PunishAction.MUTE) &&
                tier.durationMs!! <= settings.emergencyNetworkMaxMs
            if (!emergency) {
                return deny(PlanResult.Kind.NETWORK_APPROVAL, "Network-wide ${tier.describe()} needs Manager approval. Admins may only issue emergency network suspensions up to ${PunishDurations.label(settings.emergencyNetworkMaxMs)}.")
            }
            needsReview = true
        }

        val needsEvidence = (settings.requireEvidenceForBans && tier.action.isBan) || req.network || overridden || req.alternate

        return PlanResult.Allowed(
            PunishPlan(
                rule = rule,
                level = level,
                gamemode = req.gamemode,
                network = req.network,
                previousCount = req.previousCount,
                tierNumber = tierNumber,
                tier = tier,
                alternate = req.alternate,
                overridden = overridden,
                extensionMs = extensionMs,
                needsEvidence = needsEvidence,
                needsReview = needsReview,
            )
        )
    }

    private fun deny(kind: PlanResult.Kind, reason: String) = PlanResult.Denied(kind, reason)
}

/**
 * Optional flags after the rule keyword, then free text evidence:
 * `/punish <player> <rule> [gm:<survival|lifesteal>] [scope:network] [tier:<1-5>] [alt] [evidence...]`.
 * Only leading tokens are read as flags, so evidence like "ticket:123" is left alone.
 */
data class PunishFlags(
    val gamemode: ServerGamemode? = null,
    val network: Boolean = false,
    val tier: Int? = null,
    val alternate: Boolean = false,
    val evidence: String? = null,
) {
    companion object {
        sealed interface Parsed {
            data class Ok(val flags: PunishFlags) : Parsed
            data class Error(val message: String) : Parsed
        }

        val FLAG_SUGGESTIONS = listOf("gm:survival", "gm:lifesteal", "scope:network", "tier:", "alt")

        fun parse(tokens: List<String>): Parsed {
            var flags = PunishFlags()
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index]
                val lower = token.lowercase()
                val key = lower.substringBefore(':', "")
                val value = lower.substringAfter(':', "")
                when {
                    lower == "alt" || lower == "alternate" -> flags = flags.copy(alternate = true)
                    key == "gm" || key == "gamemode" ->
                        flags = flags.copy(gamemode = ServerGamemode.fromId(value) ?: return Parsed.Error("Unknown gamemode '$value' (survival or lifesteal)."))
                    key == "scope" -> flags = when (value) {
                        "network" -> flags.copy(network = true)
                        else -> flags.copy(gamemode = ServerGamemode.fromId(value) ?: return Parsed.Error("Unknown scope '$value' (network, survival or lifesteal)."))
                    }
                    key == "tier" -> flags = flags.copy(tier = value.toIntOrNull() ?: return Parsed.Error("Tier must be a number from 1 to 5."))
                    else -> break
                }
                index++
            }
            val evidence = tokens.drop(index).joinToString(" ").trim().takeIf { it.isNotEmpty() }
            return Parsed.Ok(flags.copy(evidence = evidence))
        }
    }
}
