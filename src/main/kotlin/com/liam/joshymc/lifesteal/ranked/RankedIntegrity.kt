package com.liam.joshymc.lifesteal.ranked

import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility

/**
 * Issue #1498 (audit findings F-2, F-4, F-5), Bukkit-free: the match-integrity policy of Ranked
 * Team Wars. It reuses the one competitive-state rule ([CompetitiveEligibility.problems], issue
 * #1487) and the one ranked command allowlist ([RankedMatchRules.commandAllowed]); it adds no
 * second definition of "ineligible" or of "allowed command".
 *
 * **Entry.** Every way into a match (queue registration, challenge, acceptance, creation) already
 * goes through [RankedMatchRules.participantProblem]; [eligibilityProblem] is its restricted-state
 * part, and the manager checks it again after preparation, every countdown second and right before
 * the fight starts.
 *
 * **During a match.** Restricted states are blocked before they happen (game mode changes, `/god`,
 * `/vanish`, Mod Mode, flight, staff commands aimed at a held player). Anything that still gets
 * through (another plugin, console) is found by the manager's once-a-second check and resolved by
 * [resolve]: before the fight the match is cancelled unrated (nothing was at stake, the same as a
 * disconnect before the fight); in the fight the player is out as a forfeit
 * ([DefeatReason.INELIGIBLE]) and their side is marked ineligible for this match.
 *
 * **Result.** [ratingBlocked]: a win by a side marked ineligible is recorded but never rated, so
 * using an ability can't win Elo. A loss by such a side stays rated, so switching state can't be
 * used to escape a loss. Heart decisions already committed are never rolled back.
 *
 * **Staff.** Staff never change a held player directly ([staffActionRefusal]); the emergency path
 * is the audited `/lifesteal admin ranked cancel|remove`, which a player of that match (or a
 * member of one of its teams) can't use on it unless it is a staff TEST match ([interventionRefusal]).
 */
object RankedIntegrity {

    // ── Entry ───────────────────────────────────────────────

    /**
     * Why a player with these competitive [reasons] can't play ranked (null = they can). Game mode
     * is reported here too, so Creative/Spectator read the same as at reward settlement.
     */
    fun eligibilityProblem(reasons: Set<CompetitiveEligibility.Reason>): String? {
        if (reasons.isEmpty()) return null
        val first = reasons.minBy { it.ordinal }
        return when (first) {
            CompetitiveEligibility.Reason.CREATIVE -> "is in Creative mode (switch to Survival to play ranked)"
            CompetitiveEligibility.Reason.SPECTATOR -> "is in Spectator mode (switch to Survival to play ranked)"
            CompetitiveEligibility.Reason.GOD_MODE -> "has God Mode on (turn off /god to play ranked)"
            CompetitiveEligibility.Reason.VANISHED -> "is vanished (turn off /vanish to play ranked)"
            CompetitiveEligibility.Reason.MOD_MODE -> "is in Mod Mode"
            CompetitiveEligibility.Reason.UNSAFE_ENCHANT -> "is wearing gear with enchantments above vanilla limits"
        }
    }

    // ── During a match ──────────────────────────────────────

    enum class Resolution {
        /** Not held by a running match (or already out): nothing to do. */
        NONE,
        /** Before the fight: the match is cancelled unrated, nobody's Elo or hearts change. */
        CANCEL_MATCH,
        /** In the fight: out as a forfeit, their side marked ineligible for this match's result. */
        FORFEIT,
    }

    /** What a participant found in a restricted state does to their match ([stillFighting] = ACTIVE on the roster). */
    fun resolve(state: MatchState?, stillFighting: Boolean): Resolution = when (state) {
        MatchState.CREATED, MatchState.VALIDATING, MatchState.PREPARING, MatchState.COUNTDOWN -> Resolution.CANCEL_MATCH
        MatchState.ACTIVE -> if (stillFighting) Resolution.FORFEIT else Resolution.NONE
        else -> Resolution.NONE
    }

    /** A held participant may only be in Survival; every other game mode change is refused. */
    fun gameModeChangeAllowed(held: Boolean, toSurvival: Boolean): Boolean = !held || toSurvival

    /** Flight is taken at preparation and never comes back while held. */
    fun flightAllowed(held: Boolean): Boolean = !held

    // ── Result ──────────────────────────────────────────────

    /**
     * Whether the recorded result may change Elo: never when the winning side had a player in a
     * restricted state ([ineligibleSides], stored on the match) or a participant of the winning
     * side was taken out for it ([winnerDefeats]). The other side's ineligibility never blocks it.
     */
    fun ratingBlocked(winner: Side, ineligibleSides: Set<Side>, winnerDefeats: Collection<DefeatReason?>): Boolean =
        winner in ineligibleSides || winnerDefeats.any { it == DefeatReason.INELIGIBLE }

    const val UNRATED_REASON = "a player on the winning side used a restricted ability (God Mode, Vanish, Creative/Spectator, Mod Mode or over-limit gear) during the match"

    /** Stored form of [ineligibleSides] (`A`, `B`, `A,B`; null = none). */
    fun encodeSides(sides: Set<Side>): String? = sides.sortedBy { it.ordinal }.joinToString(",") { it.name }.ifEmpty { null }

    fun decodeSides(raw: String?): Set<Side> =
        raw?.split(',')?.mapNotNull { part -> Side.entries.firstOrNull { it.name == part.trim() } }?.toSet() ?: emptySet()

    // ── Staff actions on players ────────────────────────────

    enum class StaffAction(val id: String, val command: String) {
        HEAL("heal", "/heal"),
        FEED("feed", "/feed"),
        FLY("fly", "/fly"),
        GOD("god", "/god"),
        GAMEMODE("gamemode", "/gamemode"),
        TELEPORT("tp", "/tp"),
        TELEPORT_HERE("tphere", "/tphere"),
        VANISH("vanish", "/vanish"),
        MOD_MODE("modmode", "Mod Mode"),
        FREEZE("freeze", "freeze"),
        SMITE("smite", "/smite"),
        SUDO("sudo", "/sudo"),
        ;

        /** Teleports and delegated commands also wait for a pending inventory restore (the restore moves them back). */
        val coversPendingRestore: Boolean get() = this == TELEPORT || this == TELEPORT_HERE || this == SUDO
    }

    /**
     * Why [action] may not touch a player right now (null = it may). [held] = a match holds them,
     * [pendingRestore] = their match snapshot hasn't been given back yet, [self] = the player is
     * acting on themselves. Applies to console and every staff level alike: the emergency path is
     * cancelling the match or removing the player, never changing them in place.
     */
    fun staffActionRefusal(action: StaffAction, targetName: String, held: Boolean, pendingRestore: Boolean, self: Boolean): String? = when {
        held && self -> "You can't use ${action.command} during a ranked match."
        held -> "$targetName is in a ranked match, so ${action.command} can't change them. To step in, cancel the match or remove them (/lifesteal admin ranked cancel|remove)."
        pendingRestore && action.coversPendingRestore && self -> "You can't use ${action.command} until your ranked inventory is restored."
        pendingRestore && action.coversPendingRestore -> "$targetName is waiting for their ranked inventory to be restored; try again once it is."
        else -> null
    }

    // ── Delegated commands ──────────────────────────────────

    /**
     * Whether a command that runs **as** a held player through `/sudo`, an NPC or a portal may run.
     * Exactly the typed-command allowlist ([RankedMatchRules.commandAllowed]), with the alias
     * resolved through the command map ([canonical]), so `plugin:` names and aliases of other
     * commands can't slip past it. Not held = this rule doesn't apply.
     */
    fun delegatedAllowed(held: Boolean, commandLine: String, canonical: String?): Boolean =
        !held || RankedMatchRules.commandAllowed(commandLine, canonical)

    // ── Emergency interventions ─────────────────────────────

    /**
     * Why [actor] can't cancel a match or remove its players (null = they can). A player of the
     * match ([actorOnRoster], defeated or not) or a member of one of its two teams ([actorOnTeam])
     * could otherwise end a losing PUBLIC/CHALLENGE match for free; TEST matches are staff-made and
     * unrated, so their staff players may still stop them.
     */
    fun interventionRefusal(actorOnRoster: Boolean, actorOnTeam: Boolean, test: Boolean): String? = when {
        test -> null
        actorOnRoster -> "You're playing in this match; another staff member has to cancel it or remove players."
        actorOnTeam -> "Your team is playing in this match; another staff member has to cancel it or remove players."
        else -> null
    }

    // ── Audit throttle ──────────────────────────────────────

    /** One audit row per actor + target + action per minute (the same window as #1487's reward audit). */
    fun throttleKey(actor: String, target: String, action: String): String = "$actor:$target:$action"
}
