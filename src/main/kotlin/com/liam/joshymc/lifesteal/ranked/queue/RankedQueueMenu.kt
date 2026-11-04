package com.liam.joshymc.lifesteal.ranked.queue

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.lifesteal.ranked.queue.LifestealRankedQueueManager.Companion.seconds
import com.liam.joshymc.lifesteal.ranked.queue.LifestealRankedQueueManager.Companion.text
import com.liam.joshymc.lifesteal.ranked.rating.LifestealRatingManager
import com.liam.joshymc.lifesteal.ranked.rating.RatingType
import com.liam.joshymc.lifesteal.ranked.stats.RankedMenuLayout
import com.liam.joshymc.lifesteal.ranked.stats.RankedStatsMenu
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Team
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

/**
 * The Ranked Team Wars menu (issue #1363), `/ranked`. Plain chest GUIs through the shared
 * [com.liam.joshymc.gui.GuiManager] (every click on the menu is cancelled and nothing can be moved
 * in or out), styled with [GuiStyle], no new resource pack assets; Bedrock players get the same
 * chests through Geyser, which is also why Accept/Decline are buttons here and not only clickable
 * chat.
 *
 * **No button is trusted**: every click calls the same [LifestealRankedQueueManager] action as the
 * command, which checks permission, leadership, the season unlock and every player again. Accept
 * and Decline carry the id of the request the menu showed, so a stale menu can't answer a newer
 * request.
 *
 * Issue #1365: slot 4 shows the player's Player Elo and Team Elo from
 * [com.liam.joshymc.lifesteal.ranked.rating.LifestealRatingManager.cachedProfile] (`/ranked` loads it
 * first, off the main thread); the menu never calculates or changes a rating.
 */
class RankedQueueMenu(private val plugin: Joshymc) {

    private val queue get() = plugin.lifestealRankedQueueManager
    private val teams get() = plugin.lifestealTeamManager

    fun open(player: Player) {
        val now = System.currentTimeMillis()
        val gui = CustomGui(title("Ranked Team Wars"), RankedMenuLayout.MAIN_SIZE)
        val team = teams.teamOf(player.uniqueId)
        val entry = queue.entryOf(player.uniqueId) ?: team?.takeIf { it.leader == player.uniqueId }?.let { queue.entryOfTeam(it.id) }
        val blocked = queue.playerProblem(player, now)
        val eligible = team?.let { queue.eligibleTeammates(it) }.orEmpty()

        for ((i, size) in (2..5).withIndex()) {
            val slot = 10 + i
            if (size !in queue.allowedSizes) {
                gui.setItem(slot, GuiStyle.item(Material.GRAY_DYE, text("${size}v$size Ranked", NamedTextColor.DARK_GRAY), listOf(text("Not offered on this server.", NamedTextColor.GRAY))))
                continue
            }
            val problem = queue.startProblem(player, size, now)
            val lore = mutableListOf(
                text("Required: $size players from your team", NamedTextColor.GRAY),
                text("Eligible teammates online: ${eligible.size}/$size", if (eligible.size >= size) NamedTextColor.GREEN else NamedTextColor.RED),
                text("Teams waiting: ${queue.waitingCount(size)}", NamedTextColor.GRAY),
            )
            if (entry != null && entry.size == size) lore += text("Your team: ${entry.state.label}", NamedTextColor.YELLOW)
            lore += Component.empty()
            lore += when {
                problem != null -> text(problem, NamedTextColor.RED)
                eligible.size < size -> text("Not enough eligible teammates online.", NamedTextColor.RED)
                else -> text("Click to pick who plays", NamedTextColor.YELLOW)
            }
            gui.setItem(slot, GuiStyle.item(SIZE_ICONS[i], text("${size}v$size Ranked", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), lore)) { p, _ ->
                val current = queue.startProblem(p, size)
                if (current != null) { GuiStyle.errorSound(p); Msg.error(p, current); return@setItem }
                val t = teams.teamOf(p.uniqueId) ?: return@setItem GuiStyle.errorSound(p)
                GuiStyle.clickSound(p)
                openPicker(p, t, Pick.Queue(size), emptySet())
            }
        }

        // Issue #1365: the player's ratings (from the cache /ranked just filled; never computed here).
        gui.setItem(4, ratingItem(player)) { p, _ ->
            GuiStyle.clickSound(p)
            p.closeInventory()
            p.performCommand("ranked stats")
        }

        gui.setItem(15, statusItem(player, entry, blocked, now)) { p, _ ->
            GuiStyle.clickSound(p)
            p.closeInventory()
            p.performCommand("ranked status")
        }
        gui.setItem(16, GuiStyle.item(Material.SHIELD, text("Team Challenges", NamedTextColor.AQUA).decorate(TextDecoration.BOLD), listOf(
            text("Challenge another team directly,", NamedTextColor.GRAY),
            text("or answer challenges sent to yours.", NamedTextColor.GRAY),
            team?.let { text("Open challenges for your team: ${queue.incomingChallenges(it.id).size}", NamedTextColor.YELLOW) } ?: text("Join a team first.", NamedTextColor.RED),
        ))) { p, _ ->
            GuiStyle.clickSound(p)
            openChallenges(p)
        }

        // Answer whatever is waiting for this player, bound to that request's id.
        val proposal = entry?.proposalId?.let { queue.proposal(it) }
        val token = when {
            proposal != null && player.uniqueId in proposal.waitingFor -> proposal.id
            entry != null && entry.state == QueueState.FORMING && player.uniqueId in entry.pendingConsent -> entry.id
            else -> null
        }
        if (token != null) {
            gui.setItem(19, GuiStyle.item(Material.LIME_DYE, text(if (proposal != null) "Accept Match" else "Agree to Play", NamedTextColor.GREEN).decorate(TextDecoration.BOLD), listOf(
                text("Hearts are at risk (normal Lifesteal rules).", NamedTextColor.RED),
                text("Your gear is given back after the match.", NamedTextColor.GRAY),
            ))) { p, _ ->
                GuiStyle.clickSound(p)
                p.closeInventory()
                queue.accept(p, token)
            }
            gui.setItem(20, GuiStyle.item(Material.RED_DYE, text("Decline", NamedTextColor.RED).decorate(TextDecoration.BOLD))) { p, _ ->
                GuiStyle.clickSound(p)
                p.closeInventory()
                queue.decline(p, token)
            }
        }

        gui.setItem(GuiStyle.closeSlot(RankedMenuLayout.MAIN_SIZE), GuiStyle.close()) { p, _ -> p.closeInventory() }
        statsRow(gui, player, team)
        gui.setItem(24, GuiStyle.item(if (entry != null) Material.RED_CONCRETE else Material.GRAY_CONCRETE, text("Leave Queue", if (entry != null) NamedTextColor.RED else NamedTextColor.GRAY), listOf(
            text(if (entry != null) "Withdraw your team's ${queue.describe(entry)}." else "You aren't queued.", NamedTextColor.GRAY),
        ))) { p, _ ->
            if (queue.entryOf(p.uniqueId) == null && teams.teamOf(p.uniqueId)?.let { queue.entryOfTeam(it.id) } == null) return@setItem GuiStyle.errorSound(p)
            GuiStyle.clickSound(p)
            p.closeInventory()
            queue.leave(p)
        }
        plugin.guiManager.open(player, gui)
    }

    /**
     * Issue #1367: the statistics row. Each button only opens a read-only view, which loads its
     * data off the main thread and checks permission and Lifesteal again when clicked.
     */
    private fun statsRow(gui: CustomGui, player: Player, team: Team?) {
        val menu = RankedStatsMenu(plugin)
        fun hint(vararg lines: String) = lines.map { text(it, NamedTextColor.GRAY) } + Component.empty()
        gui.setItem(RankedMenuLayout.PLAYER_STATS, GuiStyle.skull(player, text("Your Statistics", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
            hint("Player Elo, division, record,", "streaks and ranking.") + text("Click to view", NamedTextColor.YELLOW))) { p, _ ->
            GuiStyle.clickSound(p); menu.openPlayerStats(p, p.uniqueId)
        }
        gui.setItem(RankedMenuLayout.TEAM_STATS, GuiStyle.item(Material.WHITE_BANNER, text("Team Statistics", NamedTextColor.AQUA).decorate(TextDecoration.BOLD),
            hint("Your team's own Team Elo,", "division, record and ranking.") + (if (team != null) text("Click to view", NamedTextColor.YELLOW) else text("Join a team first.", NamedTextColor.RED)))) { p, _ ->
            val t = teams.teamOf(p.uniqueId) ?: run { GuiStyle.errorSound(p); Msg.error(p, "Join a Lifesteal team first."); return@setItem }
            GuiStyle.clickSound(p); menu.openTeamStats(p, t.id)
        }
        gui.setItem(RankedMenuLayout.HISTORY, GuiStyle.item(Material.BOOK, text("Match History", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
            hint("Your ranked results and", "Elo changes, newest first.") + text("Click to view", NamedTextColor.YELLOW))) { p, _ ->
            GuiStyle.pageSound(p); menu.openPlayerHistory(p, p.uniqueId, 0)
        }
        gui.setItem(RankedMenuLayout.SEASON, GuiStyle.item(Material.CLOCK, text("Current Season", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
            menu.seasonLore(player) + Component.empty() + text("Click for details", NamedTextColor.YELLOW))) { p, _ ->
            GuiStyle.clickSound(p); menu.openSeason(p)
        }
        gui.setItem(RankedMenuLayout.DIVISIONS, GuiStyle.item(Material.NETHER_STAR, text("Ranked Divisions", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD),
            hint("Every division and the Elo", "it needs; your progress.") + text("Click to view", NamedTextColor.YELLOW))) { p, _ ->
            GuiStyle.clickSound(p); menu.openDivisions(p)
        }
        gui.setItem(RankedMenuLayout.TOP_PLAYERS, GuiStyle.item(Material.GOLDEN_HELMET, text("Player Leaderboard", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
            hint("Top Player Elo this season.") + text("Click to view", NamedTextColor.YELLOW))) { p, _ ->
            GuiStyle.pageSound(p); menu.openLeaderboard(p, RatingType.PLAYER_ELO, 0)
        }
        gui.setItem(RankedMenuLayout.TOP_TEAMS, GuiStyle.item(Material.SHIELD, text("Team Leaderboard", NamedTextColor.AQUA).decorate(TextDecoration.BOLD),
            hint("Top Team Elo this season.") + text("Click to view", NamedTextColor.YELLOW))) { p, _ ->
            GuiStyle.pageSound(p); menu.openLeaderboard(p, RatingType.TEAM_ELO, 0)
        }
    }

    private fun ratingItem(player: Player) = GuiStyle.item(Material.NETHER_STAR, text("Your Ranked Rating", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), buildList {
        val ratings = plugin.lifestealRatingManager
        val profile = ratings.cachedProfile(player.uniqueId)
        val reason = ratings.unavailableReason()
        when {
            reason != null -> add(text(reason, NamedTextColor.RED))
            profile == null -> {
                add(text("Loading... reopen /ranked to see it.", NamedTextColor.GRAY))
                ratings.loadProfile(player.uniqueId) { _, _ -> }
            }
            else -> {
                add(text("Season: ${profile.seasonId}", NamedTextColor.GRAY))
                add(text("Player Elo: ${profile.playerRating}", NamedTextColor.WHITE))
                val teamName = profile.teamName
                add(if (teamName == null) text("Team Elo: not in a team", NamedTextColor.GRAY) else text("Team Elo: ${profile.teamRating} ($teamName)", NamedTextColor.WHITE))
                add(text("Wins ${profile.wins} / Losses ${profile.losses}", NamedTextColor.GRAY))
                if (profile.recent.isNotEmpty()) {
                    add(Component.empty())
                    add(text("Recent changes:", NamedTextColor.GRAY))
                    for (h in profile.recent.take(3)) {
                        val label = h.outcome?.label?.replaceFirstChar { it.uppercase() } ?: "?"
                        add(text("$label ${h.previous} -> ${h.new} (${LifestealRatingManager.signed(h.delta)})", LifestealRatingManager.deltaColor(h.delta)))
                    }
                }
            }
        }
        add(Component.empty())
        add(text("Click for full stats", NamedTextColor.YELLOW))
    })

    private fun statusItem(player: Player, entry: QueueEntry?, blocked: String?, now: Long) = GuiStyle.item(Material.CLOCK, text("Current Queue Status", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD), buildList {
        if (blocked != null) add(text(blocked, NamedTextColor.RED))
        if (entry == null) add(text("Not in a queue or challenge.", NamedTextColor.GRAY))
        else {
            add(text(queue.describe(entry).replaceFirstChar { it.uppercase() }, NamedTextColor.WHITE))
            add(text(entry.state.label.replaceFirstChar { it.uppercase() }, NamedTextColor.YELLOW))
            entry.waitingSince?.let { add(text("Waiting ${seconds(now - it)}s", NamedTextColor.GRAY)) }
            entry.problem?.let { add(text("Paused: $it", NamedTextColor.YELLOW)) }
            entry.proposalId?.let { queue.proposal(it) }?.let { p -> add(text("Accepted ${p.accepted.size}/${p.participants.size}, ${seconds(p.expiresAt - now)}s left", NamedTextColor.GOLD)) }
        }
        queue.playerCooldownSeconds(player.uniqueId).takeIf { it > 0 }?.let { add(text("You can queue again in ${it}s", NamedTextColor.GRAY)) }
        add(Component.empty())
        add(text("Click for details", NamedTextColor.YELLOW))
    })

    // ── Player picker ───────────────────────────────────────

    /** What the picked players are for. */
    sealed interface Pick {
        val size: Int
        data class Queue(override val size: Int) : Pick
        data class Challenge(val targetTeamId: String, override val size: Int) : Pick
        data class Answer(val challengeId: String, override val size: Int) : Pick
    }

    private fun openPicker(player: Player, team: Team, pick: Pick, selected: Set<UUID>) {
        val gui = CustomGui(title("Pick ${pick.size} players"), SIZE)
        val members = team.members.map { it.uuid }
        val columns = centered(members.size, 9)
        for ((i, uuid) in members.withIndex()) {
            val name = teams.nameOf(uuid)
            val online = Bukkit.getPlayer(uuid)?.takeIf { it.isOnline }
            val problem = when {
                online == null -> "Offline"
                else -> plugin.lifestealRankedManager.participantProblem(online, team)?.replaceFirstChar { it.uppercase() }
            }
            val chosen = uuid in selected
            val lore = listOf(
                when {
                    chosen -> text("Selected", NamedTextColor.GREEN)
                    problem != null -> text(problem, NamedTextColor.RED)
                    else -> text("Not selected", NamedTextColor.GRAY)
                },
                Component.empty(),
                text(if (problem != null && !chosen) "Can't play right now" else "Click to ${if (chosen) "remove" else "select"}", NamedTextColor.YELLOW),
            )
            gui.setItem(9 + columns[i], GuiStyle.skull(Bukkit.getOfflinePlayer(uuid), text(name, if (chosen) NamedTextColor.GREEN else NamedTextColor.WHITE), lore)) { p, _ ->
                if (!chosen && problem != null) return@setItem GuiStyle.errorSound(p)
                if (!chosen && selected.size >= pick.size) { GuiStyle.errorSound(p); Msg.error(p, "A ${pick.size}v${pick.size} has exactly ${pick.size} players; remove someone first."); return@setItem }
                GuiStyle.clickSound(p)
                val current = teams.teamOf(p.uniqueId)?.takeIf { it.id == team.id } ?: return@setItem p.closeInventory()
                openPicker(p, current, pick, if (chosen) selected - uuid else selected + uuid)
            }
        }
        val ready = selected.size == pick.size
        gui.setItem(24, GuiStyle.item(if (ready) Material.LIME_CONCRETE else Material.GRAY_CONCRETE, text(if (ready) "Ask them to play" else "Pick ${pick.size - selected.size} more", if (ready) NamedTextColor.GREEN else NamedTextColor.GRAY).decorate(TextDecoration.BOLD), listOf(
            text("Selected ${selected.size}/${pick.size}", NamedTextColor.GRAY),
            text("Each picked player has to agree.", NamedTextColor.GRAY),
            text("Hearts are at risk; gear is given back.", NamedTextColor.RED),
        ))) { p, _ ->
            if (!ready) return@setItem GuiStyle.errorSound(p)
            GuiStyle.successSound(p)
            p.closeInventory()
            val roster = members.filter { it in selected }
            when (pick) {
                is Pick.Queue -> queue.queueWith(p, pick.size, roster)
                is Pick.Challenge -> queue.challengeWith(p, pick.targetTeamId, pick.size, roster)
                is Pick.Answer -> queue.acceptChallengeWith(p, pick.challengeId, roster)
            }
        }
        gui.setItem(GuiStyle.backSlot(SIZE), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); if (pick is Pick.Queue) open(p) else openChallenges(p) }
        gui.setItem(GuiStyle.closeSlot(SIZE), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Challenges ──────────────────────────────────────────

    private fun openChallenges(player: Player) {
        val team = teams.teamOf(player.uniqueId) ?: run { Msg.error(player, "Join a Lifesteal team first."); player.closeInventory(); return }
        val now = System.currentTimeMillis()
        val leader = team.leader == player.uniqueId
        val gui = CustomGui(title("Team Challenges"), SIZE)
        for ((i, c) in queue.incomingChallenges(team.id).take(5).withIndex()) {
            gui.setItem(10 + i, GuiStyle.item(Material.PAPER, text("From ${c.fromTeamName}", NamedTextColor.GOLD), listOf(
                text("${c.size}v${c.size} ranked match", NamedTextColor.GRAY),
                text("Expires in ${seconds(c.expiresAt - now)}s", NamedTextColor.GRAY),
                Component.empty(),
                text(if (leader) "Click to answer" else "Your leader answers challenges", NamedTextColor.YELLOW),
            ))) { p, _ ->
                if (teams.teamOf(p.uniqueId)?.leader != p.uniqueId) return@setItem GuiStyle.errorSound(p)
                GuiStyle.clickSound(p)
                openAnswer(p, c)
            }
        }
        val outgoing = queue.outgoingChallenge(team.id)
        if (outgoing != null) {
            gui.setItem(16, GuiStyle.item(Material.CROSSBOW, text("To ${outgoing.toTeamName}", NamedTextColor.AQUA), listOf(
                text("${outgoing.size}v${outgoing.size}, ${outgoing.state.name.lowercase()}", NamedTextColor.GRAY),
                Component.empty(),
                text(if (leader) "Click to withdraw" else "Sent by your leader", NamedTextColor.YELLOW),
            ))) { p, _ ->
                if (teams.teamOf(p.uniqueId)?.leader != p.uniqueId) return@setItem GuiStyle.errorSound(p)
                GuiStyle.clickSound(p)
                p.closeInventory()
                queue.cancelChallenge(p)
            }
        } else {
            gui.setItem(16, GuiStyle.item(Material.TARGET, text("Challenge a Team", NamedTextColor.AQUA).decorate(TextDecoration.BOLD), listOf(
                text("Pick a team, a size and your players.", NamedTextColor.GRAY),
                text(if (leader) "Click to choose a team" else "Only your leader can send challenges", NamedTextColor.YELLOW),
            ))) { p, _ ->
                if (teams.teamOf(p.uniqueId)?.leader != p.uniqueId) return@setItem GuiStyle.errorSound(p)
                GuiStyle.pageSound(p)
                openTeams(p, 0)
            }
        }
        gui.setItem(GuiStyle.backSlot(SIZE), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.setItem(GuiStyle.closeSlot(SIZE), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    private fun openAnswer(player: Player, c: Challenge) {
        val gui = CustomGui(title("${c.fromTeamName} ${c.size}v${c.size}"), SIZE)
        gui.setItem(11, GuiStyle.item(Material.LIME_CONCRETE, text("Accept: pick players", NamedTextColor.GREEN).decorate(TextDecoration.BOLD), listOf(
            text("Choose ${c.size} players of your team.", NamedTextColor.GRAY),
            text("Each of them has to agree.", NamedTextColor.GRAY),
        ))) { p, _ ->
            val (current, problem) = queue.answerProblem(p, c.fromTeamId)
            if (current == null || current.id != c.id) { GuiStyle.errorSound(p); Msg.error(p, problem ?: LifestealRankedQueueManager.STALE); return@setItem }
            val team = teams.teamOf(p.uniqueId) ?: return@setItem GuiStyle.errorSound(p)
            GuiStyle.clickSound(p)
            openPicker(p, team, Pick.Answer(c.id, c.size), emptySet())
        }
        gui.setItem(13, GuiStyle.item(Material.PAPER, text("${c.fromTeamName} challenges you", NamedTextColor.GOLD), listOf(
            text("${c.size}v${c.size} ranked match.", NamedTextColor.GRAY),
            text("Hearts are at risk (normal Lifesteal rules).", NamedTextColor.RED),
            text("Your gear is given back after the match.", NamedTextColor.GRAY),
        )))
        gui.setItem(15, GuiStyle.item(Material.RED_CONCRETE, text("Decline", NamedTextColor.RED).decorate(TextDecoration.BOLD))) { p, _ ->
            GuiStyle.clickSound(p)
            p.closeInventory()
            queue.declineChallenge(p, c.fromTeamName)
        }
        gui.setItem(GuiStyle.backSlot(SIZE), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); openChallenges(p) }
        gui.setItem(GuiStyle.closeSlot(SIZE), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    private fun openTeams(player: Player, page: Int) {
        val own = teams.teamOf(player.uniqueId)
        val all = teams.allTeams().filter { it.id != own?.id }
        val pages = maxOf(1, (all.size + PER_PAGE - 1) / PER_PAGE)
        val current = page.coerceIn(0, pages - 1)
        val gui = CustomGui(title(if (pages > 1) "Challenge (${current + 1}/$pages)" else "Challenge a Team"), 54)
        val slots = (1..4).flatMap { row -> (1..7).map { row * 9 + it } }
        for ((i, t) in all.drop(current * PER_PAGE).take(PER_PAGE).withIndex()) {
            val online = t.members.count { teams.isOnline(it.uuid) }
            gui.setItem(slots[i], GuiStyle.item(Material.WHITE_BANNER, text(t.name, NamedTextColor.GOLD), listOf(
                text("Members: ${t.size}, online: $online", NamedTextColor.GRAY),
                Component.empty(),
                text("Click to pick a size", NamedTextColor.YELLOW),
            ))) { p, _ ->
                GuiStyle.clickSound(p)
                openChallengeSize(p, t.id)
            }
        }
        if (current > 0) gui.setItem(GuiStyle.prevSlot(54), GuiStyle.previousPage(current + 1, pages)) { p, _ -> GuiStyle.pageSound(p); openTeams(p, current - 1) }
        if (current < pages - 1) gui.setItem(GuiStyle.nextSlot(54), GuiStyle.nextPage(current + 1, pages)) { p, _ -> GuiStyle.pageSound(p); openTeams(p, current + 1) }
        gui.setItem(GuiStyle.backSlot(54), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); openChallenges(p) }
        gui.setItem(GuiStyle.closeSlot(54), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    private fun openChallengeSize(player: Player, targetId: String) {
        val target = teams.team(targetId) ?: return openTeams(player, 0)
        val gui = CustomGui(title("Challenge ${target.name}"), SIZE)
        for ((i, size) in (2..5).withIndex()) {
            val problem = if (size in queue.allowedSizes) queue.challengeProblem(player, target, size) else "Not offered on this server."
            gui.setItem(SIZE_SLOTS[i], GuiStyle.item(if (problem == null) SIZE_ICONS[i] else Material.GRAY_DYE, text("${size}v$size", if (problem == null) NamedTextColor.GOLD else NamedTextColor.DARK_GRAY), listOf(
                if (problem == null) text("Click to pick your players", NamedTextColor.YELLOW) else text(problem, NamedTextColor.RED),
            ))) { p, _ ->
                val now = teams.team(targetId)
                val current = queue.challengeProblem(p, now, size)
                if (current != null || now == null) { GuiStyle.errorSound(p); Msg.error(p, current ?: "That team no longer exists."); return@setItem }
                val own = teams.teamOf(p.uniqueId) ?: return@setItem GuiStyle.errorSound(p)
                GuiStyle.clickSound(p)
                openPicker(p, own, Pick.Challenge(targetId, size), emptySet())
            }
        }
        gui.setItem(GuiStyle.backSlot(SIZE), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); openTeams(p, 0) }
        gui.setItem(GuiStyle.closeSlot(SIZE), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    /** Chest titles stay short (CLAUDE.md: titles over ~28 characters overflow). */
    private fun title(raw: String): Component = text(if (raw.length > 28) raw.take(27) + "…" else raw, NamedTextColor.DARK_GRAY)

    companion object {
        const val SIZE = 27
        private const val PER_PAGE = 28
        private val SIZE_SLOTS = listOf(10, 12, 14, 16)
        private val SIZE_ICONS =listOf(Material.IRON_SWORD, Material.GOLDEN_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD)

        fun centered(count: Int, width: Int): List<Int> {
            if (count <= 0) return emptyList()
            val n = minOf(count, width)
            val start = (width - n) / 2
            return List(n) { start + it }
        }
    }
}
