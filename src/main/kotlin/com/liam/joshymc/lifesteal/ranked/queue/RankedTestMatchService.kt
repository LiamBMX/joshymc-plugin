package com.liam.joshymc.lifesteal.ranked.queue

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.ranked.LifestealRankedManager
import com.liam.joshymc.lifesteal.ranked.queue.LifestealRankedQueueManager.Companion.button
import com.liam.joshymc.lifesteal.ranked.queue.LifestealRankedQueueManager.Companion.seconds
import com.liam.joshymc.lifesteal.ranked.queue.LifestealRankedQueueManager.Companion.text
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Team
import com.liam.joshymc.staff.AdminAudit
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Issue #1491 (F-3): the staff TEST match flow on top of [TestMatchBook]. Owned by
 * [LifestealRankedQueueManager], which hands it `/ranked accept|decline` for test proposals and
 * ticks it every second. A TEST match is created only through
 * [LifestealRankedManager.createTestMatch], after the staff member confirmed and every participant
 * accepted; that call runs the same validation, snapshots and lifecycle as every other match, and
 * hearts move under the normal heart rules (TEST matches stay unrated).
 *
 * Every step is written to `admin_logs` through [AdminAudit] (staff actions) or
 * `adminManager.logAction` (player answers and system outcomes), each row carrying the proposal id
 * and the initiating staff member.
 */
class RankedTestMatchService(private val plugin: Joshymc, private val queue: LifestealRankedQueueManager) {

    private val book = TestMatchBook()
    private val ranked get() = plugin.lifestealRankedManager
    private val teams get() = plugin.lifestealTeamManager

    // ── Read API ────────────────────────────────────────────

    fun proposalOf(uuid: UUID): TestProposal? = book.proposalOf(uuid)
    fun proposals(): List<TestProposal> = book.allProposals()
    fun busy(uuid: UUID): Boolean = book.proposalOf(uuid) != null

    /** A `/ranked accept|decline` for this service: the player is in a test proposal, or the token is one. */
    fun handles(uuid: UUID, token: String?): Boolean = busy(uuid) || TestMatchBook.isProposalToken(token)

    private fun consentSeconds(): Long = (queue.settings?.consentSeconds ?: 60).toLong()

    private fun actorOf(sender: CommandSender): String = (sender as? Player)?.uniqueId?.toString() ?: TestMatchRules.CONSOLE_ACTOR

    /** The live sender behind [actor], or null when that staff member is offline. */
    private fun senderOf(actor: String): CommandSender? =
        if (actor == TestMatchRules.CONSOLE_ACTOR) Bukkit.getConsoleSender()
        else runCatching { UUID.fromString(actor) }.getOrNull()?.let { Bukkit.getPlayer(it) }?.takeIf { it.isOnline }

    // ── Plans and checks ────────────────────────────────────

    /** The first [size] online members of each team (leader first, then by join time), as before. */
    private fun plan(teamA: Team?, teamB: Team?, size: Int, arenaId: String?): TestMatchPlan? {
        if (teamA == null || teamB == null) return null
        fun online(t: Team) = t.members.mapNotNull { m -> Bukkit.getPlayer(m.uuid)?.takeIf { it.isOnline }?.uniqueId }.take(size)
        return TestMatchPlan(teamA.id, teamA.name, teamB.id, teamB.name, size, arenaId?.let { ranked.arena(it)?.arena?.id ?: it }, online(teamA), online(teamB))
    }

    /** Every reason [sender] can't run [plan] right now (permission, hierarchy, eligibility, arena, unlock). */
    private fun problems(sender: CommandSender, plan: TestMatchPlan, ownProposal: String? = null): List<String> {
        val out = mutableListOf<String>()
        if (!ranked.active) out += "Ranked Team Wars are unavailable (lifesteal.yml invalid or the database failed)."
        if (!ranked.rankedUnlocked()) out += "Ranked Team Wars haven't unlocked for this season yet."
        if (plan.arenaId != null && ranked.arena(plan.arenaId) == null) out += "No arena called '${plan.arenaId}'."
        if (plan.size !in queue.allowedSizes && queue.allowedSizes.isNotEmpty()) out += "${plan.size}v${plan.size} isn't an allowed size (${queue.allowedSizes.joinToString { "${it}v$it" }})."
        if (!ranked.hostsSize(plan.size)) out += "No enabled arena hosts ${plan.size}v${plan.size}."
        out += TestMatchRules.refusals(
            plan,
            hasPermission = sender.hasPermission(LifestealRankedManager.ADMIN_PERMISSION),
            protection = { uuid -> plugin.staffRankManager.protectionProblem(sender, uuid) },
            participant = { uuid -> participantProblem(uuid, plan, ownProposal) },
            name = teams::nameOf,
        )
        return out
    }

    /** Normal ranked eligibility of one participant, plus "not already in a queue, challenge or other test". */
    private fun participantProblem(uuid: UUID, plan: TestMatchPlan, ownProposal: String? = null): String? {
        val player = Bukkit.getPlayer(uuid)?.takeIf { it.isOnline } ?: return "isn't online"
        val team = plan.sideOf(uuid)?.let { teams.team(it) } ?: return "isn't on either team any more"
        if (teams.teamOf(uuid)?.id != team.id) return "is no longer in ${team.name}"
        if (queue.entryOf(uuid) != null) return "is in a ranked queue or challenge"
        book.proposalOf(uuid)?.takeIf { it.id != ownProposal }?.let { return "is already asked to play another test match" }
        return ranked.participantProblem(player, team)
    }

    // ── Staff: request and confirm ──────────────────────────

    /** `/lifesteal admin ranked testmatch <teamA> <teamB> <size> [arena]`: preview + confirmation token, nothing else. */
    fun request(sender: CommandSender, teamAName: String, teamBName: String, size: Int, arenaId: String?) {
        if (!sender.hasPermission(LifestealRankedManager.ADMIN_PERMISSION)) return Msg.noPermission(sender)
        val teamA = teams.teamByName(teamAName) ?: return Msg.error(sender, "No Lifesteal team called '$teamAName'.")
        val teamB = teams.teamByName(teamBName) ?: return Msg.error(sender, "No Lifesteal team called '$teamBName'.")
        val plan = plan(teamA, teamB, size, arenaId) ?: return Msg.error(sender, "A team couldn't be found.")
        val problems = problems(sender, plan)
        if (problems.isNotEmpty()) {
            Msg.error(sender, "The test match can't be set up:")
            for (p in problems.take(LifestealRankedQueueManager.MAX_LISTED)) sender.sendMessage(Component.text(" - $p", NamedTextColor.RED))
            return
        }
        val now = System.currentTimeMillis()
        val c = book.request(actorOf(sender), plan, RankedQueueBook.newId("rx"), now, TestMatchRules.CONFIRM_MILLIS)
        for (line in TestMatchRules.previewLines(plan, teams::nameOf, consentSeconds())) sender.sendMessage(Component.text(line, NamedTextColor.YELLOW))
        sender.sendMessage(
            Component.text("Confirm within ${TestMatchRules.CONFIRM_MILLIS / 1000}s: ", NamedTextColor.GRAY)
                .append(button("[Confirm]", "/lifesteal admin ranked testmatch confirm ${c.id}", NamedTextColor.GREEN))
                .append(Component.text(" (players are only asked after you confirm; nobody is moved until all of them accept)", NamedTextColor.GRAY))
        )
        AdminAudit.log(plugin, sender, "RANKED_TESTMATCH_REQUESTED", details = "confirmation=${c.id} ${plan.describe()}".take(500))
    }

    /** `/lifesteal admin ranked testmatch confirm <token>`: rebuilds the plan, rechecks it, then asks every participant. */
    fun confirm(sender: CommandSender, token: String) {
        if (!sender.hasPermission(LifestealRankedManager.ADMIN_PERMISSION)) return Msg.noPermission(sender)
        val now = System.currentTimeMillis()
        val pending = book.confirmation(token)
        val current = pending?.let { c -> plan(teams.team(c.plan.teamAId), teams.team(c.plan.teamBId), c.plan.size, c.plan.arenaId) }
        // Problems are checked before the book spends the token, so a refusal explains itself.
        val problems = if (pending != null && pending.actor == actorOf(sender) && current != null && pending.plan.sameAs(current)) problems(sender, current) else emptyList()
        if (problems.isNotEmpty()) {
            book.confirm(actorOf(sender), sender.name, token, null, "", now, 0) // spend it
            Msg.error(sender, "The test match can't be set up any more (run the command again):")
            for (p in problems.take(LifestealRankedQueueManager.MAX_LISTED)) sender.sendMessage(Component.text(" - $p", NamedTextColor.RED))
            return
        }
        when (val r = book.confirm(actorOf(sender), sender.name, token, current, RankedQueueBook.newId("rt"), now, consentSeconds() * 1000L)) {
            TestMatchBook.ConfirmResult.NotFound -> Msg.error(sender, "That confirmation doesn't exist (already used, replaced or expired). Run the testmatch command again.")
            TestMatchBook.ConfirmResult.NotYours -> Msg.error(sender, "That confirmation belongs to another staff member.")
            TestMatchBook.ConfirmResult.Expired -> Msg.error(sender, "That confirmation expired. Run the testmatch command again.")
            TestMatchBook.ConfirmResult.Changed -> Msg.error(sender, "The teams or their online players changed since the preview, so it was not confirmed. Run the testmatch command again.")
            is TestMatchBook.ConfirmResult.Busy -> Msg.error(sender, "${teams.nameOf(r.uuid)} is already asked to play another test match.")
            TestMatchBook.ConfirmResult.Full -> Msg.error(sender, "Too many test matches are waiting for players. Try again later.")
            is TestMatchBook.ConfirmResult.Confirmed -> {
                val p = r.proposal
                AdminAudit.log(plugin, sender, "RANKED_TESTMATCH_CONFIRMED", details = "proposal=${p.id} confirmation=$token ${p.plan.describe()}".take(500))
                Msg.success(sender, "Confirmed. ${p.participants.size} players were asked to accept test match ${p.id} (${consentSeconds()}s). Nobody is moved unless all of them accept.")
                ask(p)
            }
        }
    }

    private fun ask(p: TestProposal) {
        val limits = plugin.lifestealHeartManager.limits
        val lines = TestMatchRules.consentLines(p.plan, p.actorName, limits?.let { it.minimum..it.maximum }, consentSeconds())
        for (uuid in p.participants) {
            tell(uuid, text(lines[0], NamedTextColor.GOLD))
            tell(uuid, text(lines[1], NamedTextColor.RED))
            for (line in lines.drop(2)) tell(uuid, text(line, NamedTextColor.GRAY))
            tell(uuid, buttons(p))
        }
    }

    private fun buttons(p: TestProposal): Component =
        button("[Accept]", "/ranked accept ${p.id}", NamedTextColor.GREEN)
            .append(text("  ", NamedTextColor.GRAY))
            .append(button("[Decline]", "/ranked decline ${p.id}", NamedTextColor.RED))
            .append(text("  ${seconds(p.expiresAt - System.currentTimeMillis())}s left", NamedTextColor.GRAY))

    // ── Participants ────────────────────────────────────────

    fun accept(player: Player, token: String?) {
        val now = System.currentTimeMillis()
        // Accepting never skips eligibility: a player who can't play right now can't accept (the request stays open).
        book.proposalOf(player.uniqueId)?.takeIf { token == it.id && it.state == TestProposalState.CONFIRMING && player.uniqueId !in it.accepted }?.let { p ->
            participantProblem(player.uniqueId, p.plan, p.id)?.let { problem ->
                return Msg.error(player, "You can't accept right now: $problem. The request stays open until it times out.")
            }
        }
        when (val r = book.accept(player.uniqueId, token, now)) {
            TestMatchBook.AcceptResult.NothingToAccept -> Msg.error(player, "You have no ranked request to accept.")
            TestMatchBook.AcceptResult.Stale -> Msg.error(player, LifestealRankedQueueManager.STALE)
            TestMatchBook.AcceptResult.Expired -> Msg.error(player, "Too late: that test match request expired.")
            TestMatchBook.AcceptResult.Already -> Msg.reply(player, text("You already accepted; waiting for the others.", NamedTextColor.GRAY))
            is TestMatchBook.AcceptResult.NeedsToken -> {
                // Consent is to one exact proposal: show it again with its own buttons.
                val p = r.proposal
                val limits = plugin.lifestealHeartManager.limits
                for (line in TestMatchRules.consentLines(p.plan, p.actorName, limits?.let { it.minimum..it.maximum }, seconds(p.expiresAt - now))) {
                    tell(player.uniqueId, text(line, NamedTextColor.YELLOW))
                }
                tell(player.uniqueId, buttons(p))
            }
            is TestMatchBook.AcceptResult.Accepted -> {
                val p = r.proposal
                logPlayer(player, "RANKED_TESTMATCH_ACCEPTED", p, "accepted=${p.accepted.size}/${p.participants.size}")
                for (uuid in p.participants) tell(uuid, text("${player.name} accepted the test match (${p.accepted.size}/${p.participants.size}).", NamedTextColor.GREEN))
                if (p.complete) start(p)
            }
        }
    }

    fun decline(player: Player, token: String?) {
        val p = book.proposalOf(player.uniqueId) ?: return Msg.error(player, LifestealRankedQueueManager.STALE)
        if (token != null && token != p.id) return Msg.error(player, LifestealRankedQueueManager.STALE)
        if (p.state != TestProposalState.CONFIRMING) return Msg.error(player, "The match is already being set up.")
        cancel(p, TestProposalState.DECLINED, "${player.name} declined", player)
        Msg.success(player, "Declined. Nothing happened to your inventory or hearts.")
    }

    fun onQuit(player: Player) {
        val p = book.proposalOf(player.uniqueId) ?: return
        if (p.state != TestProposalState.CONFIRMING) return
        cancel(p, TestProposalState.CANCELLED, "${player.name} disconnected", player)
    }

    // ── Start ───────────────────────────────────────────────

    private fun start(proposal: TestProposal) {
        val now = System.currentTimeMillis()
        val p = book.beginStart(proposal.id, now) ?: return
        val staff = senderOf(p.actor)
        val problems = if (staff == null) listOf("the staff member who set it up went offline")
        else {
            val current = plan(teams.team(p.plan.teamAId), teams.team(p.plan.teamBId), p.plan.size, p.plan.arenaId)
            // The exact accepted players: nobody joins or swaps in after the consent.
            buildList {
                if (current == null) add("a team no longer exists")
                addAll(problems(staff, p.plan, p.id).map { it.trimEnd('.') })
            }.distinct()
        }
        if (problems.isNotEmpty()) return failStart(p, problems.joinToString("; ").take(300))
        for (uuid in p.participants) tell(uuid, text("Everyone accepted the test match. Preparing the arena...", NamedTextColor.GREEN))
        val result = try {
            ranked.createTestMatch(p.actor, p.actorName, p.plan, p.id)
        } catch (e: Exception) {
            plugin.logger.log(java.util.logging.Level.SEVERE, "[RankedTest] Starting test match ${p.id} failed unexpectedly", e)
            p.participants.mapNotNull { ranked.matchOf(it) }.distinct().forEach { ranked.cancel(it, "the match couldn't be started (server error)") }
            return failStart(p, "a server error stopped the match from starting")
        }
        when (result) {
            is LifestealRankedManager.CreateResult.Started -> {
                book.started(p.id)
                logSystem("RANKED_TESTMATCH_CREATED", p, "match=${result.match.id} arena=${result.match.arena.id}")
                staff?.let { Msg.success(it, "Test match ${result.match.id} started: ${result.match.label} in ${result.match.arena.id}.") }
            }
            is LifestealRankedManager.CreateResult.Refused -> failStart(p, result.problems.joinToString("; ").take(300))
        }
    }

    private fun failStart(p: TestProposal, reason: String) {
        book.end(p.id, TestProposalState.CANCELLED) ?: return
        logSystem("RANKED_TESTMATCH_CANCELLED", p, "reason=couldn't start: $reason")
        notifyAll(p, text("The test match couldn't start: $reason. Nothing happened to your inventory or hearts.", NamedTextColor.RED))
    }

    // ── Ending, timeouts ────────────────────────────────────

    private fun cancel(p: TestProposal, state: TestProposalState, reason: String, by: Player?) {
        book.end(p.id, state) ?: return
        val action = when (state) {
            TestProposalState.DECLINED -> "RANKED_TESTMATCH_DECLINED"
            TestProposalState.EXPIRED -> "RANKED_TESTMATCH_EXPIRED"
            else -> "RANKED_TESTMATCH_CANCELLED"
        }
        if (by != null) logPlayer(by, action, p, "reason=$reason") else logSystem(action, p, "reason=$reason")
        notifyAll(p, text("The test match ${if (state == TestProposalState.EXPIRED) "expired" else "was cancelled"}: $reason. Nothing happened to your inventory or hearts.", NamedTextColor.RED))
    }

    /** `/lifesteal admin ranked cancelproposal rt-...`. */
    fun adminCancel(sender: CommandSender, id: String): String? {
        val p = book.proposal(id) ?: return "No open test match proposal '$id'."
        if (p.state != TestProposalState.CONFIRMING) return "That proposal is ${p.state.name.lowercase()}."
        book.end(p.id, TestProposalState.CANCELLED) ?: return "That proposal already ended."
        AdminAudit.log(plugin, sender, "RANKED_TESTMATCH_CANCELLED", details = "proposal=${p.id} initiator=${p.actor} reason=cancelled by staff ${p.plan.describe()}".take(500))
        notifyAll(p, text("Staff cancelled the test match. Nothing happened to your inventory or hearts.", NamedTextColor.RED))
        return null
    }

    /** Once a second, from the queue's tick (also while matchmaking is closed to players). */
    fun tick(now: Long, reminders: Boolean) {
        if (book.isEmpty) return
        if (!ranked.active) return clear("Ranked Team Wars became unavailable")
        for (p in book.expire(now)) {
            logSystem("RANKED_TESTMATCH_EXPIRED", p, "reason=not everyone accepted in time; missing=${p.waitingFor.joinToString(",")}")
            notifyAll(p, text("The test match expired: not everyone accepted in time. Nothing happened to your inventory or hearts.", NamedTextColor.RED))
        }
        for (p in book.allProposals()) {
            if (p.state != TestProposalState.CONFIRMING) continue
            // Disconnects, team changes and other matches end the proposal at once.
            val gone = p.participants.firstNotNullOfOrNull { uuid ->
                val name = teams.nameOf(uuid)
                when {
                    Bukkit.getPlayer(uuid)?.isOnline != true -> "$name went offline"
                    teams.teamOf(uuid)?.id != p.plan.sideOf(uuid) -> "$name changed teams"
                    ranked.holds(uuid) || ranked.hasPendingRestore(uuid) -> "$name is in another ranked match"
                    queue.entryOf(uuid) != null -> "$name joined a ranked queue"
                    else -> null
                }
            }
            if (gone != null) { cancel(p, TestProposalState.CANCELLED, gone, null); continue }
            if (reminders) for (uuid in p.waitingFor) {
                Bukkit.getPlayer(uuid)?.let { plugin.commsManager.sendActionBar(it, text("Staff TEST match: hearts at risk. /ranked accept ${p.id} (${seconds(p.expiresAt - now)}s)", NamedTextColor.GOLD)) }
            }
        }
    }

    /** Reload / shutdown: every confirmation and proposal ends (nothing was touched yet). */
    fun clear(reason: String) {
        for (p in book.clear()) {
            logSystem("RANKED_TESTMATCH_CANCELLED", p, "reason=$reason")
            notifyAll(p, text("The test match was cancelled: $reason. Nothing happened to your inventory or hearts.", NamedTextColor.RED))
        }
    }

    // ── Messages and audit ──────────────────────────────────

    private fun notifyAll(p: TestProposal, message: Component) {
        for (uuid in p.participants) tell(uuid, message)
        senderOf(p.actor)?.takeIf { it is Player && it.uniqueId !in p.participants }?.let { tell((it as Player).uniqueId, message) }
    }

    private fun tell(uuid: UUID, message: Component) {
        val player = Bukkit.getPlayer(uuid)?.takeIf { it.isOnline } ?: return
        plugin.commsManager.send(player, PREFIX.append(message))
    }

    private fun details(p: TestProposal, extra: String) = "proposal=${p.id} initiator=${p.actor} ($extra) ${p.plan.describe()}".take(500)

    private fun logPlayer(player: Player, action: String, p: TestProposal, extra: String) =
        AdminAudit.log(plugin, player, action, player.uniqueId, player.name, details(p, extra))

    private fun logSystem(action: String, p: TestProposal, extra: String) {
        try {
            plugin.adminManager.logAction(null, SYSTEM_ACTOR, action, null, null, details(p, extra))
        } catch (e: Exception) {
            plugin.logger.warning("[RankedTest] Couldn't store $action for ${p.id}: ${e.message}")
        }
        plugin.logger.info("[RankedTest] $action ${details(p, extra)}")
    }

    companion object {
        const val SYSTEM_ACTOR = "Lifesteal Ranked"
        private val PREFIX: Component = Component.text("[Ranked] ", NamedTextColor.GOLD)
    }
}
