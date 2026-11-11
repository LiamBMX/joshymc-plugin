package com.liam.joshymc.lifesteal.ranked.queue

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.progression.LifestealProgressionManager
import com.liam.joshymc.lifesteal.ranked.ArenaAvailability
import com.liam.joshymc.lifesteal.ranked.LifestealRankedManager
import com.liam.joshymc.lifesteal.ranked.MatchKind
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Team
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.time.Duration
import java.util.UUID

/**
 * Public Ranked Team Wars matchmaking, queues and team challenges (issue #1363). Lifesteal only:
 * where Lifesteal isn't hosted it does nothing (no table, no task, `/ranked` is released) and every
 * action refuses.
 *
 * It only **coordinates**: who plays is decided by the team leader and agreed to by every selected
 * player ([RankedQueueBook], FORMING); teams wait in a per-size queue (WAITING); the matchmaker
 * pairs the longest-waiting compatible teams ([OpponentSelector], FIFO for now) when an arena is
 * free; everyone accepts the found match (CONFIRMING); then [LifestealRankedManager.createQueuedMatch]
 * revalidates everything, reserves the arena, snapshots inventories and runs the normal match
 * lifecycle. This class never changes an inventory, a position or a heart, and never touches
 * combat tags.
 *
 * Main thread only. The queue is memory only and bounded; a restart or reload starts it empty.
 * The only database work is the async audit log ([RankedQueueStore]); matching reads memory only.
 */
class LifestealRankedQueueManager(private val plugin: Joshymc) {

    private val book = RankedQueueBook()
    private val store = RankedQueueStore(plugin.databaseManager)

    /** Issue #1491: staff TEST matches, which need the staff member's confirmation and every participant's own acceptance. */
    val tests = RankedTestMatchService(plugin, this)

    /** How opponents are picked. A later Elo system swaps this; nothing else changes. */
    var selector: OpponentSelector = FirstInFirstOut

    @Volatile var settings: LifestealSettings.RankedMatchmaking? = null
        private set
    private var sizes: List<Int> = emptyList()
    @Volatile private var tablesReady = false
    private var task: BukkitTask? = null
    private var ticks = 0L

    private val teamCooldown = BoundedCooldowns<String>(COOLDOWN_CAPACITY)
    private val playerCooldown = BoundedCooldowns<UUID>(COOLDOWN_CAPACITY)
    private val challengeCooldown = BoundedCooldowns<String>(COOLDOWN_CAPACITY)
    private val actionSpam = BoundedCooldowns<UUID>(COOLDOWN_CAPACITY)
    /** Last "matchmaking paused" notice per entry id (cleared with the entry). */
    private val pausedNotice = HashMap<String, String>()

    private val ranked get() = plugin.lifestealRankedManager
    private val teams get() = plugin.lifestealTeamManager

    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes

    /**
     * Issue #1369: `lifesteal.yml > ranked-team-wars.enabled`. While false, `/ranked` is closed to
     * players (only ranked staff can open it) and the progression reports Ranked as not playable,
     * so the +72h unlock alone never opens it.
     */
    @Volatile var playerAccess: Boolean = false
        private set

    /** Matchmaking can run (the season unlock is checked separately by the progression). */
    val systemReady: Boolean get() = hosted && playerAccess && tablesReady && settings?.enabled == true && ranked.active

    /** [sender] may see and use `/ranked` right now (players only once it is enabled; ranked staff always). */
    fun commandVisibleTo(sender: org.bukkit.command.CommandSender): Boolean =
        hosted && (playerAccess || sender.hasPermission(LifestealRankedManager.ADMIN_PERMISSION))

    val allowedSizes: List<Int> get() = sizes

    fun isLifesteal(player: Player): Boolean = plugin.gamemodeLockManager.gamemodeOf(player.world) == ServerGamemode.LIFESTEAL

    // ── Read API (memory only) ──────────────────────────────

    fun entryOf(uuid: UUID): QueueEntry? = book.entryOf(uuid)
    fun entryOfTeam(teamId: String): QueueEntry? = book.entryOfTeam(teamId)
    fun proposalOf(uuid: UUID): Proposal? = book.proposalOf(uuid)
    fun proposal(id: String): Proposal? = book.proposal(id)
    fun entry(id: String): QueueEntry? = book.entry(id)
    fun entries(): List<QueueEntry> = book.allEntries()
    fun proposals(): List<Proposal> = book.allProposals()
    fun challenges(): List<Challenge> = book.allChallenges()
    fun incomingChallenges(teamId: String): List<Challenge> = book.incomingChallenges(teamId)
    fun outgoingChallenge(teamId: String): Challenge? = book.outgoingChallenge(teamId)
    fun reservedArenas(): Set<String> = book.reservedArenas()
    fun waitingCount(size: Int): Int = book.waiting(size).size
    fun cooldownCounts(): String = "teams ${teamCooldown.size}, players ${playerCooldown.size}, challenges ${challengeCooldown.size}"
    fun recentAudit(limit: Int): List<RankedQueueStore.Row> = store.recent(limit)

    /** Seconds before [uuid] may queue again after declining/ignoring/leaving a found match (0 = may). */
    fun playerCooldownSeconds(uuid: UUID): Long = seconds(playerCooldown.remaining(uuid, System.currentTimeMillis()))
    fun teamCooldownSeconds(teamId: String): Long = seconds(teamCooldown.remaining(teamId, System.currentTimeMillis()))

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload, after lifesteal.yml, teams, progression and the match manager. */
    fun refresh() {
        cancelAll("ranked queues were reset by a server reload")
        tests.clear("ranked queues were reset by a server reload")
        task?.cancel()
        task = null
        val progression = plugin.lifestealProgressionManager
        if (!hosted) {
            settings = null
            sizes = emptyList()
            playerAccess = false
            progression.rankedTeamWarsSystem = null
            return
        }
        val lifesteal = plugin.lifestealConfigManager.settings
        val wasOpen = playerAccess
        playerAccess = lifesteal?.rankedTeamWars?.enabled == true
        // The client's command list (tab completion) only changes when it is resent.
        if (wasOpen != playerAccess) Bukkit.getOnlinePlayers().forEach { it.updateCommands() }
        settings = lifesteal?.rankedTeamWars?.matchmaking
        sizes = lifesteal?.rankedTeamWars?.teamSizes ?: emptyList()
        if (lifesteal == null) plugin.logger.severe("[RankedQueue] lifesteal.yml is invalid, so ranked matchmaking is off.")
        if (!tablesReady) {
            try {
                store.createTables()
                tablesReady = true
                val now = System.currentTimeMillis()
                plugin.databaseManager.async("ranked queue audit prune") { store.prune(now) }
            } catch (e: Exception) {
                plugin.logger.severe("[RankedQueue] Could not create the ranked queue audit table; matchmaking is off: ${e.message}")
            }
        }
        // The progression now tells "unlocked by schedule" from "playable" through this.
        progression.rankedTeamWarsSystem = LifestealProgressionManager.RankedTeamWarsSystem { systemReady }
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
        val s = settings
        plugin.logger.info(
            if (s == null) "[RankedQueue] Matchmaking unavailable (lifesteal.yml)."
            else if (!playerAccess) "[RankedQueue] Ranked Team Wars are closed to players (lifesteal.yml > ranked-team-wars.enabled: false); staff tools still work."
            else "[RankedQueue] Matchmaking ${if (s.enabled) "on" else "off"}: sizes ${sizes.joinToString { "${it}v$it" }}, agree ${s.consentSeconds}s, accept ${s.confirmSeconds}s, queue timeout ${s.queueTimeoutMinutes} min."
        )
    }

    /** Shutdown (before the match manager stops): every queue entry, found match and challenge ends. */
    fun stop() {
        cancelAll("the server is shutting down")
        tests.clear("the server is shutting down")
        task?.cancel()
        task = null
        plugin.lifestealProgressionManager.rankedTeamWarsSystem = null
    }

    private fun cancelAll(reason: String) {
        if (book.isEmpty) return
        val now = System.currentTimeMillis()
        for (e in book.clear(now)) {
            notifyEntry(e, text("Your ${describe(e)} was cancelled: $reason. Nothing happened to your inventory or hearts.", NamedTextColor.RED))
            audit("RESET", e.id, e.teamId, null, reason)
        }
        pausedNotice.clear()
    }

    // ── Checks ──────────────────────────────────────────────

    /** Why nobody can queue right now (null = the system is open). */
    fun unavailableReason(now: Long = System.currentTimeMillis()): String? {
        if (!hosted) return "Ranked Team Wars only exist on the Lifesteal server."
        val s = settings ?: return "Ranked matchmaking is unavailable right now (server configuration). Please tell staff."
        if (!playerAccess) return CLOSED_MESSAGE
        if (!s.enabled) return "Ranked matchmaking is turned off right now."
        if (!tablesReady || !ranked.active) return "Ranked Team Wars are unavailable right now. Please try again later."
        teams.unavailableReason()?.let { return it }
        if (!plugin.lifestealProgressionManager.canEnterRankedQueue(now)) return LOCKED_MESSAGE
        return null
    }

    fun playerProblem(player: Player, now: Long = System.currentTimeMillis()): String? = unavailableReason(now) ?: when {
        !player.hasPermission(PERMISSION_USE) -> "You don't have permission to play Ranked Team Wars."
        !isLifesteal(player) -> "Ranked Team Wars only work in Lifesteal."
        else -> null
    }

    /** Spam guard for commands and GUI clicks (true = ignore this action). */
    private fun spamming(player: Player): Boolean {
        val now = System.currentTimeMillis()
        if (actionSpam.remaining(player.uniqueId, now) > 0) return true
        actionSpam.start(player.uniqueId, now, ACTION_COOLDOWN_MS)
        return false
    }

    /** Online teammates who could play right now (for the menu). */
    fun eligibleTeammates(team: Team): List<UUID> = team.members.mapNotNull { m ->
        val p = Bukkit.getPlayer(m.uuid)?.takeIf { it.isOnline } ?: return@mapNotNull null
        if (book.entryOf(m.uuid) != null && book.entryOf(m.uuid)?.teamId != team.id) return@mapNotNull null
        if (ranked.participantProblem(p, team) != null) null else m.uuid
    }

    /** Why [player]'s team can't start a [size] queue/challenge right now, ignoring who is picked (null = it can). */
    fun startProblem(player: Player, size: Int, now: Long = System.currentTimeMillis()): String? {
        playerProblem(player, now)?.let { return it }
        if (size !in sizes) return "${size}v$size isn't offered (sizes: ${sizes.joinToString { "${it}v$it" }})."
        val team = teams.teamOf(player.uniqueId) ?: return "Join a Lifesteal team first (/team)."
        if (team.leader != player.uniqueId) return "Only your team leader can queue the team or send challenges."
        book.entryOfTeam(team.id)?.let { return "Your team is already in a ranked ${if (it.kind == EntryKind.CHALLENGE) "challenge" else "queue"} (/ranked status)." }
        seconds(teamCooldown.remaining(team.id, now)).takeIf { it > 0 }?.let { return "Your team can queue again in ${it}s." }
        if (team.size < size) return "Your team needs at least $size members for ${size}v$size (it has ${team.size})."
        if (!ranked.hostsSize(size)) return "No arena hosts ${size}v$size right now."
        return null
    }

    /** Every reason the [roster] of [team] can't be registered (empty = it can). */
    private fun rosterProblems(team: Team, roster: List<UUID>, now: Long): List<String> = roster.mapNotNull { uuid ->
        val name = teams.nameOf(uuid)
        val player = Bukkit.getPlayer(uuid)?.takeIf { it.isOnline }
        when {
            teams.teamOf(uuid)?.id != team.id -> "$name isn't in ${team.name}."
            player == null -> "$name isn't online."
            book.entryOf(uuid) != null -> "$name is already in a ranked queue or challenge."
            tests.busy(uuid) -> "$name is asked to play a staff test match right now."
            seconds(playerCooldown.remaining(uuid, now)) > 0 -> "$name declined or missed a ranked match recently (${seconds(playerCooldown.remaining(uuid, now))}s left)."
            else -> ranked.participantProblem(player, team)?.let { "$name $it." }
        }
    }

    /** Hard problem (the entry can't continue at all), or null. */
    private fun hardProblem(e: QueueEntry, season: String): String? {
        val team = teams.team(e.teamId) ?: return "${e.teamName} no longer exists"
        if (e.seasonId != season) return "a new season started"
        for (uuid in e.participants) {
            val name = teams.nameOf(uuid)
            if (teams.teamOf(uuid)?.id != team.id) return "$name is no longer in ${team.name}"
            if (Bukkit.getPlayer(uuid)?.isOnline != true) return "$name went offline"
        }
        return null
    }

    /** Why [e] can't be matched this moment (combat tag, death, ...), or null. */
    private fun softProblem(e: QueueEntry): String? {
        val team = teams.team(e.teamId) ?: return "${e.teamName} no longer exists"
        for (uuid in e.participants) {
            val player = Bukkit.getPlayer(uuid)?.takeIf { it.isOnline } ?: return "${teams.nameOf(uuid)} is offline"
            if (teams.teamOf(uuid)?.id != team.id) return "${player.name} left the team"
            ranked.participantProblem(player, team)?.let { return "${player.name} $it" }
        }
        return null
    }

    private fun currentSeason(now: Long): String = plugin.lifestealProgressionManager.progress(now).season?.id ?: "none"

    // ── Roster selection ────────────────────────────────────

    /**
     * Who plays, from the names the leader listed. Listing `size - 1` teammates means the leader
     * plays too; listing nobody is only allowed when exactly `size` members are online (they are
     * then each asked to agree; nobody is ever enrolled without agreeing).
     */
    fun resolveRoster(leader: Player, team: Team, size: Int, names: List<String>): Pair<List<UUID>?, String?> {
        if (names.isEmpty()) {
            val online = team.members.map { it.uuid }.filter { Bukkit.getPlayer(it)?.isOnline == true }
            if (online.size == size) return online to null
            val list = online.filter { it != leader.uniqueId }.joinToString { teams.nameOf(it) }.ifEmpty { "nobody" }
            return null to "Pick who plays: list ${size - 1} teammates (you play too) or $size. Online teammates: $list."
        }
        val chosen = LinkedHashSet<UUID>()
        for (raw in names) {
            val member = team.members.firstOrNull { teams.nameOf(it.uuid).equals(raw, ignoreCase = true) }
                ?: return null to "$raw isn't in ${team.name}."
            if (!chosen.add(member.uuid)) return null to "${teams.nameOf(member.uuid)} is listed twice."
        }
        val roster = if (chosen.size == size - 1 && leader.uniqueId !in chosen) listOf(leader.uniqueId) + chosen else chosen.toList()
        if (roster.size != size) return null to "A ${size}v$size needs exactly $size players: list ${size - 1} teammates (you play too) or $size."
        return roster to null
    }

    // ── Public queue ────────────────────────────────────────

    fun queue(player: Player, size: Int, names: List<String>) {
        if (spamming(player)) return
        startProblem(player, size)?.let { return Msg.error(player, it) }
        val team = teams.teamOf(player.uniqueId) ?: return
        val (roster, problem) = resolveRoster(player, team, size, names)
        if (roster == null) return Msg.error(player, problem ?: "Couldn't pick the players.")
        register(player, team, size, roster, EntryKind.PUBLIC, null)
    }

    /** The menu's player picker (the same checks as the command). */
    fun queueWith(player: Player, size: Int, roster: List<UUID>) {
        if (spamming(player)) return
        startProblem(player, size)?.let { return Msg.error(player, it) }
        val team = teams.teamOf(player.uniqueId) ?: return
        register(player, team, size, roster, EntryKind.PUBLIC, null)
    }

    private fun register(player: Player, team: Team, size: Int, roster: List<UUID>, kind: EntryKind, challengeTarget: Team?): Boolean {
        val now = System.currentTimeMillis()
        if (roster.size != size || roster.toSet().size != size) {
            Msg.error(player, "A ${size}v$size needs exactly $size different players.")
            return false
        }
        val problems = rosterProblems(team, roster, now)
        if (problems.isNotEmpty()) {
            Msg.error(player, "Your team can't ${if (kind == EntryKind.CHALLENGE) "send this challenge" else "queue"} yet:")
            for (p in problems.take(MAX_LISTED)) plugin.commsManager.send(player, text(" - $p", NamedTextColor.RED))
            return false
        }
        val s = settings ?: return false
        val season = currentSeason(now)
        val entryId = RankedQueueBook.newId("rq")
        if (kind == EntryKind.PUBLIC) {
            val entry = QueueEntry(entryId, EntryKind.PUBLIC, team.id, team.name, size, roster, player.uniqueId, now, season)
            book.register(entry, listOf(player.uniqueId), now)?.let { Msg.error(player, it); return false }
            audit("QUEUED", entry.id, team.id, player.uniqueId, "${size}v$size: ${names(roster)}")
            afterRegister(entry, null)
            return true
        }
        val target = challengeTarget ?: return false
        val challengeId = RankedQueueBook.newId("rc")
        val entry = QueueEntry(entryId, EntryKind.CHALLENGE, team.id, team.name, size, roster, player.uniqueId, now, season, challengeId)
        val challenge = Challenge(challengeId, team.id, team.name, target.id, target.name, size, entry.id, now, now + s.challengeExpireSeconds * 1000L)
        book.challenge(challenge, entry, listOf(player.uniqueId), now)?.let { Msg.error(player, it); return false }
        challengeCooldown.start(team.id, now, s.challengeCooldownSeconds * 1000L)
        audit("CHALLENGED", challenge.id, team.id, player.uniqueId, "${team.name} -> ${target.name} ${size}v$size: ${names(roster)}")
        afterRegister(entry, target.name)
        announceChallenge(challenge, target)
        return true
    }

    /** Messages after a roster was registered: consent prompts, or "queued" when nobody else must agree. */
    private fun afterRegister(entry: QueueEntry, opponent: String?) {
        val s = settings ?: return
        val leader = teams.nameOf(entry.initiator)
        val what = describe(entry) + (opponent?.let { " against $it" } ?: "")
        if (entry.state == QueueState.WAITING) return onWaiting(entry)
        tell(entry.initiator, text("Asked ${names(entry.pendingConsent)} to play the $what. Waiting for them to agree (${s.consentSeconds}s).", NamedTextColor.YELLOW))
        for (uuid in entry.pendingConsent) {
            tell(uuid, text("$leader picked you for ${entry.teamName}'s $what.", NamedTextColor.YELLOW))
            tell(uuid, riskLines())
            tell(uuid, button("[Agree]", "/ranked accept ${entry.id}", NamedTextColor.GREEN)
                .append(text("  ", NamedTextColor.GRAY))
                .append(button("[Decline]", "/ranked decline ${entry.id}", NamedTextColor.RED))
                .append(text("  ${s.consentSeconds}s - or /ranked accept, /ranked decline, /ranked", NamedTextColor.GRAY)))
            actionBar(uuid, text("Ranked: ${entry.teamName} wants you for a ${entry.size}v${entry.size}. /ranked accept", NamedTextColor.GOLD))
        }
    }

    private fun onWaiting(entry: QueueEntry) {
        if (entry.kind == EntryKind.PUBLIC) {
            notifyEntry(entry, text("${entry.teamName} is in the ${entry.size}v${entry.size} ranked queue (${waitingCount(entry.size)} team(s) waiting). Everyone confirms once an opponent is found. /ranked leave to cancel.", NamedTextColor.GREEN))
            tryMatch(entry.size)
        } else {
            notifyEntry(entry, text("${entry.teamName}'s challenge roster is ready.", NamedTextColor.GREEN))
            tryChallenges()
        }
    }

    // ── Agree / accept / decline / leave ────────────────────

    /** `/ranked accept [token]`: agree to play (FORMING) or accept a found match (CONFIRMING). */
    fun accept(player: Player, token: String?) {
        if (spamming(player)) return
        if (!hosted) return Msg.error(player, "Ranked Team Wars only exist on the Lifesteal server.")
        if (tests.handles(player.uniqueId, token)) return tests.accept(player, token)
        val entry = book.entryOf(player.uniqueId) ?: return Msg.error(player, if (token != null) STALE else "You have no ranked request to accept.")
        val now = System.currentTimeMillis()
        when (entry.state) {
            QueueState.FORMING -> {
                if (token != null && token != entry.id) return Msg.error(player, STALE)
                if (player.uniqueId !in entry.pendingConsent) return Msg.reply(player, text("You already agreed; waiting for ${names(entry.pendingConsent)}.", NamedTextColor.GRAY))
                val team = teams.team(entry.teamId)
                val problem = when {
                    team == null || teams.teamOf(player.uniqueId)?.id != team.id -> "you're no longer in ${entry.teamName}"
                    else -> playerProblem(player, now) ?: ranked.participantProblem(player, team)
                }
                if (problem != null) return Msg.error(player, "You can't agree right now: $problem. The request stays open until it times out.")
                val result = book.consent(player.uniqueId, now)
                if (result !is RankedQueueBook.ConsentResult.Recorded) return Msg.error(player, STALE)
                audit("AGREED", entry.id, entry.teamId, player.uniqueId, null)
                notifyEntry(entry, text("${player.name} agreed (${entry.consented.size}/${entry.size}).", NamedTextColor.GREEN))
                if (result.complete) onWaiting(entry)
            }
            QueueState.CONFIRMING -> acceptMatch(player, entry, token, now)
            QueueState.WAITING -> Msg.reply(player, text("Your team is waiting for an opponent; you'll be asked to accept once one is found.", NamedTextColor.GRAY))
            else -> Msg.error(player, "There's nothing to accept right now (${entry.state.label}).")
        }
    }

    private fun acceptMatch(player: Player, entry: QueueEntry, token: String?, now: Long) {
        if (plugin.combatManager.isTagged(player)) return Msg.error(player, "You can't accept while in combat. Get out of combat before the timer runs out.")
        val team = teams.team(entry.teamId)
        val problem = when {
            team == null || teams.teamOf(player.uniqueId)?.id != team.id -> "you're no longer in ${entry.teamName}"
            else -> ranked.participantProblem(player, team)
        }
        if (problem != null) return Msg.error(player, "You can't accept right now: $problem.")
        when (val r = book.accept(player.uniqueId, token, now)) {
            RankedQueueBook.AcceptResult.NothingToAccept -> Msg.error(player, "There's no match to accept.")
            RankedQueueBook.AcceptResult.Stale -> Msg.error(player, STALE)
            RankedQueueBook.AcceptResult.Expired -> Msg.error(player, "Too late: that match request expired.")
            RankedQueueBook.AcceptResult.Already -> Msg.reply(player, text("You already accepted.", NamedTextColor.GRAY))
            is RankedQueueBook.AcceptResult.Accepted -> {
                val p = r.proposal
                audit("ACCEPTED", p.id, entry.teamId, player.uniqueId, null)
                for (uuid in p.participants) tell(uuid, text("${player.name} accepted (${p.accepted.size}/${p.participants.size}).", NamedTextColor.GREEN))
                if (p.complete) start(p)
            }
        }
    }

    /** `/ranked decline [token]`: refuse to play (FORMING) or refuse a found match (CONFIRMING). */
    fun decline(player: Player, token: String?) {
        if (spamming(player)) return
        if (tests.handles(player.uniqueId, token)) return tests.decline(player, token)
        val entry = book.entryOf(player.uniqueId) ?: return Msg.error(player, if (token != null) STALE else "You have no ranked request to decline.")
        // A button only answers the stage it was sent for: an old "agree" prompt can't decline a found match.
        val current = if (entry.state == QueueState.CONFIRMING) entry.proposalId else entry.id
        if (token != null && token != current) return Msg.error(player, STALE)
        if (entry.state == QueueState.PREPARING) return Msg.error(player, "The match is already being set up.")
        audit("DECLINED", entry.proposalId ?: entry.id, entry.teamId, player.uniqueId, entry.state.name)
        when (entry.state) {
            QueueState.FORMING -> end(entry, QueueState.CANCELLED, "${player.name} declined", null)
            QueueState.CONFIRMING -> end(entry, QueueState.CANCELLED, "${player.name} declined the match", player.uniqueId)
            QueueState.WAITING, QueueState.MATCH_FOUND -> end(entry, QueueState.CANCELLED, "${player.name} left", null)
            else -> return
        }
        Msg.success(player, "Declined. Nothing happened to your inventory or hearts.")
    }

    /** `/ranked leave`: a selected player or the team leader withdraws the team's entry. */
    fun leave(player: Player) {
        if (spamming(player)) return
        val team = teams.teamOf(player.uniqueId)
        val entry = book.entryOf(player.uniqueId) ?: team?.takeIf { it.leader == player.uniqueId }?.let { book.entryOfTeam(it.id) }
            ?: return Msg.error(player, "You aren't in a ranked queue or challenge.")
        if (entry.state == QueueState.PREPARING) return Msg.error(player, "The match is already being set up.")
        val penalize = if (entry.state == QueueState.CONFIRMING && player.uniqueId in entry.participants) player.uniqueId else null
        audit("LEFT", entry.id, entry.teamId, player.uniqueId, entry.state.name)
        end(entry, QueueState.CANCELLED, "${player.name} left", penalize)
        Msg.success(player, "You left the ranked ${if (entry.kind == EntryKind.CHALLENGE) "challenge" else "queue"}.")
    }

    // ── Ending ──────────────────────────────────────────────

    private fun end(entry: QueueEntry, state: QueueState, reason: String, penalize: UUID?) {
        val now = System.currentTimeMillis()
        val ended = book.end(entry.id, state, now) ?: return
        penalize?.let { playerCooldown.start(it, now, (settings?.declineCooldownSeconds ?: 0) * 1000L) }
        handleEnded(ended, reason)
    }

    private fun handleEnded(ended: RankedQueueBook.Ended, reason: String) {
        val now = System.currentTimeMillis()
        val e = ended.entry
        pausedNotice.remove(e.id)
        teamCooldown.start(e.teamId, now, (settings?.requeueCooldownSeconds ?: 0) * 1000L)
        val verb = if (e.state == QueueState.EXPIRED) "expired" else "was cancelled"
        notifyEntry(e, text("Your ${describe(e)} $verb: $reason. Nothing happened to your inventory or hearts.", NamedTextColor.RED))
        audit(e.state.name, e.id, e.teamId, null, reason)
        ended.proposal?.let { p -> audit("MATCH_" + p.state.name, p.id, null, null, reason) }
        ended.returned?.let { r ->
            notifyEntry(r, text("The other team didn't accept ($reason). You're back in the queue with your place.", NamedTextColor.YELLOW))
        }
        ended.alsoEnded?.let { o ->
            pausedNotice.remove(o.id)
            notifyEntry(o, text("The challenge between ${ended.challenge?.fromTeamName} and ${ended.challenge?.toTeamName} ended: $reason.", NamedTextColor.RED))
            audit("CANCELLED", o.id, o.teamId, null, "challenge ended: $reason")
        }
        ended.challenge?.let { c ->
            audit("CHALLENGE_" + c.state.name, c.id, c.fromTeamId, null, reason)
            // The challenged leader hadn't answered yet: let them know it's gone.
            if (ended.alsoEnded == null && c.toEntryId == null) teams.team(c.toTeamId)?.let { tell(it.leader, text("The challenge from ${c.fromTeamName} ended: $reason.", NamedTextColor.GRAY)) }
        }
        ended.returned?.let { tryMatch(it.size) }
    }

    // ── Matchmaking ─────────────────────────────────────────

    /** Proposes as many found matches of [size] as there are free arenas and compatible pairs. */
    private fun tryMatch(size: Int) {
        val s = settings ?: return
        if (unavailableReason() != null) return
        repeat(book.limits.maxProposals) {
            val now = System.currentTimeMillis()
            val waiting = book.waiting(size).filter { it.problem == null }
            if (waiting.size < 2) return
            val arena = ranked.availableArenaFor(size, book.reservedArenas()) ?: return
            val pair = selector.select(waiting, book::compatible) ?: return
            // Revalidate both right before proposing; a problem parks that entry until the next tick.
            val stale = listOf(pair.first, pair.second).filter { e -> softProblem(e)?.also { e.problem = it } != null }
            if (stale.isNotEmpty()) return@repeat
            val p = book.propose(pair.first, pair.second, arena.id, RankedQueueBook.newId("rp"), now, s.confirmSeconds * 1000L) ?: return
            announceProposal(p, arena.displayName)
        }
    }

    private fun tryChallenges() {
        val s = settings ?: return
        if (unavailableReason() != null) return
        for ((a, b) in book.readyChallenges()) {
            if (softProblem(a)?.also { a.problem = it } != null || softProblem(b)?.also { b.problem = it } != null) continue
            a.problem = null; b.problem = null
            val arena = ranked.availableArenaFor(a.size, book.reservedArenas()) ?: continue
            val p = book.propose(a, b, arena.id, RankedQueueBook.newId("rp"), System.currentTimeMillis(), s.confirmSeconds * 1000L) ?: continue
            announceProposal(p, arena.displayName)
        }
    }

    private fun announceProposal(p: Proposal, arenaName: String) {
        val s = settings ?: return
        val a = book.entry(p.entryA) ?: return
        val b = book.entry(p.entryB) ?: return
        audit("MATCH_FOUND", p.id, null, null, "${a.teamName} vs ${b.teamName} ${p.size}v${p.size} (${p.kind.name}) arena ${p.arenaId}")
        val times = Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))
        for (uuid in p.participants) {
            val mine = if (uuid in a.participants) a else b
            val other = if (mine === a) b else a
            val player = Bukkit.getPlayer(uuid) ?: continue
            player.showTitle(Title.title(text("Match Found!", NamedTextColor.GOLD), text("${mine.teamName} vs ${other.teamName} - ${p.size}v${p.size}", NamedTextColor.YELLOW), times))
            tell(uuid, text("${if (p.kind == EntryKind.CHALLENGE) "Team challenge" else "Ranked match"} found: ", NamedTextColor.GOLD)
                .append(text(mine.teamName, NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                .append(text(" vs ", NamedTextColor.GRAY))
                .append(text(other.teamName, NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                .append(text(" (${p.size}v${p.size}) in $arenaName.", NamedTextColor.GOLD)))
            tell(uuid, riskLines())
            tell(uuid, button("[Accept]", "/ranked accept ${p.id}", NamedTextColor.GREEN)
                .append(text("  ", NamedTextColor.GRAY))
                .append(button("[Decline]", "/ranked decline ${p.id}", NamedTextColor.RED))
                .append(text("  ${s.confirmSeconds}s - everyone must accept (/ranked)", NamedTextColor.GRAY)))
        }
    }

    /** Everyone accepted: revalidate, then hand the match to the existing lifecycle. */
    private fun start(proposal: Proposal) {
        val now = System.currentTimeMillis()
        val p = book.beginStart(proposal.id, now) ?: return
        val a = book.entry(p.entryA) ?: return fail(p, emptySet(), "a team's entry disappeared")
        val b = book.entry(p.entryB) ?: return fail(p, emptySet(), "a team's entry disappeared")
        unavailableReason(now)?.let { return fail(p, setOf(a.id, b.id), it.trimEnd('.')) }
        // 1-2: both teams and every participant again.
        val faulty = LinkedHashSet<String>()
        val reasons = mutableListOf<String>()
        for (e in listOf(a, b)) {
            val problem = hardProblem(e, currentSeason(now)) ?: softProblem(e)
            if (problem != null) { faulty += e.id; reasons += problem }
        }
        if (faulty.isNotEmpty()) return fail(p, faulty, reasons.joinToString("; "))
        // 3: the arena is still free (else any other free one).
        val arena = ranked.arena(p.arenaId)?.takeIf { ranked.availability(it) == ArenaAvailability.AVAILABLE && p.size in it.arena.sizes }?.arena
            ?: ranked.availableArenaFor(p.size, book.reservedArenas() - p.arenaId)
            ?: return fail(p, emptySet(), "no arena is free right now")
        for (uuid in p.participants) tell(uuid, text("Everyone accepted! Preparing ${arena.displayName}...", NamedTextColor.GREEN))
        // 4-7: reservation, match record, snapshots, preparation: all the existing match lifecycle.
        val kind = if (p.kind == EntryKind.CHALLENGE) MatchKind.CHALLENGE else MatchKind.PUBLIC
        // Issue #1377: an unexpected error here would leave both entries PREPARING forever (the tick,
        // quits and staff cancels all skip PREPARING), so the teams could never queue again until a
        // restart. Undo any half-created match (restoring its players) and release the entries.
        val result = try {
            ranked.createQueuedMatch(a.teamId, b.teamId, p.size, arena.id, kind, a.participants, b.participants, p.id)
        } catch (e: Exception) {
            plugin.logger.log(java.util.logging.Level.SEVERE, "[RankedQueue] Starting match ${p.id} failed unexpectedly", e)
            p.participants.mapNotNull { ranked.matchOf(it) }.distinct().forEach { ranked.cancel(it, "the match couldn't be started (server error)") }
            return fail(p, emptySet(), "a server error stopped the match from starting")
        }
        when (result) {
            is LifestealRankedManager.CreateResult.Started -> {
                book.matched(p.id, now)
                pausedNotice.remove(a.id); pausedNotice.remove(b.id)
                audit("STARTED", p.id, null, null, "match ${result.match.id} in ${arena.id} (${kind.name})")
            }
            is LifestealRankedManager.CreateResult.Refused -> fail(p, setOf(a.id, b.id), result.problems.joinToString("; ").take(200))
        }
    }

    private fun fail(p: Proposal, faulty: Set<String>, reason: String) {
        val failed = book.startFailed(p.id, faulty, System.currentTimeMillis()) ?: return
        audit("START_FAILED", p.id, null, null, reason)
        for (e in failed.ended) {
            pausedNotice.remove(e.id)
            teamCooldown.start(e.teamId, System.currentTimeMillis(), (settings?.requeueCooldownSeconds ?: 0) * 1000L)
            notifyEntry(e, text("The match couldn't start: $reason. Your ${describe(e)} was cancelled; nothing happened to your inventory or hearts.", NamedTextColor.RED))
        }
        for (e in failed.returned) notifyEntry(e, text("The match couldn't start: $reason. You're back in the ${if (e.kind == EntryKind.CHALLENGE) "challenge" else "queue"} with your place.", NamedTextColor.YELLOW))
        for (e in failed.returned.map { it.size }.distinct()) tryMatch(e)
    }

    // ── Challenges ──────────────────────────────────────────

    fun challenge(player: Player, targetName: String, size: Int, names: List<String>) {
        if (spamming(player)) return
        val team = teams.teamOf(player.uniqueId)
        val target = teams.teamByName(targetName)
        challengeProblem(player, target, size)?.let { return Msg.error(player, it) }
        val own = team ?: return
        val (roster, problem) = resolveRoster(player, own, size, names)
        if (roster == null) return Msg.error(player, problem ?: "Couldn't pick the players.")
        register(player, own, size, roster, EntryKind.CHALLENGE, target)
    }

    fun challengeWith(player: Player, targetTeamId: String, size: Int, roster: List<UUID>) {
        if (spamming(player)) return
        val target = teams.team(targetTeamId)
        challengeProblem(player, target, size)?.let { return Msg.error(player, it) }
        register(player, teams.teamOf(player.uniqueId) ?: return, size, roster, EntryKind.CHALLENGE, target)
    }

    /** Why [player] can't challenge [target] to a [size] match right now (null = they can). */
    fun challengeProblem(player: Player, target: Team?, size: Int, now: Long = System.currentTimeMillis()): String? {
        startProblem(player, size, now)?.let { return it }
        val own = teams.teamOf(player.uniqueId) ?: return "Join a Lifesteal team first."
        if (target == null) return "There's no Lifesteal team by that name."
        if (target.id == own.id) return "You can't challenge your own team."
        seconds(challengeCooldown.remaining(own.id, now)).takeIf { it > 0 }?.let { return "Your team can send another challenge in ${it}s." }
        if (book.pendingChallenge(own.id, target.id) != null) return "You already challenged ${target.name}."
        if (book.entryOfTeam(target.id) != null) return "${target.name} is already in a ranked queue or challenge."
        val online = target.members.count { m -> Bukkit.getPlayer(m.uuid)?.let { it.isOnline && isLifesteal(it) } == true }
        if (online < size) return "${target.name} doesn't have $size players online in Lifesteal."
        return null
    }

    private fun announceChallenge(c: Challenge, target: Team) {
        val s = settings ?: return
        tell(target.leader, text("${c.fromTeamName} challenges ${target.name} to a ${c.size}v${c.size} ranked match (expires in ${s.challengeExpireSeconds}s).", NamedTextColor.GOLD))
        tell(target.leader, riskLines())
        tell(target.leader, Component.text("[Accept]", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
            .clickEvent(ClickEvent.suggestCommand("/ranked challenge accept ${c.fromTeamName} "))
            .hoverEvent(HoverEvent.showText(text("List ${c.size - 1} teammates (you play too) or ${c.size}", NamedTextColor.GRAY)))
            .append(text("  ", NamedTextColor.GRAY))
            .append(button("[Decline]", "/ranked challenge decline ${c.fromTeamName}", NamedTextColor.RED))
            .append(text("  or /ranked > Team Challenges", NamedTextColor.GRAY)))
        for (m in target.members) if (m.uuid != target.leader) tell(m.uuid, text("${c.fromTeamName} challenged your team to a ${c.size}v${c.size} ranked match. Your leader decides who plays; everyone picked has to agree.", NamedTextColor.GRAY))
    }

    fun acceptChallenge(player: Player, fromName: String, names: List<String>) {
        if (spamming(player)) return
        val from = teams.teamByName(fromName) ?: return Msg.error(player, "There's no Lifesteal team called $fromName.")
        val (c, problem) = answerProblem(player, from.id)
        if (c == null) return Msg.error(player, problem ?: STALE)
        val own = teams.teamOf(player.uniqueId) ?: return
        val (roster, rosterProblem) = resolveRoster(player, own, c.size, names)
        if (roster == null) return Msg.error(player, rosterProblem ?: "Couldn't pick the players.")
        registerAnswer(player, own, c, roster)
    }

    fun acceptChallengeWith(player: Player, challengeId: String, roster: List<UUID>) {
        if (spamming(player)) return
        val c = book.challenge(challengeId) ?: return Msg.error(player, STALE)
        val (current, problem) = answerProblem(player, c.fromTeamId)
        if (current == null || current.id != challengeId) return Msg.error(player, problem ?: STALE)
        registerAnswer(player, teams.teamOf(player.uniqueId) ?: return, current, roster)
    }

    /** The open challenge from [fromTeamId] to [player]'s team, or why they can't answer it. */
    fun answerProblem(player: Player, fromTeamId: String, now: Long = System.currentTimeMillis()): Pair<Challenge?, String?> {
        playerProblem(player, now)?.let { return null to it }
        val own = teams.teamOf(player.uniqueId) ?: return null to "Join a Lifesteal team first."
        if (own.leader != player.uniqueId) return null to "Only your team leader can answer challenges."
        val c = book.pendingChallenge(fromTeamId, own.id) ?: return null to "There's no open challenge from that team."
        if (now >= c.expiresAt) return null to "That challenge expired."
        book.entryOfTeam(own.id)?.let { return null to "Your team is already in a ranked queue or challenge; leave it first (/ranked leave)." }
        if (own.size < c.size) return null to "Your team needs at least ${c.size} members."
        if (!ranked.hostsSize(c.size)) return null to "No arena hosts ${c.size}v${c.size} right now."
        return c to null
    }

    private fun registerAnswer(player: Player, own: Team, c: Challenge, roster: List<UUID>) {
        val now = System.currentTimeMillis()
        if (roster.size != c.size || roster.toSet().size != c.size) return Msg.error(player, "A ${c.size}v${c.size} needs exactly ${c.size} different players.")
        val problems = rosterProblems(own, roster, now)
        if (problems.isNotEmpty()) {
            Msg.error(player, "Your team can't accept yet:")
            for (p in problems.take(MAX_LISTED)) plugin.commsManager.send(player, text(" - $p", NamedTextColor.RED))
            return
        }
        val entry = QueueEntry(RankedQueueBook.newId("rq"), EntryKind.CHALLENGE, own.id, own.name, c.size, roster, player.uniqueId, now, currentSeason(now), c.id)
        book.acceptChallenge(c.id, entry, listOf(player.uniqueId), now)?.let { return Msg.error(player, it) }
        audit("CHALLENGE_ACCEPTED", c.id, own.id, player.uniqueId, names(roster))
        book.entry(c.fromEntryId)?.let { notifyEntry(it, text("${own.name} accepted your challenge. Their players are agreeing now.", NamedTextColor.GREEN)) }
        afterRegister(entry, c.fromTeamName)
    }

    fun declineChallenge(player: Player, fromName: String) {
        if (spamming(player)) return
        val from = teams.teamByName(fromName) ?: return Msg.error(player, "There's no Lifesteal team called $fromName.")
        val own = teams.teamOf(player.uniqueId) ?: return Msg.error(player, "Join a Lifesteal team first.")
        if (own.leader != player.uniqueId) return Msg.error(player, "Only your team leader can answer challenges.")
        val c = book.pendingChallenge(from.id, own.id) ?: return Msg.error(player, "There's no open challenge from ${from.name}.")
        val fromEntry = book.entry(c.fromEntryId)
        book.declineChallenge(c.id, System.currentTimeMillis()) ?: return Msg.error(player, STALE)
        audit("CHALLENGE_DECLINED", c.id, own.id, player.uniqueId, null)
        fromEntry?.let {
            teamCooldown.start(it.teamId, System.currentTimeMillis(), (settings?.requeueCooldownSeconds ?: 0) * 1000L)
            notifyEntry(it, text("${own.name} declined your ${c.size}v${c.size} challenge.", NamedTextColor.RED))
        }
        Msg.success(player, "Declined ${from.name}'s challenge.")
    }

    fun cancelChallenge(player: Player) {
        if (spamming(player)) return
        val own = teams.teamOf(player.uniqueId) ?: return Msg.error(player, "Join a Lifesteal team first.")
        if (own.leader != player.uniqueId) return Msg.error(player, "Only your team leader can withdraw a challenge.")
        val c = book.outgoingChallenge(own.id) ?: return Msg.error(player, "Your team has no open challenge.")
        val entry = book.entry(c.fromEntryId) ?: return Msg.error(player, STALE)
        if (entry.state == QueueState.PREPARING) return Msg.error(player, "The match is already being set up.")
        audit("CHALLENGE_WITHDRAWN", c.id, own.id, player.uniqueId, null)
        end(entry, QueueState.CANCELLED, "${own.name} withdrew the challenge", if (entry.state == QueueState.CONFIRMING && player.uniqueId in entry.participants) player.uniqueId else null)
        Msg.success(player, "Challenge withdrawn.")
    }

    // ── Events ──────────────────────────────────────────────

    /** A participant disconnected: their team's entry ends (a running match is the match manager's job). */
    fun onQuit(player: Player) {
        tests.onQuit(player)
        val uuid = player.uniqueId
        val entry = book.entryOf(uuid) ?: return
        if (entry.state == QueueState.PREPARING || entry.state.terminal) return
        audit("DISCONNECT", entry.proposalId ?: entry.id, entry.teamId, uuid, entry.state.name)
        end(entry, QueueState.CANCELLED, "${player.name} disconnected", if (entry.state == QueueState.CONFIRMING) uuid else null)
    }

    /** Once a second: timeouts, revalidation (team changes, combat tags, ...), matchmaking, reminders. */
    private fun tick() {
        ticks++
        val now = System.currentTimeMillis()
        if (ticks % 60L == 0L) { teamCooldown.sweep(now); playerCooldown.sweep(now); challengeCooldown.sweep(now); actionSpam.sweep(now) }
        // Staff test matches also run while matchmaking is closed to players.
        tests.tick(now, reminders = ticks % 5L == 0L)
        if (book.isEmpty) return
        val s = settings
        if (s == null || !systemReady) return cancelAll("ranked matchmaking became unavailable")
        if (!plugin.lifestealProgressionManager.canEnterRankedQueue(now)) return cancelAll("Ranked Team Wars are locked right now")

        val expiry = book.expire(now, s.consentSeconds * 1000L, s.queueTimeoutMinutes * 60_000L)
        for (u in expiry.ignoredBy) playerCooldown.start(u, now, s.declineCooldownSeconds * 1000L)
        for (ended in expiry.ended) handleEnded(ended, "time ran out")

        val season = currentSeason(now)
        for (e in book.allEntries()) {
            if (e.state == QueueState.PREPARING || e.state.terminal) continue
            val hard = hardProblem(e, season)
            if (hard != null) { end(e, QueueState.CANCELLED, hard, null); continue }
            if (e.state != QueueState.WAITING) continue
            val soft = softProblem(e)
            e.problem = soft
            if (soft == null) pausedNotice.remove(e.id)
            else if (pausedNotice[e.id] != soft) {
                pausedNotice[e.id] = soft
                notifyEntry(e, text("Matchmaking is paused for your team: $soft.", NamedTextColor.YELLOW))
            }
        }

        for (size in sizes) tryMatch(size)
        tryChallenges()

        if (ticks % 5L == 0L) for (p in book.allProposals()) {
            if (p.state != ProposalState.CONFIRMING) continue
            val left = seconds(p.expiresAt - now)
            for (uuid in p.waitingFor) actionBar(uuid, text("Ranked match found! /ranked accept (${left}s)", NamedTextColor.GOLD))
        }
    }

    // ── Staff ───────────────────────────────────────────────

    /** Ends a queue entry (by id or team name) for staff; a match being created can't be cancelled here. */
    fun adminCancelEntry(actor: String, idOrTeam: String): String? {
        val entry = book.entry(idOrTeam) ?: teams.teamByName(idOrTeam)?.let { book.entryOfTeam(it.id) } ?: return "No queue entry '$idOrTeam'."
        if (entry.state == QueueState.PREPARING) return "That entry's match is being created right now; use /lifesteal admin ranked cancel <match> once it exists."
        audit("ADMIN_CANCEL", entry.id, entry.teamId, null, "by $actor")
        plugin.logger.warning("[RankedQueue] $actor cancelled queue entry ${entry.id} (${entry.teamName}).")
        end(entry, QueueState.CANCELLED, "cancelled by staff", null)
        return null
    }

    /** Ends a found match and both of its entries (players can queue again at once). */
    fun adminCancelProposal(sender: org.bukkit.command.CommandSender, id: String): String? {
        if (TestMatchBook.isProposalToken(id)) return tests.adminCancel(sender, id)
        val actor = sender.name
        val p = book.proposal(id) ?: return "No open match proposal '$id'."
        if (p.state != ProposalState.CONFIRMING) return "That proposal is ${p.state.name.lowercase()}."
        audit("ADMIN_CANCEL", p.id, null, null, "by $actor")
        plugin.logger.warning("[RankedQueue] $actor cancelled match proposal ${p.id}.")
        val failed = book.startFailed(p.id, setOf(p.entryA, p.entryB), System.currentTimeMillis()) ?: return "That proposal already ended."
        for (e in failed.ended) notifyEntry(e, text("Staff cancelled your found match. Nothing happened to your inventory or hearts; you can queue again.", NamedTextColor.RED))
        return null
    }

    // ── Messages ────────────────────────────────────────────

    fun describe(e: QueueEntry): String = "${e.size}v${e.size} ranked ${if (e.kind == EntryKind.CHALLENGE) "challenge" else "queue"}"

    private fun riskLines(): Component {
        val limits = plugin.lifestealHeartManager.limits
        val range = limits?.let { " (${it.minimum}-${it.maximum} hearts)" } ?: ""
        return text("Hearts are at risk: normal Lifesteal heart stealing applies$range. ", NamedTextColor.RED)
            .append(text("You fight with your own gear; your inventory and equipment are saved and given back after the match.", NamedTextColor.GRAY))
    }

    private fun notifyEntry(e: QueueEntry, message: Component) {
        val targets = LinkedHashSet(e.participants)
        targets += e.initiator
        for (uuid in targets) tell(uuid, message)
    }

    private fun tell(uuid: UUID, message: Component) {
        val player = Bukkit.getPlayer(uuid) ?: return
        if (player.isOnline) plugin.commsManager.send(player, PREFIX.append(message))
    }

    private fun actionBar(uuid: UUID, message: Component) {
        val player = Bukkit.getPlayer(uuid) ?: return
        if (player.isOnline) plugin.commsManager.sendActionBar(player, message)
    }

    private fun names(uuids: Collection<UUID>): String = uuids.joinToString { teams.nameOf(it) }

    private fun audit(kind: String, ref: String?, teamId: String?, uuid: UUID?, detail: String?) {
        if (!tablesReady) return
        val at = System.currentTimeMillis()
        try {
            plugin.databaseManager.async("ranked queue audit") { store.log(at, kind, ref, teamId, uuid, detail) }
        } catch (_: Exception) { }
    }

    companion object {
        const val PERMISSION_USE = "joshymc.lifesteal.ranked.use"
        const val ACTION_COOLDOWN_MS = 400L
        const val COOLDOWN_CAPACITY = 2000
        const val MAX_LISTED = 8
        const val STALE = "That ranked request is no longer valid (it was answered, replaced or expired)."
        /** Issue #1369: shown while `ranked-team-wars.enabled` is false. */
        const val CLOSED_MESSAGE = "Ranked Team Wars aren't open yet. Staff will announce when they start."
        const val LOCKED_MESSAGE = "Ranked Team Wars haven't unlocked for this season yet; they open on Day 3 (/lifesteal progress shows when)."
        private val PREFIX: Component = Component.text("[Ranked] ", NamedTextColor.GOLD)

        fun text(content: String, color: NamedTextColor): Component = Component.text(content, color)

        fun button(label: String, command: String, color: NamedTextColor): Component =
            Component.text(label, color).decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)))

        fun seconds(millis: Long): Long = if (millis <= 0) 0 else (millis + 999) / 1000
    }
}
