package com.liam.joshymc.lifesteal.teams

import com.liam.joshymc.Joshymc
import com.liam.joshymc.command.IgnoreCommand
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.teams.LifestealTeamManager.Companion.command
import com.liam.joshymc.lifesteal.teams.LifestealTeamManager.Companion.text
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Team
import com.liam.joshymc.lifesteal.teams.TeamRelationStore.Refusal
import com.liam.joshymc.lifesteal.teams.TeamRelationStore.Request
import com.liam.joshymc.lifesteal.teams.TeamRelationStore.Result
import com.liam.joshymc.manager.ChatManager
import com.liam.joshymc.manager.ChatSpamDetector
import com.liam.joshymc.util.Msg
import com.liam.joshymc.util.ProfanityFilter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Lifesteal team alliances and rivalries (issue #1405), on top of [LifestealTeamManager]: the same
 * worker, throttle and per-player "one change at a time" rule ([LifestealTeamManager.submit]), the
 * same Lifesteal/permission checks ([LifestealTeamManager.playerProblem]) and the same team cache.
 * Every change is decided again by [TeamRelationStore] inside one transaction (current leader,
 * both teams, limits, cooldowns, rivalries, request validity); the checks here only give fast,
 * friendly answers. The relationship cache ([LifestealTeamManager.relationSnapshot]) is replaced on
 * the worker right after each commit, so protection ends immediately when an alliance does.
 *
 * Alliances give friendly-fire protection (see [TeamCombatRules.Relation.ALLIES]) and `/ac` only:
 * never claims, containers, team management, invitations or any other team's resources. Rivalries
 * are labels: no rewards, hearts, Coins, statistics or Elo. Notifications go to the affected teams
 * only (online members); nothing is broadcast.
 */
class LifestealTeamRelations(private val plugin: Joshymc, private val teams: LifestealTeamManager) {

    val snapshot: TeamRelationStore.Snapshot get() = teams.relationSnapshot

    private val allianceSettings get() = teams.settings?.alliances

    /** Anti-spam for `/ac`, the same repeat rule as public chat (`config.yml > chat.anti-spam`). */
    private val spam: ChatSpamDetector by lazy {
        val config = plugin.config
        ChatSpamDetector(
            ChatSpamDetector.settingsOf(
                enabled = config.getBoolean("chat.anti-spam.enabled", true),
                repeatThreshold = config.getInt("chat.anti-spam.repeat-threshold", ChatSpamDetector.DEFAULT_THRESHOLD),
                windowTicks = config.getInt("chat.anti-spam.window-ticks", ChatSpamDetector.DEFAULT_WINDOW_TICKS),
                muteDurationSeconds = config.getLong("chat.anti-spam.mute-duration-seconds", ChatSpamDetector.DEFAULT_MUTE_SECONDS),
                muteReason = config.getString("chat.anti-spam.mute-reason", ChatSpamDetector.DEFAULT_REASON),
            ) { plugin.logger.warning("[AllianceChat] config.yml chat.anti-spam.$it") }
        )
    }

    /** An expiry pass is queued on the worker; no second one is queued until it reports back. */
    @Volatile private var expiryInFlight = false

    // ── Read API (cache only) ───────────────────────────────

    fun alliancesEnabled(): Boolean = teams.alliancesInForce
    fun rivalriesEnabled(): Boolean = teams.active && teams.settings?.rivalriesEnabled == true
    fun chatEnabled(): Boolean = alliancesEnabled() && allianceSettings?.chatEnabled == true

    fun allyOf(teamId: String): Team? = snapshot.allyOf(teamId)?.let { teams.team(it) }
    fun allianceSince(teamId: String): Long? = snapshot.allianceOf(teamId)?.createdAt
    fun declaredRival(teamId: String): Team? = snapshot.rivalOf(teamId)?.let { teams.team(it.target) }
    fun rivalsAgainst(teamId: String): List<Team> = snapshot.rivalsAgainst(teamId).mapNotNull { teams.team(it.declarer) }
    fun cooldownRemaining(teamId: String, now: Long = System.currentTimeMillis()): Long? = snapshot.cooldownUntil(teamId, now)?.let { it - now }
    fun incoming(teamId: String, now: Long = System.currentTimeMillis()): List<Request> = snapshot.incoming(teamId, now).filter { teams.team(it.source) != null }
    fun outgoing(teamId: String, now: Long = System.currentTimeMillis()): List<Request> = snapshot.outgoing(teamId, now).filter { teams.team(it.target) != null }

    fun formatRemaining(millis: Long): String = HeartRules.formatDuration(millis.coerceAtLeast(1))

    // ── Checks ──────────────────────────────────────────────

    private fun allianceProblem(): String? = teams.unavailableReason() ?: if (allianceSettings?.enabled != true) "Team alliances are turned off right now." else null
    private fun rivalryProblem(): String? = teams.unavailableReason() ?: if (teams.settings?.rivalriesEnabled != true) "Team rivalries are turned off right now." else null

    /** The team [player] leads (cache), or null after telling them why not. Re-checked by the store. */
    private fun ledTeam(player: Player, featureProblem: String?): Team? {
        teams.playerProblem(player)?.let { teams.refuse(player, it); return null }
        featureProblem?.let { teams.refuse(player, it); return null }
        val team = teams.teamOf(player.uniqueId) ?: run { teams.refuse(player, Refusal.NOT_IN_TEAM.message); return null }
        if (!TeamRelationRules.canManage(player.uniqueId, team.leader)) {
            teams.refuse(player, Refusal.NOT_LEADER.message)
            return null
        }
        return team
    }

    /** An exact team name (case-insensitive, never a partial match), or null after telling [player]. */
    private fun named(player: Player, name: String?, usage: String): Team? {
        if (name.isNullOrBlank()) { Msg.usage(player, usage); return null }
        return teams.teamByName(name) ?: run { teams.refuse(player, "There's no team called $name."); null }
    }

    private fun refused(player: Player, result: Result.Refused, teamName: String?) =
        teams.refuse(player, TeamRelationRules.refusalText(result.refusal, result.until, System.currentTimeMillis(), teamName))

    private fun nameOfTeam(id: String, fallback: String = "a team"): String = teams.team(id)?.name ?: fallback

    private fun teamLabel(name: String, color: NamedTextColor): Component = Component.text(name, color).decorate(TextDecoration.BOLD)

    private fun tell(teamId: String, message: Component) {
        teams.team(teamId)?.let { teams.tellTeam(it, message) }
    }

    // ── Alliance requests ───────────────────────────────────

    fun requestAlliance(player: Player, targetName: String?) {
        val own = ledTeam(player, allianceProblem()) ?: return
        val target = named(player, targetName, "/team ally <team>") ?: return
        if (target.id == own.id) return teams.refuse(player, Refusal.SELF_ALLIANCE.message)
        val ttl = (allianceSettings?.requestExpirationHours ?: 24) * 3_600_000L
        val uuid = player.uniqueId
        val name = player.name
        teams.submit(player, uuid, { plugin.lifestealTeamManager.relationStore.requestAlliance(uuid, name, target.id, System.currentTimeMillis(), ttl) }) { result ->
            when (result) {
                is Result.RequestSent -> {
                    val hours = ttl / 3_600_000L
                    tell(own.id, text("Your team sent an alliance request to ", NamedTextColor.GREEN).append(teamLabel(target.name, NamedTextColor.GOLD))
                        .append(text(". It expires in ${hours}h.", NamedTextColor.GREEN)))
                    notifyRequestReceived(result.request, own, target, hours)
                }
                is Result.Refused -> refused(player, result, target.name)
                else -> {}
            }
        }
    }

    private fun notifyRequestReceived(request: Request, source: Team, target: Team, hours: Long) {
        for (member in target.members) {
            val online = Bukkit.getPlayer(member.uuid) ?: continue
            val base = teamLabel(source.name, NamedTextColor.GOLD).append(text(" wants to form an alliance with your team", NamedTextColor.GREEN))
            if (member.uuid == target.leader) {
                teams.send(online, base.append(text(" (expires in ${hours}h). ", NamedTextColor.GREEN))
                    .append(command("[Accept]", "/team ally accept ${source.name}", NamedTextColor.GREEN)).append(text(" ", NamedTextColor.GRAY))
                    .append(command("[Decline]", "/team ally deny ${source.name}", NamedTextColor.RED)))
                // Bedrock clients can't click chat: the typed commands, plainly.
                teams.send(online, text("Type /team ally accept ${source.name} or /team ally deny ${source.name}.", NamedTextColor.GRAY))
            } else {
                teams.send(online, base.append(text(". Your team leader decides.", NamedTextColor.GRAY)))
            }
        }
        plugin.logger.info("[Teams] ${source.name} (${source.id}) requested an alliance with ${target.name} (${target.id}), request ${request.id}.")
    }

    /** [sourceName] may be null when the team has exactly one incoming request. */
    fun acceptAlliance(player: Player, sourceName: String?) {
        val own = ledTeam(player, allianceProblem()) ?: return
        val source = pickIncoming(player, own, sourceName, "accept") ?: return
        // Forming an alliance mid-fight would end a legitimate fight (and its heart) retroactively.
        val involved = own.members.map { it.uuid } + source.members.map { it.uuid }
        if (involved.any { teams.inCombat(it) }) return teams.refuse(player, TeamRelationRules.COMBAT_MESSAGE)
        val uuid = player.uniqueId
        val name = player.name
        teams.submit(player, uuid, { plugin.lifestealTeamManager.relationStore.acceptAlliance(uuid, name, source.id, System.currentTimeMillis()) }) { result ->
            when (result) {
                is Result.Allied -> {
                    plugin.logger.info("[Teams] ${own.name} and ${source.name} formed alliance ${result.alliance.id} (accepted by $name).")
                    tell(own.id, text("Your team is now allied with ", NamedTextColor.GREEN).append(teamLabel(source.name, NamedTextColor.GREEN))
                        .append(text("! Use /ac <message> for alliance chat.", NamedTextColor.GREEN)))
                    tell(source.id, text("Your team is now allied with ", NamedTextColor.GREEN).append(teamLabel(own.name, NamedTextColor.GREEN))
                        .append(text("! Use /ac <message> for alliance chat.", NamedTextColor.GREEN)))
                    notifyInvalidated(result.invalidated, "one of the teams formed an alliance")
                }
                is Result.Refused -> refused(player, result, source.name)
                else -> {}
            }
        }
    }

    fun declineAlliance(player: Player, sourceName: String?) {
        val own = ledTeam(player, allianceProblem()) ?: return
        val source = pickIncoming(player, own, sourceName, "deny") ?: return
        val uuid = player.uniqueId
        val name = player.name
        teams.submit(player, uuid, { plugin.lifestealTeamManager.relationStore.declineAlliance(uuid, name, source.id, System.currentTimeMillis()) }) { result ->
            when (result) {
                is Result.Declined -> {
                    tell(own.id, text("Your team declined the alliance request from ", NamedTextColor.GRAY).append(teamLabel(source.name, NamedTextColor.GOLD)).append(text(".", NamedTextColor.GRAY)))
                    tell(source.id, teamLabel(own.name, NamedTextColor.GOLD).append(text(" declined your team's alliance request.", NamedTextColor.RED)))
                }
                is Result.Refused -> refused(player, result, source.name)
                else -> {}
            }
        }
    }

    fun cancelAlliance(player: Player, targetName: String?) {
        val own = ledTeam(player, allianceProblem()) ?: return
        val target = if (targetName != null) named(player, targetName, "/team ally cancel <team>") ?: return else {
            val pending = outgoing(own.id)
            when (pending.size) {
                0 -> return teams.refuse(player, "Your team has no pending alliance requests.")
                1 -> teams.team(pending[0].target) ?: return teams.refuse(player, "Your team has no pending alliance requests.")
                else -> return teams.refuse(player, "Your team has several pending requests. Use /team ally cancel <team>.")
            }
        }
        val uuid = player.uniqueId
        val name = player.name
        teams.submit(player, uuid, { plugin.lifestealTeamManager.relationStore.cancelAlliance(uuid, name, target.id, System.currentTimeMillis()) }) { result ->
            when (result) {
                is Result.Cancelled -> {
                    tell(own.id, text("Your team's alliance request to ", NamedTextColor.GRAY).append(teamLabel(target.name, NamedTextColor.GOLD)).append(text(" was cancelled.", NamedTextColor.GRAY)))
                    tell(target.id, teamLabel(own.name, NamedTextColor.GOLD).append(text(" cancelled its alliance request to your team.", NamedTextColor.GRAY)))
                }
                is Result.Refused -> refused(player, result, target.name)
                else -> {}
            }
        }
    }

    private fun pickIncoming(player: Player, own: Team, sourceName: String?, verb: String): Team? {
        if (sourceName != null) return named(player, sourceName, "/team ally $verb <team>")
        val pending = incoming(own.id)
        return when (pending.size) {
            0 -> { teams.refuse(player, "Your team has no pending alliance requests."); null }
            1 -> teams.team(pending[0].source) ?: run { teams.refuse(player, "Your team has no pending alliance requests."); null }
            else -> { teams.refuse(player, "Your team has several alliance requests. Use /team ally $verb <team>."); null }
        }
    }

    // ── Ending alliances ────────────────────────────────────

    fun dissolveAlliance(player: Player, confirmed: Boolean) {
        val own = ledTeam(player, allianceProblem()) ?: return
        val allyId = snapshot.allyOf(own.id) ?: return teams.refuse(player, Refusal.NO_ALLIANCE.message)
        val allyName = nameOfTeam(allyId)
        val hours = allianceSettings?.dissolutionCooldownHours ?: 24
        if (!confirmed) {
            return teams.send(player, text("End your alliance with ", NamedTextColor.YELLOW).append(teamLabel(allyName, NamedTextColor.GREEN))
                .append(text("? Friendly-fire protection and alliance chat end right away, and both teams must wait ${hours}h before forming a new alliance. ", NamedTextColor.YELLOW))
                .append(command("[Confirm]", "/team ally leave confirm", NamedTextColor.RED))
                .append(text(" (or type /team ally leave confirm)", NamedTextColor.GRAY)))
        }
        val uuid = player.uniqueId
        val name = player.name
        val cooldown = hours * 3_600_000L
        teams.submit(player, uuid, { plugin.lifestealTeamManager.relationStore.dissolveAlliance(uuid, name, System.currentTimeMillis(), cooldown) }) { result ->
            when (result) {
                is Result.Dissolved -> {
                    plugin.logger.info("[Teams] Alliance ${result.alliance.id} ended by $name (${nameOfTeam(result.byTeam)}).")
                    val remaining = (result.cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0)
                    for (side in listOf(result.alliance.teamA, result.alliance.teamB)) {
                        val other = result.alliance.other(side) ?: continue
                        tell(side, text("Your alliance with ", NamedTextColor.YELLOW).append(teamLabel(nameOfTeam(other), NamedTextColor.GOLD))
                            .append(text(" has ended.", NamedTextColor.YELLOW)))
                        if (remaining > 0) tell(side, text("Your team can form another alliance in ${formatRemaining(remaining)}.", NamedTextColor.GRAY))
                    }
                }
                is Result.Refused -> refused(player, result, allyName)
                else -> {}
            }
        }
    }

    // ── Rivalries ───────────────────────────────────────────

    fun declareRival(player: Player, targetName: String?) {
        val own = ledTeam(player, rivalryProblem()) ?: return
        val target = named(player, targetName, "/team rival <team>") ?: return
        if (target.id == own.id) return teams.refuse(player, Refusal.SELF_RIVALRY.message)
        if (snapshot.allied(own.id, target.id)) return teams.refuse(player, Refusal.RIVAL_IS_ALLY.message)
        val uuid = player.uniqueId
        val name = player.name
        teams.submit(player, uuid, { plugin.lifestealTeamManager.relationStore.declareRival(uuid, name, target.id, System.currentTimeMillis()) }) { result ->
            when (result) {
                is Result.RivalDeclared -> {
                    tell(own.id, text("Your team declared ", NamedTextColor.RED).append(teamLabel(target.name, NamedTextColor.RED))
                        .append(text(" its rival. (Rivalries are labels only: no rewards or PvP changes.)", NamedTextColor.GRAY)))
                    tell(target.id, teamLabel(own.name, NamedTextColor.RED).append(text(" has declared your team a rival!", NamedTextColor.RED)))
                    notifyInvalidated(result.invalidated, "a rivalry was declared")
                }
                is Result.Refused -> refused(player, result, target.name)
                else -> {}
            }
        }
    }

    fun withdrawRival(player: Player) {
        val own = ledTeam(player, rivalryProblem()) ?: return
        val uuid = player.uniqueId
        val name = player.name
        teams.submit(player, uuid, { plugin.lifestealTeamManager.relationStore.withdrawRival(uuid, name, System.currentTimeMillis()) }) { result ->
            when (result) {
                is Result.RivalWithdrawn -> {
                    val target = nameOfTeam(result.rivalry.target)
                    tell(own.id, text("Your team withdrew its rivalry with ", NamedTextColor.GRAY).append(teamLabel(target, NamedTextColor.GOLD)).append(text(".", NamedTextColor.GRAY)))
                    tell(result.rivalry.target, teamLabel(own.name, NamedTextColor.GOLD).append(text(" withdrew its rivalry declaration against your team.", NamedTextColor.GRAY)))
                }
                is Result.Refused -> refused(player, result, null)
                else -> {}
            }
        }
    }

    // ── Notifications ───────────────────────────────────────

    private fun notifyInvalidated(requests: List<Request>, why: String) {
        for (request in requests) {
            val source = nameOfTeam(request.source)
            val target = nameOfTeam(request.target)
            val message = text("The alliance request from $source to $target was cancelled: $why.", NamedTextColor.GRAY)
            tell(request.source, message)
            tell(request.target, message)
        }
    }

    /** After [LifestealTeamManager]'s disband: tell the surviving teams what was removed (no cooldown). */
    fun notifyDisbanded(team: Team, cleanup: TeamRelationStore.Cleanup) {
        if (cleanup.empty) return
        cleanup.alliance?.other(team.id)?.let { survivor ->
            tell(survivor, text("Your alliance with ", NamedTextColor.YELLOW).append(teamLabel(team.name, NamedTextColor.GOLD))
                .append(text(" ended because that team was disbanded. Your team can form a new alliance right away.", NamedTextColor.YELLOW)))
        }
        cleanup.outgoingRival?.let { tell(it.target, teamLabel(team.name, NamedTextColor.GOLD).append(text(" was disbanded, so its rivalry declaration against your team was removed.", NamedTextColor.GRAY))) }
        for (rivalry in cleanup.incomingRivals) {
            tell(rivalry.declarer, teamLabel(team.name, NamedTextColor.GOLD).append(text(" was disbanded, so your team's rivalry declaration was removed.", NamedTextColor.GRAY)))
        }
        for (request in cleanup.requests) {
            val other = if (request.source == team.id) request.target else request.source
            tell(other, text("The alliance request with ${team.name} was cancelled: that team was disbanded.", NamedTextColor.GRAY))
        }
        plugin.logger.info("[Teams] Removed the relationships of disbanded team ${team.name} (${team.id}).")
    }

    /** Every second (from the team sweep): expired requests are handed to the worker once. */
    fun sweep(now: Long) {
        if (expiryInFlight || !snapshot.anyExpired(now)) return
        expiryInFlight = true
        val queued = teams.background({
            try {
                plugin.lifestealTeamManager.relationStore.expireRequests(System.currentTimeMillis())
            } catch (e: Exception) {
                plugin.logger.warning("[Teams] Couldn't expire alliance requests (they can't be accepted anyway): ${e.message}")
                emptyList()
            }
        }) { expired ->
            expiryInFlight = false
            for (request in expired) {
                val source = nameOfTeam(request.source)
                val target = nameOfTeam(request.target)
                tell(request.source, text("Your team's alliance request to $target expired.", NamedTextColor.GRAY))
                tell(request.target, text("The alliance request from $source expired.", NamedTextColor.GRAY))
            }
        }
        if (!queued) expiryInFlight = false
    }

    /** A leader coming online is told about incoming alliance requests (offline notices aren't stored). */
    fun onJoin(player: Player) {
        if (!alliancesEnabled() || !teams.isLifesteal(player)) return
        val team = teams.teamOf(player.uniqueId) ?: return
        if (team.leader != player.uniqueId) return
        val pending = incoming(team.id)
        if (pending.isEmpty()) return
        teams.send(player, text("Your team has ${pending.size} pending alliance request(s). ", NamedTextColor.GREEN).append(command("[View]", "/team ally")))
    }

    // ── Alliance chat (/ac) ─────────────────────────────────

    /**
     * `/ac <message>`: to the online members (in Lifesteal) of the sender's team and its ally only.
     * Follows the chat rules a public message would meet: gamemode mutes, the global chat mute,
     * the profanity filter and the repeated-message rule. Recipients are read from the current
     * alliance right before delivery, so a former ally never receives anything.
     */
    fun allianceChat(player: Player, rawMessage: String) {
        teams.playerProblem(player)?.let { return teams.refuse(player, it) }
        allianceProblem()?.let { return teams.refuse(player, it) }
        if (allianceSettings?.chatEnabled != true) return teams.refuse(player, "Alliance chat is turned off right now.")
        val team = teams.teamOf(player.uniqueId) ?: return teams.refuse(player, "You're not in a team. Alliance chat is for teams in an alliance.")
        if (snapshot.allyOf(team.id) == null) {
            return teams.refuse(player, "Your team has no alliance. Leaders can send one with /team ally <team>.")
        }
        val message = rawMessage.trim()
        if (message.isEmpty()) return Msg.usage(player, "/ac <message>")
        plugin.punishmentManager.isMutedIn(player.uniqueId, ServerGamemode.LIFESTEAL)?.let {
            return teams.refuse(player, "You are muted" + (it.reason?.let { reason -> ": $reason" } ?: "."))
        }
        if (plugin.chatManager.isMuted && !player.hasPermission(ChatManager.PERM_MUTE_BYPASS)) return teams.refuse(player, "Chat is currently muted.")
        if (!player.hasPermission(SPAM_BYPASS) && spam.record(player.uniqueId, message, Bukkit.getCurrentTick())) {
            return teams.refuse(player, "Please don't repeat the same message.")
        }
        val clean = if (!player.hasPermission(PROFANITY_BYPASS) && ProfanityFilter.contains(message)) {
            teams.send(player, text("Your message contained banned words and was censored.", NamedTextColor.RED))
            ProfanityFilter.censor(message)
        } else message

        // Authoritative state right before delivery.
        val current = teams.relationSnapshot
        val recipients = TeamRelationRules.chatRecipients(team.id, { current.allyOf(it) }, { teams.roster(it) })
        if (recipients.isEmpty()) return teams.refuse(player, "Your team has no alliance any more.")
        val formatted = Component.text("[Alliance] ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
            .append(Component.text("[${team.name}] ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, false))
            .append(Component.text(player.name, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
            .append(Component.text(": ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
            .append(Component.text(clean, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
        for (uuid in recipients) {
            val target = Bukkit.getPlayer(uuid) ?: continue
            if (!target.isOnline || !teams.isLifesteal(target)) continue
            if (uuid != player.uniqueId && IgnoreCommand.isIgnoring(uuid, player.uniqueId)) continue
            target.sendMessage(formatted)
        }
        plugin.logger.info("[AllianceChat] ${team.name}/${nameOfTeam(current.allyOf(team.id) ?: "")} ${player.name}: $clean")
    }

    fun onQuit(player: Player) {
        spam.forget(player.uniqueId)
    }

    companion object {
        private const val PROFANITY_BYPASS = "joshymc.chat.profanity.bypass"
        private const val SPAM_BYPASS = "joshymc.antispam.bypass"
    }
}
