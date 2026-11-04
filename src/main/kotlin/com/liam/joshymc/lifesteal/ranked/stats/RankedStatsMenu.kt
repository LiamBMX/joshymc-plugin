package com.liam.joshymc.lifesteal.ranked.stats

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.lifesteal.progression.ProgressionRules
import com.liam.joshymc.lifesteal.ranked.queue.LifestealRankedQueueManager
import com.liam.joshymc.lifesteal.ranked.queue.RankedQueueMenu
import com.liam.joshymc.lifesteal.ranked.rating.LifestealRatingManager
import com.liam.joshymc.lifesteal.ranked.rating.RatingOutcome
import com.liam.joshymc.lifesteal.ranked.rating.RatingType
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The statistics side of the Ranked Team Wars menu (issue #1367): player and team statistics,
 * player and team leaderboards, divisions, match history and the current season. Plain chest GUIs
 * through the shared GuiManager (every click on the menu is cancelled, nothing can be moved in or
 * out), [GuiStyle] items and vanilla materials only, so Bedrock players (Geyser) get the same
 * chests.
 *
 * Every view is **loaded first, off the main thread**, and opened when the data arrives, so
 * rendering never touches the database and a view always shows what was committed when it was
 * opened. Every click re-opens through [guard] (permission and Lifesteal checked again) and loads
 * fresh data; no button changes anything.
 */
class RankedStatsMenu(private val plugin: Joshymc) {

    private val stats get() = plugin.lifestealRankedStatsManager
    private val teams get() = plugin.lifestealTeamManager

    private fun guard(player: Player): Boolean {
        if (!player.isOnline) return false
        if (!stats.hosted || !player.hasPermission(LifestealRankedQueueManager.PERMISSION_USE)) {
            player.closeInventory()
            Msg.noPermission(player)
            return false
        }
        return true
    }

    private fun fail(player: Player, problem: String?) {
        if (!player.isOnline) return
        GuiStyle.errorSound(player)
        Msg.error(player, problem ?: "Ranked statistics are unavailable right now.")
    }

    private fun backToMain(player: Player) {
        GuiStyle.clickSound(player)
        plugin.lifestealRatingManager.loadProfile(player.uniqueId) { _, _ -> if (player.isOnline) RankedQueueMenu(plugin).open(player) }
    }

    private fun bottom(gui: CustomGui, size: Int, back: (Player) -> Unit) {
        gui.setItem(GuiStyle.backSlot(size), GuiStyle.back()) { p, _ -> back(p) }
        gui.setItem(GuiStyle.closeSlot(size), GuiStyle.close()) { p, _ -> p.closeInventory() }
    }

    // ── Player statistics ───────────────────────────────────

    fun openPlayerStats(viewer: Player, target: UUID) {
        if (!guard(viewer)) return
        if (target != viewer.uniqueId && !viewer.hasPermission(LifestealRatingManager.PERMISSION_STATS_OTHERS)) return Msg.noPermission(viewer)
        stats.loadPlayerStats(target, RECENT) { s, problem ->
            if (!guard(viewer)) return@loadPlayerStats
            if (s == null) return@loadPlayerStats fail(viewer, problem)
            renderPlayerStats(viewer, s)
        }
    }

    private fun renderPlayerStats(viewer: Player, s: LifestealRankedStatsManager.PlayerStats) {
        val name = teams.nameOf(s.uuid)
        val own = s.uuid == viewer.uniqueId
        val gui = CustomGui(title(if (own) "Your Ranked Stats" else "$name's Stats"), RankedMenuLayout.DETAIL_SIZE)
        val sum = s.summary
        gui.setItem(RankedMenuLayout.LIST_HEADER, GuiStyle.skull(Bukkit.getOfflinePlayer(s.uuid), text(name, NamedTextColor.GOLD).decorate(TextDecoration.BOLD), listOf(
            label("Division", stats.divisionName(RatingType.PLAYER_ELO, sum.current)),
            label("Player Elo", text(sum.current.toString(), NamedTextColor.WHITE)),
            label("Season", text(s.seasonId, NamedTextColor.WHITE)),
        )))
        val slots = RankedMenuLayout.centeredRow(2, 7)
        gui.setItem(slots[0], eloItem(RatingType.PLAYER_ELO, sum))
        gui.setItem(slots[1], recordItem(sum))
        gui.setItem(slots[2], streakItem(sum))
        gui.setItem(slots[3], rankingItem(s.position, s.minimumMatches, sum.matches, "players")) { p, _ ->
            GuiStyle.pageSound(p); openLeaderboard(p, RatingType.PLAYER_ELO, 0)
        }
        gui.setItem(slots[4], progressItem(RatingType.PLAYER_ELO, sum.current))
        val teamName = s.teamName
        gui.setItem(slots[5], GuiStyle.item(Material.WHITE_BANNER, text(teamName ?: "No team", NamedTextColor.AQUA).decorate(TextDecoration.BOLD), buildList {
            if (teamName == null || s.teamRating == null) add(text("Not in a Lifesteal team.", NamedTextColor.GRAY))
            else {
                add(label("Team Elo", text(s.teamRating.toString(), NamedTextColor.WHITE)))
                add(label("Team division", stats.divisionName(RatingType.TEAM_ELO, s.teamRating)))
                add(text("Team Elo is the team's own rating,", NamedTextColor.DARK_GRAY))
                add(text("separate from Player Elo.", NamedTextColor.DARK_GRAY))
                add(Component.empty())
                add(text("Click for team stats", NamedTextColor.YELLOW))
            }
        })) { p, _ ->
            val id = s.teamId ?: return@setItem GuiStyle.errorSound(p)
            GuiStyle.clickSound(p); openTeamStats(p, id)
        }
        gui.setItem(slots[6], recentItem(s.recent, player = true)) { p, _ ->
            GuiStyle.pageSound(p); openPlayerHistory(p, s.uuid, 0)
        }
        bottom(gui, RankedMenuLayout.DETAIL_SIZE) { backToMain(it) }
        plugin.guiManager.open(viewer, gui)
    }

    // ── Team statistics ─────────────────────────────────────

    fun openTeamStats(viewer: Player, teamId: String) {
        if (!guard(viewer)) return
        // Players only see active teams (their records stay; staff diagnostics show disbanded ones).
        if (teams.team(teamId) == null) return fail(viewer, "That team no longer exists.")
        stats.loadTeamStats(teamId, RECENT) { s, problem ->
            if (!guard(viewer)) return@loadTeamStats
            if (s == null) return@loadTeamStats fail(viewer, problem)
            renderTeamStats(viewer, s)
        }
    }

    private fun renderTeamStats(viewer: Player, s: LifestealRankedStatsManager.TeamStats) {
        val gui = CustomGui(title("${s.name} Stats"), RankedMenuLayout.DETAIL_SIZE)
        val sum = s.summary
        gui.setItem(RankedMenuLayout.LIST_HEADER, GuiStyle.item(Material.WHITE_BANNER, text(s.name, NamedTextColor.AQUA).decorate(TextDecoration.BOLD), buildList {
            add(label("Leader", text(s.leader?.let { teams.nameOf(it) } ?: "-", NamedTextColor.WHITE)))
            add(label("Division", stats.divisionName(RatingType.TEAM_ELO, sum.current)))
            add(label("Team Elo", text(sum.current.toString(), NamedTextColor.WHITE)))
            add(label("Season", text(s.seasonId, NamedTextColor.WHITE)))
            if (!s.active) add(text("Disbanded: records kept, not ranked.", NamedTextColor.RED))
        }))
        val slots = RankedMenuLayout.centeredRow(2, 6)
        gui.setItem(slots[0], eloItem(RatingType.TEAM_ELO, sum))
        gui.setItem(slots[1], recordItem(sum))
        gui.setItem(slots[2], streakItem(sum))
        gui.setItem(slots[3], rankingItem(s.position, s.minimumMatches, sum.matches, "teams")) { p, _ ->
            GuiStyle.pageSound(p); openLeaderboard(p, RatingType.TEAM_ELO, 0)
        }
        gui.setItem(slots[4], progressItem(RatingType.TEAM_ELO, sum.current))
        gui.setItem(slots[5], recentItem(s.recent, player = false)) { p, _ ->
            GuiStyle.pageSound(p); openTeamHistory(p, s.teamId, 0)
        }
        bottom(gui, RankedMenuLayout.DETAIL_SIZE) { backToMain(it) }
        plugin.guiManager.open(viewer, gui)
    }

    // ── Leaderboards ────────────────────────────────────────

    fun openLeaderboard(viewer: Player, type: RatingType, page: Int) {
        if (!guard(viewer)) return
        stats.loadLeaderboard(type, page, RankedMenuLayout.PER_PAGE, viewer.uniqueId) { board, problem ->
            if (!guard(viewer)) return@loadLeaderboard
            if (board == null) return@loadLeaderboard fail(viewer, problem)
            renderLeaderboard(viewer, type, board)
        }
    }

    private fun renderLeaderboard(viewer: Player, type: RatingType, board: LifestealRankedStatsManager.Board) {
        val size = RankedMenuLayout.LIST_SIZE
        val page = board.page
        val players = type == RatingType.PLAYER_ELO
        val gui = CustomGui(title((if (players) "Top Players" else "Top Teams") + if (page.pages > 1) " (${page.page + 1}/${page.pages})" else ""), size)
        gui.setItem(RankedMenuLayout.LIST_HEADER, GuiStyle.item(if (players) Material.NETHER_STAR else Material.WHITE_BANNER, text(if (players) "Your Place" else "Your Team's Place", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), buildList {
            add(label("Season", text(page.seasonId, NamedTextColor.WHITE)))
            when {
                board.viewerSubject == null -> add(text("You aren't in a Lifesteal team.", NamedTextColor.GRAY))
                board.viewerPosition != null -> add(label("Position", text("#${board.viewerPosition} of ${page.total}", NamedTextColor.GREEN)))
                else -> add(text("Not ranked yet: ${board.viewerMatches}/${board.minimumMatches} rated matches.", NamedTextColor.GRAY))
            }
            add(text("Listed after ${board.minimumMatches} rated match(es) this season.", NamedTextColor.DARK_GRAY))
            add(text("Ties: more wins, then whoever got there first.", NamedTextColor.DARK_GRAY))
        }))
        if (page.entries.isEmpty()) gui.setItem(22, GuiStyle.item(Material.PAPER, text("Nobody is ranked yet", NamedTextColor.GRAY), listOf(
            text("Play rated Ranked Team Wars matches", NamedTextColor.GRAY), text("to appear here.", NamedTextColor.GRAY),
        )))
        val ownTeam = teams.teamOf(viewer.uniqueId)?.id
        for ((i, e) in page.entries.withIndex()) {
            val slot = RankedMenuLayout.LIST_SLOTS[i]
            val mine = if (players) e.subjectId == viewer.uniqueId.toString() else e.subjectId == ownTeam
            val name = if (players) uuidOf(e.subjectId)?.let { teams.nameOf(it) } ?: "?" else e.name ?: "?"
            val title = text("#${e.position} ", positionColor(e.position)).decorate(TextDecoration.BOLD)
                .append(text(name, if (mine) NamedTextColor.GREEN else NamedTextColor.WHITE).decoration(TextDecoration.BOLD, mine))
                .append(if (mine) text(" (you)", NamedTextColor.GREEN) else Component.empty())
            val lore = buildList {
                add(label("Division", stats.divisionName(type, e.rating)))
                add(label(if (players) "Player Elo" else "Team Elo", text(e.rating.toString(), NamedTextColor.WHITE)))
                add(label("Wins / Losses", text("${e.wins} / ${e.losses}", NamedTextColor.WHITE)))
                add(label("Matches", text(e.matches.toString(), NamedTextColor.WHITE)))
                add(label("Win rate", text(e.winPercent, NamedTextColor.WHITE)))
                if (!players) e.leader?.let { add(label("Leader", text(teams.nameOf(it), NamedTextColor.WHITE))) }
                add(Component.empty())
                add(text("Click for stats", NamedTextColor.YELLOW))
            }
            val icon = if (players) uuidOf(e.subjectId)?.let { GuiStyle.skull(Bukkit.getOfflinePlayer(it), title, lore) } ?: GuiStyle.item(Material.PLAYER_HEAD, title, lore)
            else GuiStyle.item(if (mine) Material.LIME_BANNER else Material.WHITE_BANNER, title, lore)
            gui.setItem(slot, icon) { p, _ ->
                GuiStyle.clickSound(p)
                if (players) uuidOf(e.subjectId)?.let { openPlayerStats(p, it) } else openTeamStats(p, e.subjectId)
            }
        }
        if (page.page > 0) gui.setItem(GuiStyle.prevSlot(size), GuiStyle.previousPage(page.page + 1, page.pages)) { p, _ -> GuiStyle.pageSound(p); openLeaderboard(p, type, page.page - 1) }
        if (page.page < page.pages - 1) gui.setItem(GuiStyle.nextSlot(size), GuiStyle.nextPage(page.page + 1, page.pages)) { p, _ -> GuiStyle.pageSound(p); openLeaderboard(p, type, page.page + 1) }
        val other = if (players) RatingType.TEAM_ELO else RatingType.PLAYER_ELO
        gui.setItem(size - 1, GuiStyle.item(if (players) Material.WHITE_BANNER else Material.PLAYER_HEAD, text(if (players) "Team Leaderboard" else "Player Leaderboard", NamedTextColor.AQUA), listOf(
            text("Player Elo and Team Elo are", NamedTextColor.GRAY), text("separate leaderboards.", NamedTextColor.GRAY),
        ))) { p, _ -> GuiStyle.pageSound(p); openLeaderboard(p, other, 0) }
        bottom(gui, size) { backToMain(it) }
        plugin.guiManager.open(viewer, gui)
    }

    // ── Match history ───────────────────────────────────────

    fun openPlayerHistory(viewer: Player, target: UUID, page: Int) {
        if (!guard(viewer)) return
        if (target != viewer.uniqueId && !viewer.hasPermission(LifestealRatingManager.PERMISSION_STATS_OTHERS)) return Msg.noPermission(viewer)
        stats.loadPlayerHistory(target, page, RankedMenuLayout.PER_PAGE) { h, problem ->
            if (!guard(viewer)) return@loadPlayerHistory
            if (h == null) return@loadPlayerHistory fail(viewer, problem)
            val whose = if (target == viewer.uniqueId) "Your" else "${teams.nameOf(target)}'s"
            renderHistory(viewer, "$whose Matches", h, player = true, back = { p -> openPlayerStats(p, target) }) { p, n -> openPlayerHistory(p, target, n) }
        }
    }

    fun openTeamHistory(viewer: Player, teamId: String, page: Int) {
        if (!guard(viewer)) return
        val team = teams.team(teamId) ?: return fail(viewer, "That team no longer exists.")
        stats.loadTeamHistory(teamId, page, RankedMenuLayout.PER_PAGE) { h, problem ->
            if (!guard(viewer)) return@loadTeamHistory
            if (h == null) return@loadTeamHistory fail(viewer, problem)
            renderHistory(viewer, "${team.name} Matches", h, player = false, back = { p -> openTeamStats(p, teamId) }) { p, n -> openTeamHistory(p, teamId, n) }
        }
    }

    private fun renderHistory(viewer: Player, heading: String, h: RankedStatsStore.HistoryPage, player: Boolean, back: (Player) -> Unit, goto: (Player, Int) -> Unit) {
        val size = RankedMenuLayout.LIST_SIZE
        val gui = CustomGui(title(heading + if (h.pages > 1) " (${h.page + 1}/${h.pages})" else ""), size)
        gui.setItem(RankedMenuLayout.LIST_HEADER, GuiStyle.item(Material.BOOK, text("Match History", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), listOf(
            label("Matches", text(h.total.toString(), NamedTextColor.WHITE)),
            text("Newest first, every season.", NamedTextColor.DARK_GRAY),
            text("Unrated matches didn't change Elo.", NamedTextColor.DARK_GRAY),
        )))
        if (h.lines.isEmpty()) gui.setItem(22, GuiStyle.item(Material.PAPER, text("No ranked matches yet", NamedTextColor.GRAY)))
        val zone = plugin.timezoneManager.zoneFor(viewer)
        for ((i, line) in h.lines.withIndex()) {
            val won = line.outcome == RatingOutcome.WIN
            val name = text(if (won) "Win" else "Loss", if (won) NamedTextColor.GREEN else NamedTextColor.RED).decorate(TextDecoration.BOLD)
                .append(text(" vs ${line.opponentTeamName}", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
            gui.setItem(RankedMenuLayout.LIST_SLOTS[i], GuiStyle.item(if (won) Material.LIME_DYE else Material.RED_DYE, name, historyLore(line, player, zone)))
        }
        if (h.page > 0) gui.setItem(GuiStyle.prevSlot(size), GuiStyle.previousPage(h.page + 1, h.pages)) { p, _ -> GuiStyle.pageSound(p); goto(p, h.page - 1) }
        if (h.page < h.pages - 1) gui.setItem(GuiStyle.nextSlot(size), GuiStyle.nextPage(h.page + 1, h.pages)) { p, _ -> GuiStyle.pageSound(p); goto(p, h.page + 1) }
        bottom(gui, size) { p -> GuiStyle.clickSound(p); back(p) }
        plugin.guiManager.open(viewer, gui)
    }

    private fun historyLore(line: RankedStatsStore.MatchLine, player: Boolean, zone: java.time.ZoneId): List<Component> = buildList {
        add(label("Date", text(DATE.withZone(zone).format(Instant.ofEpochMilli(line.endedAt)), NamedTextColor.WHITE)))
        add(label("Size", text("${line.size}v${line.size}", NamedTextColor.WHITE)))
        add(label("Team", text(line.ownTeamName, NamedTextColor.WHITE)))
        add(label("Opponent", text(line.opponentTeamName, NamedTextColor.WHITE)))
        add(label("Arena", text(line.arena, NamedTextColor.WHITE)))
        line.durationMillis?.let { add(label("Duration", text(RankedStatistics.duration(it), NamedTextColor.WHITE))) }
        add(label("Type", text(if (line.kind == "CHALLENGE") "Team challenge" else "Ranked queue", NamedTextColor.WHITE)))
        add(label("Season", text(line.seasonId, NamedTextColor.WHITE)))
        when (line.rated) {
            RankedStatsStore.RatedState.RATED -> {
                if (player) line.playerDelta?.let { d -> add(label("Player Elo", text("${line.playerAfter} ", NamedTextColor.WHITE).append(text("(${LifestealRatingManager.signed(d)})", LifestealRatingManager.deltaColor(d))))) }
                line.teamDelta?.let { d -> add(label("Team Elo", text("${line.teamAfter} ", NamedTextColor.WHITE).append(text("(${LifestealRatingManager.signed(d)})", LifestealRatingManager.deltaColor(d))))) }
            }
            RankedStatsStore.RatedState.UNRATED -> add(text("Unrated${line.unratedReason?.let { ": $it" } ?: ""}", NamedTextColor.GRAY))
            RankedStatsStore.RatedState.PENDING -> add(text("Rating pending", NamedTextColor.YELLOW))
        }
    }

    // ── Divisions ───────────────────────────────────────────

    fun openDivisions(viewer: Player) {
        if (!guard(viewer)) return
        val table = stats.divisions ?: return fail(viewer, if (stats.divisionStatus == DivisionConfig.Status.DISABLED) "Ranked divisions are turned off on this server." else "Ranked divisions are unavailable (lifesteal.yml); see the console.")
        stats.loadPlayerStats(viewer.uniqueId, 0) { s, _ ->
            if (!guard(viewer)) return@loadPlayerStats
            // Shown even without the player's stats (e.g. no season yet): the table itself is config.
            val rating = s?.summary?.current
            val gui = CustomGui(title("Ranked Divisions"), RankedMenuLayout.DETAIL_SIZE)
            gui.setItem(RankedMenuLayout.LIST_HEADER, if (rating == null) GuiStyle.item(Material.NETHER_STAR, text("Your Division", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), listOf(text("No ranked rating yet.", NamedTextColor.GRAY)))
                else progressItem(RatingType.PLAYER_ELO, rating))
            val current = rating?.let { table.of(RatingType.PLAYER_ELO, it) }
            for ((i, d) in table.divisions.withIndex()) {
                val slot = RankedMenuLayout.divisionSlots(table.divisions.size).getOrNull(i) ?: break
                val here = current?.id == d.id
                gui.setItem(slot, GuiStyle.item(divisionIcon(d), LifestealRankedStatsManager.label(d), buildList {
                    add(label("Player Elo", text("${d.playerMinimum}+", NamedTextColor.WHITE)))
                    add(label("Team Elo", text("${d.teamMinimum}+", NamedTextColor.WHITE)))
                    if (d.description.isNotEmpty()) add(text(d.description, NamedTextColor.GRAY))
                    if (here) { add(Component.empty()); add(text("You are here", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)) }
                }))
            }
            gui.setItem(RankedMenuLayout.DETAIL_SIZE - 1, GuiStyle.item(Material.PAPER, text("About divisions", NamedTextColor.YELLOW), listOf(
                text("Divisions follow your current Elo.", NamedTextColor.GRAY),
                text("Player Elo and Team Elo each have one.", NamedTextColor.GRAY),
                text("They are titles only: no powers.", NamedTextColor.GRAY),
            )))
            bottom(gui, RankedMenuLayout.DETAIL_SIZE) { backToMain(it) }
            plugin.guiManager.open(viewer, gui)
        }
    }

    // ── Season ──────────────────────────────────────────────

    fun openSeason(viewer: Player) {
        if (!guard(viewer)) return
        stats.loadPlayerStats(viewer.uniqueId, 0) { s, _ ->
            if (!guard(viewer)) return@loadPlayerStats
            val gui = CustomGui(title("Current Season"), RankedMenuLayout.DETAIL_SIZE)
            gui.setItem(13, GuiStyle.item(Material.CLOCK, text("Lifesteal Season", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), seasonLore(viewer)))
            gui.setItem(29, GuiStyle.item(Material.EXPERIENCE_BOTTLE, text("Your Player Elo", NamedTextColor.GOLD), if (s == null) listOf(text("No rating this season yet.", NamedTextColor.GRAY)) else listOf(
                label("Player Elo", text(s.summary.current.toString(), NamedTextColor.WHITE)),
                label("Division", stats.divisionName(RatingType.PLAYER_ELO, s.summary.current)),
                label("Rated matches", text(s.summary.matches.toString(), NamedTextColor.WHITE)),
            )))
            gui.setItem(33, GuiStyle.item(Material.WHITE_BANNER, text("Your Team Elo", NamedTextColor.AQUA), when {
                s == null -> listOf(text("No rating this season yet.", NamedTextColor.GRAY))
                s.teamRating == null -> listOf(text("Not in a Lifesteal team.", NamedTextColor.GRAY))
                else -> listOf(
                    label("Team", text(s.teamName ?: "-", NamedTextColor.WHITE)),
                    label("Team Elo", text(s.teamRating.toString(), NamedTextColor.WHITE)),
                    label("Division", stats.divisionName(RatingType.TEAM_ELO, s.teamRating)),
                )
            }))
            bottom(gui, RankedMenuLayout.DETAIL_SIZE) { backToMain(it) }
            plugin.guiManager.open(viewer, gui)
        }
    }

    /** From the progression service only; no end date is invented (season rollover doesn't exist yet). */
    fun seasonLore(viewer: Player): List<Component> {
        val progression = plugin.lifestealProgressionManager
        val p = progression.progress()
        val season = p.season
        val length = plugin.lifestealConfigManager.settings?.seasons?.lengthMonths
        val zone = plugin.timezoneManager.zoneFor(viewer)
        return buildList {
            add(label("Season", text(season?.id ?: "none yet", NamedTextColor.WHITE)))
            add(label("Started", text(season?.let { s -> if (p.launched) DATE.withZone(zone).format(Instant.ofEpochMilli(s.launchAt)) else "not yet" } ?: "-", NamedTextColor.WHITE)))
            add(label("Status", text(p.phase.label, NamedTextColor.WHITE)))
            add(label("Ranked", text(progression.rankedStatus().label, if (progression.rankedStatus() == ProgressionRules.RankedStatus.AVAILABLE) NamedTextColor.GREEN else NamedTextColor.YELLOW)))
            if (length != null) add(label("Season length", text("$length months", NamedTextColor.WHITE)))
            add(text("No season end date is scheduled yet.", NamedTextColor.DARK_GRAY))
        }
    }

    // ── Shared items ────────────────────────────────────────

    private fun eloItem(type: RatingType, s: RankedStatistics.Summary) = GuiStyle.item(Material.EXPERIENCE_BOTTLE, text(type.label, NamedTextColor.GOLD).decorate(TextDecoration.BOLD), buildList {
        add(label("Current", text(s.current.toString(), NamedTextColor.WHITE)))
        add(label("Peak", text(s.peak.toString(), NamedTextColor.GREEN)))
        add(label("Lowest", text(s.lowest.toString(), NamedTextColor.RED)))
        add(label("Gained / lost", text("+${s.gained} / -${s.lost}", NamedTextColor.WHITE)))
        if (!s.rated) add(text("No rated matches this season yet.", NamedTextColor.DARK_GRAY))
    })

    private fun recordItem(s: RankedStatistics.Summary) = GuiStyle.item(Material.IRON_SWORD, text("Record", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), buildList {
        add(label("Rated matches", text(s.matches.toString(), NamedTextColor.WHITE)))
        add(label("Wins", text(s.wins.toString(), NamedTextColor.GREEN)))
        add(label("Losses", text(s.losses.toString(), NamedTextColor.RED)))
        if (s.draws > 0) add(label("Draws", text(s.draws.toString(), NamedTextColor.GRAY)))
        add(label("Win rate", text(s.winPercent, NamedTextColor.WHITE)))
    })

    private fun streakItem(s: RankedStatistics.Summary) = GuiStyle.item(Material.BLAZE_POWDER, text("Win Streaks", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), listOf(
        label("Current", text(s.currentStreak.toString(), NamedTextColor.WHITE)),
        label("Best", text(s.bestStreak.toString(), NamedTextColor.WHITE)),
    ))

    private fun rankingItem(position: Int?, minimum: Int?, matches: Int, of: String) = GuiStyle.item(Material.GOLDEN_HELMET, text("Season Ranking", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), buildList {
        when {
            minimum == null -> add(text("Leaderboards are unavailable.", NamedTextColor.RED))
            position != null -> add(label("Position", text("#$position among $of", NamedTextColor.GREEN)))
            else -> add(text("Not ranked yet: $matches/$minimum rated matches.", NamedTextColor.GRAY))
        }
        add(Component.empty())
        add(text("Click for the leaderboard", NamedTextColor.YELLOW))
    })

    private fun progressItem(type: RatingType, rating: Int) = run {
        val table = stats.divisions
        if (table == null) GuiStyle.item(Material.GRAY_DYE, text("Division", NamedTextColor.GRAY), listOf(text("Divisions are unavailable.", NamedTextColor.GRAY)))
        else {
            val pr = table.progress(type, rating)
            GuiStyle.item(divisionIcon(pr.current), text("Division: ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD).append(LifestealRankedStatsManager.label(pr.current)), buildList {
                add(label("Current Elo", text(rating.toString(), NamedTextColor.WHITE)))
                val next = pr.next
                if (next == null) add(text("Highest division reached.", NamedTextColor.GREEN))
                else {
                    add(label("Next division", LifestealRankedStatsManager.label(next)))
                    add(label("Elo required", text(pr.required.toString(), NamedTextColor.WHITE)))
                    add(label("Remaining", text(pr.remaining.toString(), NamedTextColor.YELLOW)))
                }
            })
        }
    }

    private fun recentItem(lines: List<RankedStatsStore.MatchLine>, player: Boolean) = GuiStyle.item(Material.BOOK, text("Recent Matches", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), buildList {
        if (lines.isEmpty()) add(text("No ranked matches yet.", NamedTextColor.GRAY))
        for (l in lines) {
            val won = l.outcome == RatingOutcome.WIN
            val delta = if (player) l.playerDelta else l.teamDelta
            add(text(if (won) "Win " else "Loss ", if (won) NamedTextColor.GREEN else NamedTextColor.RED)
                .append(text("vs ${l.opponentTeamName} (${l.size}v${l.size}) ", NamedTextColor.WHITE))
                .append(when (l.rated) {
                    RankedStatsStore.RatedState.RATED -> delta?.let { text(LifestealRatingManager.signed(it), LifestealRatingManager.deltaColor(it)) } ?: Component.empty()
                    RankedStatsStore.RatedState.UNRATED -> text("unrated", NamedTextColor.DARK_GRAY)
                    RankedStatsStore.RatedState.PENDING -> text("pending", NamedTextColor.YELLOW)
                }))
        }
        add(Component.empty())
        add(text("Click for match history", NamedTextColor.YELLOW))
    })

    private fun divisionIcon(d: Division): Material = d.icon?.let { Material.matchMaterial(it) }?.takeIf { it.isItem && !it.isAir } ?: Material.NETHER_STAR

    private fun label(name: String, value: Component): Component = text("$name: ", NamedTextColor.GRAY).append(value)

    private fun text(content: String, color: TextColor): Component = Component.text(content, color)

    private fun uuidOf(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()

    /** Chest titles stay short (CLAUDE.md: titles over ~28 characters overflow). */
    private fun title(raw: String): Component = Component.text(if (raw.length > 28) raw.take(27) + "…" else raw, NamedTextColor.DARK_GRAY)

    companion object {
        const val RECENT = 5
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun positionColor(position: Int): TextColor = when (position) {
            1 -> NamedTextColor.GOLD
            2 -> TextColor.color(0xC0C0C0)
            3 -> TextColor.color(0xCD7F32)
            else -> NamedTextColor.YELLOW
        }
    }
}
