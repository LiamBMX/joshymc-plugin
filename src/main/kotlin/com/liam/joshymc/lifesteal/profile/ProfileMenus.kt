package com.liam.joshymc.lifesteal.profile

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.lifesteal.profile.ProfileMenuLayout as L
import com.liam.joshymc.lifesteal.ranked.rating.RatingType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The Lifesteal profile, achievement, leaderboard and customization menus (issue #1429).
 *
 * Menus only display loaded data and never trust their own buttons: a claim re-checks the row in
 * the database (one winner), a customization is re-validated against the database, and a stale
 * menu (an achievement removed from the config, a tier already claimed elsewhere) gets a refusal,
 * not a reward. GuiManager cancels every click on a menu's own slots, blocks shift/number-key/
 * double-click/offhand moves into it and rate-limits clicks.
 */
class ProfileMenus(private val plugin: Joshymc, private val manager: LifestealProfileManager) {

    private fun text(s: String, color: NamedTextColor = NamedTextColor.GRAY) = Component.text(s, color)
    private fun bold(s: String, color: NamedTextColor) = Component.text(s, color).decorate(TextDecoration.BOLD)
    private fun line(label: String, value: String) = text("$label: ", NamedTextColor.GRAY).append(text(value, NamedTextColor.WHITE))
    private fun material(name: String, fallback: Material = Material.PAPER): Material = Material.matchMaterial(name)?.takeIf { it.isItem } ?: fallback
    private fun title(raw: String) = plugin.commsManager.parseLegacy(raw)
    private fun nameOf(uuid: UUID): String = Bukkit.getPlayer(uuid)?.name ?: plugin.playerResolver.lastKnownName(uuid) ?: Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString().take(8)

    private fun date(at: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(at))

    // ── Profile ─────────────────────────────────────────────

    /** Opens [targetName]'s profile for [viewer] (their own when [target] is them). */
    fun openProfile(viewer: Player, target: UUID, targetName: String) {
        manager.loadProfile(target, targetName) { profile, problem ->
            if (!viewer.isOnline) return@loadProfile
            if (profile == null) {
                manager.send(viewer, text(problem ?: "No profile.", NamedTextColor.RED))
                return@loadProfile
            }
            val own = viewer.uniqueId == target
            if (!own && profile.settings.private && manager.settings?.allowPrivate == true && !viewer.hasPermission(LifestealProfileManager.PERMISSION_PRIVATE_BYPASS)) {
                manager.send(viewer, text("${profile.name}'s profile is private.", NamedTextColor.RED))
                return@loadProfile
            }
            showProfile(viewer, profile, own)
        }
    }

    private fun showProfile(viewer: Player, p: LifestealProfileManager.Profile, own: Boolean) {
        val s = manager.settings ?: ProfileSettings.DEFAULTS
        val gui = CustomGui(title("&8${s.profileTitle}"), L.PROFILE_SIZE)
        val border = p.settings.border?.takeIf { it in manager.unlockedBorders(p.rows) } ?: s.defaultBorder
        val pane = GuiStyle.item(material(ProfileDecorations.borderMaterial(border), Material.GRAY_STAINED_GLASS_PANE), Component.text(" "))
        for (slot in L.borderSlots(L.PROFILE_SIZE)) gui.setItem(slot, pane)

        val season = p.periods.season
        val life = p.periods.lifetime
        val seasonLabel = p.periods.current?.id
        fun both(stat: ProfileStat): List<Component> = listOfNotNull(
            seasonLabel?.let { line("This season ($it)", "${season?.get(stat) ?: 0}") },
            line("All time", "${life[stat]}"),
        )
        val titles = manager.unlockedTitles(p.rows)
        val shownTitle = p.settings.title?.let { titles[it] }
        gui.setItem(L.HEAD, GuiStyle.skull(Bukkit.getOfflinePlayer(p.uuid), bold(p.name, NamedTextColor.GOLD), listOfNotNull(
            shownTitle?.let { title(it) },
            if (p.settings.private) text("Private profile", NamedTextColor.DARK_GRAY) else null,
        )))
        gui.setItem(L.HEARTS, GuiStyle.item(Material.RED_DYE, bold("Hearts", NamedTextColor.RED), listOf(line("Permanent hearts", p.hearts?.toString() ?: "-"))))
        gui.setItem(L.KILLS, GuiStyle.item(Material.IRON_SWORD, bold("PvP Kills", NamedTextColor.GREEN), both(ProfileStat.PVP_KILLS) + listOf(
            text("Only legitimate Lifesteal kills count.", NamedTextColor.DARK_GRAY),
            line("Unique players eliminated", "${life[ProfileStat.UNIQUE_VICTIMS]}"),
        )))
        gui.setItem(L.DEATHS, GuiStyle.item(Material.SKELETON_SKULL, bold("PvP Deaths", NamedTextColor.RED), both(ProfileStat.PVP_DEATHS)))
        gui.setItem(L.KD, GuiStyle.item(Material.COMPASS, bold("K/D Ratio", NamedTextColor.YELLOW), listOfNotNull(
            season?.let { line("This season", PvpCounting.kdText(it[ProfileStat.PVP_KILLS], it[ProfileStat.PVP_DEATHS])) },
            line("All time", PvpCounting.kdText(life[ProfileStat.PVP_KILLS], life[ProfileStat.PVP_DEATHS])),
        )))
        gui.setItem(L.STREAK, GuiStyle.item(Material.BLAZE_POWDER, bold("Kill Streak", NamedTextColor.GOLD), both(ProfileStat.BEST_KILL_STREAK) + listOf(
            line("Current streak", "${p.periods.currentStreak}"),
        )))
        gui.setItem(L.BOUNTIES, GuiStyle.item(Material.GOLD_INGOT, bold("Bounties", NamedTextColor.GOLD), both(ProfileStat.BOUNTY_CLAIMS)))
        gui.setItem(L.PLAYTIME, GuiStyle.item(Material.CLOCK, bold("Playtime", NamedTextColor.AQUA), listOf(
            if (p.playtimeSeconds != null) line("Played", plugin.playtimeManager.formatPlaytime(p.playtimeSeconds)) else text("Not shown on this server.", NamedTextColor.DARK_GRAY),
        )))
        val ranked = plugin.lifestealRankedStatsManager
        gui.setItem(L.RANKED, GuiStyle.item(Material.DIAMOND_SWORD, bold("Ranked Team Wars", NamedTextColor.AQUA), listOfNotNull(
            p.playerRating?.let { line("Player Elo", "$it") },
            p.playerRating?.let { ranked.divisions?.let { _ -> text("Division: ", NamedTextColor.GRAY).append(ranked.divisionName(RatingType.PLAYER_ELO, p.playerRating)) } },
            line("Rated matches", "${season?.get(ProfileStat.RANKED_MATCHES) ?: 0} this season, ${life[ProfileStat.RANKED_MATCHES]} total"),
            line("Wins", "${season?.get(ProfileStat.RANKED_WINS) ?: 0} this season, ${life[ProfileStat.RANKED_WINS]} total"),
        )))
        gui.setItem(L.TEAM, GuiStyle.item(Material.WHITE_BANNER, bold("Team", NamedTextColor.BLUE), listOfNotNull(
            line("Team", p.teamName ?: "None"),
            p.teamRating?.let { line("Team Elo", "$it") },
            p.teamRating?.let { r -> ranked.divisions?.let { text("Team division: ", NamedTextColor.GRAY).append(ranked.divisionName(RatingType.TEAM_ELO, r)) } },
        )))
        val defs = manager.achievements
        val done = p.rows.size
        gui.setItem(L.ACHIEVEMENTS, GuiStyle.item(Material.BOOK, bold("Achievements", NamedTextColor.LIGHT_PURPLE), listOf(
            line("Tiers completed", "$done"),
            line("Claimed", "${p.rows.count { it.status == "CLAIMED" }}"),
            line("Achievements available", "${defs.size}"),
        )))
        gui.setItem(L.ACTIVITY, GuiStyle.item(Material.IRON_PICKAXE, bold("Activity", NamedTextColor.GREEN), listOf(
            ProfileStat.ORES_MINED, ProfileStat.CROPS_HARVESTED, ProfileStat.FISH_CAUGHT, ProfileStat.MOBS_KILLED, ProfileStat.DIMENSIONS_EXPLORED,
        ).map { st -> line(st.label, "${season?.get(st) ?: 0} this season, ${life[st]} total") }))
        val previous = p.periods.previous
        gui.setItem(L.SEASONS, GuiStyle.item(Material.WRITABLE_BOOK, bold("Previous Seasons", NamedTextColor.YELLOW),
            if (previous.isEmpty()) listOf(text("No finished seasons yet.", NamedTextColor.DARK_GRAY))
            else previous.take(6).flatMap { (w, v) ->
                listOf(
                    text("${w.id} (${date(w.start)} - ${w.end?.let { date(it) } ?: "now"})", NamedTextColor.GOLD),
                    text("  Kills ${v[ProfileStat.PVP_KILLS]}, deaths ${v[ProfileStat.PVP_DEATHS]}, best streak ${v[ProfileStat.BEST_KILL_STREAK]}, bounties ${v[ProfileStat.BOUNTY_CLAIMS]}"),
                )
            }))
        gui.setItem(L.RANK, GuiStyle.item(Material.NAME_TAG, bold("Rank", NamedTextColor.WHITE), listOf(
            p.rank?.let { title(it) } ?: text("None"),
        )))
        val featured = p.settings.featured.mapNotNull { AchievementRules.parseKey(it) }.mapNotNull { key ->
            val def = manager.achievement(key.achievementId) ?: return@mapNotNull null
            val tier = def.tier(key.tier) ?: return@mapNotNull null
            if (p.rows.none { it.key == key }) null else Triple(key, def, tier)
        }
        for ((i, slot) in L.FEATURED.withIndex()) {
            val f = featured.getOrNull(i)
            gui.setItem(slot, if (f == null) GuiStyle.item(Material.LIGHT_GRAY_DYE, text("Featured achievement slot", NamedTextColor.DARK_GRAY), if (own) listOf(text("Pick one in Customize.")) else emptyList())
            else GuiStyle.item(material(f.second.icon), bold(f.second.tierLabel(f.third), NamedTextColor.GOLD), listOf(text(f.second.description), line("Badge", f.second.category.label))))
        }
        if (own) {
            gui.setItem(L.OPEN_ACHIEVEMENTS, GuiStyle.item(Material.KNOWLEDGE_BOOK, bold("Achievements", NamedTextColor.LIGHT_PURPLE), listOf(text("Click to open."))) ) { pl, _ -> GuiStyle.clickSound(pl); openAchievements(pl) }
            gui.setItem(L.CUSTOMIZE, GuiStyle.item(Material.ARMOR_STAND, bold("Customize Profile", NamedTextColor.AQUA), listOf(text("Title, border, featured achievements and privacy.")))) { pl, _ ->
                GuiStyle.clickSound(pl); openCustomize(pl, p)
            }
        }
        gui.setItem(L.OPEN_LEADERBOARDS, GuiStyle.item(Material.GOLDEN_HELMET, bold("Leaderboards", NamedTextColor.GOLD), listOf(text("Click to open.")))) { pl, _ ->
            GuiStyle.clickSound(pl); openLeaderboards(pl, null, BoardPeriod.SEASON, 0, back = { openProfile(it, p.uuid, p.name) })
        }
        gui.setItem(L.CLOSE, GuiStyle.close()) { pl, _ -> pl.closeInventory() }
        plugin.guiManager.open(viewer, gui)
    }

    // ── Achievements ────────────────────────────────────────

    fun openAchievements(player: Player) {
        manager.unavailableReason(player)?.let { return manager.send(player, text(it, NamedTextColor.RED)) }
        manager.evaluate(player) {
            manager.read("achievements", { manager.store.achievements(player.uniqueId) to manager.calculator().player(player.uniqueId, includePrevious = false) }) { data, problem ->
                if (!player.isOnline) return@read
                if (data == null) return@read manager.send(player, text(problem ?: "Couldn't load your achievements.", NamedTextColor.RED))
                showMain(player, data.first, data.second)
            }
        }
    }

    private fun rowsFor(defs: List<AchievementDef>, rows: List<ProfileStore.AchievementRow>, periods: ProfileCalculator.Periods): List<AchievementRules.Row> =
        defs.mapNotNull { def ->
            val period = AchievementRules.period(def, periods.current?.id)
            val statuses = rows.filter { it.key.achievementId == def.id && it.key.period == period }.associate { it.key.tier to it.status }
            if (period == null) return@mapNotNull null
            val tier = AchievementRules.shownTier(def, statuses)
            val value = AchievementRules.value(def, periods.season, periods.lifetime)
            AchievementRules.Row(def, tier, value, AchievementRules.state(def, tier, value, statuses[tier.number]))
        }

    private fun showMain(player: Player, rows: List<ProfileStore.AchievementRow>, periods: ProfileCalculator.Periods) {
        val s = manager.settings ?: ProfileSettings.DEFAULTS
        val gui = CustomGui(title("&8${s.achievementsTitle}"), L.MAIN_SIZE)
        val all = rowsFor(manager.achievements, rows, periods)
        val totalTiers = manager.achievements.sumOf { it.tiers.size }
        val claimable = rows.count { it.status == "COMPLETED" }
        gui.setItem(L.SUMMARY, GuiStyle.item(Material.NETHER_STAR, bold("Your Achievements", NamedTextColor.GOLD), listOfNotNull(
            line("Tiers completed", "${rows.count { it.key.period == Seasons.PERMANENT || it.key.period == periods.current?.id }}/$totalTiers"),
            line("Ready to claim", "$claimable"),
            periods.current?.let { line("Season", it.id) },
        )))
        for ((i, category) in AchievementCategory.entries.withIndex()) {
            val inCategory = all.filter { it.def.category == category }
            gui.setItem(L.CATEGORY_SLOTS[i], GuiStyle.item(material(category.icon), bold(category.label, NamedTextColor.YELLOW), listOf(
                line("Achievements", "${inCategory.size}"),
                line("Ready to claim", "${inCategory.count { it.state == AchievementRules.State.CLAIMABLE }}"),
                line("Fully claimed", "${inCategory.count { it.state == AchievementRules.State.CLAIMED }}"),
                text("Click to open.", NamedTextColor.DARK_GRAY),
            ))) { pl, _ -> GuiStyle.clickSound(pl); openCategory(pl, category, 0, AchievementRules.Sort.DEFAULT) }
        }
        gui.setItem(L.MAIN_BACK, GuiStyle.back("Back to Profile")) { pl, _ -> openProfile(pl, pl.uniqueId, pl.name) }
        gui.setItem(L.MAIN_CLOSE, GuiStyle.close()) { pl, _ -> pl.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    fun openCategory(player: Player, category: AchievementCategory, page: Int, sort: AchievementRules.Sort) {
        manager.unavailableReason(player)?.let { return manager.send(player, text(it, NamedTextColor.RED)) }
        manager.read("achievements", { manager.store.achievements(player.uniqueId) to manager.calculator().player(player.uniqueId, includePrevious = false) }) { data, problem ->
            if (!player.isOnline) return@read
            if (data == null) return@read manager.send(player, text(problem ?: "Couldn't load your achievements.", NamedTextColor.RED))
            showCategory(player, category, page, sort, data.first, data.second)
        }
    }

    private fun showCategory(player: Player, category: AchievementCategory, page: Int, sort: AchievementRules.Sort, rows: List<ProfileStore.AchievementRow>, periods: ProfileCalculator.Periods) {
        val list = AchievementRules.sort(rowsFor(manager.achievements.filter { it.category == category }, rows, periods), sort)
        val pages = L.pages(list.size, L.PAGE_SIZE)
        val p = page.coerceIn(0, pages - 1)
        val gui = CustomGui(title("&8${category.label}" + if (pages > 1) " (${p + 1}/$pages)" else ""), L.LIST_SIZE)
        for ((i, row) in list.drop(p * L.PAGE_SIZE).take(L.PAGE_SIZE).withIndex()) {
            val def = row.def
            val period = AchievementRules.period(def, periods.current?.id) ?: continue
            val statuses = rows.filter { it.key.achievementId == def.id && it.key.period == period }.associate { it.key.tier to it.status }
            if (!AchievementRules.revealed(def, statuses)) {
                gui.setItem(i, GuiStyle.item(Material.GRAY_DYE, bold("???", NamedTextColor.DARK_GRAY), listOf(text("A hidden achievement."))))
                continue
            }
            val value = row.value ?: 0
            val icon = when (row.state) {
                AchievementRules.State.LOCKED -> Material.GRAY_DYE
                AchievementRules.State.CLAIMED -> Material.LIME_DYE
                else -> material(def.icon)
            }
            val lore = ArrayList<Component>()
            if (def.description.isNotEmpty()) lore += text(def.description)
            lore += text(if (def.period == AchievementPeriod.SEASONAL) "Seasonal (resets each season)" else "Permanent", NamedTextColor.DARK_GRAY)
            lore += Component.empty()
            for (t in def.tiers) {
                val st = statuses[t.number]
                val mark = when (st) { "CLAIMED" -> "✔"; "COMPLETED", "CLAIMING" -> "★"; else -> if (value >= t.target) "★" else "•" }
                lore += text("$mark ${t.name ?: "Tier ${t.number}"}: ${t.target}" + if (t.rewards.isEmpty()) "" else " - ${t.rewards.joinToString(", ") { it.describe() }}",
                    if (st == "CLAIMED") NamedTextColor.GREEN else if (st != null) NamedTextColor.GOLD else NamedTextColor.GRAY)
            }
            lore += Component.empty()
            lore += text(AchievementRules.bar(value, row.tier.target), NamedTextColor.AQUA)
            lore += line("Progress", "$value/${row.tier.target}")
            lore += when (row.state) {
                AchievementRules.State.CLAIMABLE -> text(if (statuses[row.tier.number] == "COMPLETED") "Click to claim!" else "Complete! It will be recorded in a moment.", NamedTextColor.GREEN)
                AchievementRules.State.CLAIMING -> text("Claim in progress...", NamedTextColor.YELLOW)
                AchievementRules.State.CLAIMED -> text("All tiers claimed.", NamedTextColor.GREEN)
                AchievementRules.State.IN_PROGRESS -> text("In progress.", NamedTextColor.YELLOW)
                AchievementRules.State.LOCKED -> text("Not started.", NamedTextColor.DARK_GRAY)
            }
            val key = AchievementRules.Key(def.id, row.tier.number, period)
            val claimable = statuses[row.tier.number] == "COMPLETED"
            gui.setItem(i, GuiStyle.item(icon, bold(def.tierLabel(row.tier), if (row.state == AchievementRules.State.LOCKED) NamedTextColor.GRAY else NamedTextColor.GOLD), lore)) { pl, _ ->
                if (!claimable) return@setItem GuiStyle.errorSound(pl)
                manager.claim(pl, key) { outcome ->
                    if (!pl.isOnline) return@claim
                    when (outcome) {
                        is LifestealProfileManager.ClaimOutcome.Claimed -> {
                            GuiStyle.successSound(pl)
                            manager.send(pl, text("Claimed ", NamedTextColor.GREEN).append(text(def.tierLabel(row.tier), NamedTextColor.GOLD))
                                .append(text(if (outcome.rewards.isEmpty()) "." else ": ${outcome.rewards.joinToString(", ") { it.describe() }}.", NamedTextColor.GREEN)))
                        }
                        LifestealProfileManager.ClaimOutcome.Pending -> manager.send(pl, text("Claimed. Your reward is queued and arrives shortly.", NamedTextColor.YELLOW))
                        is LifestealProfileManager.ClaimOutcome.Refused -> { GuiStyle.errorSound(pl); manager.send(pl, text(outcome.reason, NamedTextColor.RED)) }
                    }
                    openCategory(pl, category, p, sort)
                }
            }
        }
        if (list.isEmpty()) gui.setItem(22, GuiStyle.item(Material.BARRIER, text("No achievements here yet.", NamedTextColor.GRAY)))
        gui.setItem(L.LIST_BACK, GuiStyle.back()) { pl, _ -> openAchievements(pl) }
        if (p > 0) gui.setItem(L.LIST_PREV, GuiStyle.previousPage(p, pages)) { pl, _ -> GuiStyle.pageSound(pl); openCategory(pl, category, p - 1, sort) }
        if (p < pages - 1) gui.setItem(L.LIST_NEXT, GuiStyle.nextPage(p, pages)) { pl, _ -> GuiStyle.pageSound(pl); openCategory(pl, category, p + 1, sort) }
        gui.setItem(L.LIST_CLOSE, GuiStyle.close()) { pl, _ -> pl.closeInventory() }
        gui.setItem(L.LIST_SORT, GuiStyle.item(Material.HOPPER, bold("Sort: ${sort.label}", NamedTextColor.YELLOW), listOf(text("Click for: ${sort.next().label}")))) { pl, _ ->
            GuiStyle.clickSound(pl); openCategory(pl, category, 0, sort.next())
        }
        plugin.guiManager.open(player, gui)
    }

    // ── Leaderboards ────────────────────────────────────────

    fun openLeaderboards(player: Player, category: BoardCategory?, period: BoardPeriod, page: Int, back: ((Player) -> Unit)? = null) {
        val s = manager.settings
        manager.unavailableReason(player)?.let { return manager.send(player, text(it, NamedTextColor.RED)) }
        val categories = manager.boardCategories()
        if (s == null || !s.boardsEnabled || categories.isEmpty()) return manager.send(player, text("Leaderboards are turned off.", NamedTextColor.RED))
        val cat = category?.takeIf { it in categories } ?: categories.first()
        val per = if (period == BoardPeriod.SEASON && !cat.seasonal) BoardPeriod.ALL_TIME else if (period == BoardPeriod.ALL_TIME && !cat.allTime) BoardPeriod.SEASON else period
        if (cat.ranked) return openRankedBoard(player, cat, page, back)
        val boards = manager.boards
        if (boards == null) {
            manager.send(player, text("Building the leaderboards...", NamedTextColor.YELLOW))
            return manager.rebuildBoards { if (player.isOnline && manager.boards != null) openLeaderboards(player, cat, per, page, back) }
        }
        val list = boards.lists[cat to per].orEmpty()
        val entries = list.map { Triple(it.position, it.uuid.toString(), format(cat, it.value)) }
        val own = Leaderboards.positionOf(list, player.uniqueId)
        showBoard(player, cat, per, page, back, entries, own?.let { "#${it.position} (${format(cat, it.value)})" }, boards.builtAt,
            if (per == BoardPeriod.SEASON && boards.seasonId == null) "There is no Lifesteal season yet." else null)
    }

    private fun format(category: BoardCategory, value: Long): String = when (category) {
        BoardCategory.KD -> "%.2f".format(value / 100.0)
        BoardCategory.PLAYTIME -> "${value}h"
        else -> value.toString()
    }

    private fun openRankedBoard(player: Player, cat: BoardCategory, page: Int, back: ((Player) -> Unit)?) {
        val type = if (cat == BoardCategory.TEAM_ELO) RatingType.TEAM_ELO else RatingType.PLAYER_ELO
        // The existing Ranked Team Wars leaderboard (issue #1367): same ordering, eligibility and cache.
        plugin.lifestealRankedStatsManager.loadLeaderboard(type, page.coerceAtLeast(0), L.BOARD_SLOTS.size, player.uniqueId) { board, problem ->
            if (!player.isOnline) return@loadLeaderboard
            if (board == null) return@loadLeaderboard manager.send(player, text(problem ?: "Ranked leaderboards are unavailable.", NamedTextColor.RED))
            val entries = board.page.entries.map { e -> Triple(e.position, if (type == RatingType.TEAM_ELO) "team:${e.name ?: e.subjectId.take(8)}" else e.subjectId, "${e.rating} Elo (${e.wins}W ${e.losses}L)") }
            val own = board.viewerPosition?.let { "#$it" } ?: "Not ranked (${board.viewerMatches}/${board.minimumMatches} rated matches)"
            showBoard(player, cat, BoardPeriod.SEASON, board.page.page, back, entries, own, null, null, board.page.pages)
        }
    }

    private fun showBoard(
        player: Player, cat: BoardCategory, period: BoardPeriod, page: Int, back: ((Player) -> Unit)?,
        entries: List<Triple<Int, String, String>>, own: String?, builtAt: Long?, notice: String?, fixedPages: Int? = null,
    ) {
        val s = manager.settings ?: ProfileSettings.DEFAULTS
        val perPage = L.BOARD_SLOTS.size
        val pages = fixedPages ?: Leaderboards.pages(entries.size, perPage)
        val p = page.coerceIn(0, pages - 1)
        val shown = if (fixedPages != null) entries else entries.drop(p * perPage).take(perPage)
        val gui = CustomGui(title("&8${s.leaderboardsTitle}"), L.LIST_SIZE)
        gui.setItem(L.BOARD_HEADER, GuiStyle.item(material(cat.icon), bold(cat.label, NamedTextColor.GOLD), listOfNotNull(
            line("Period", period.label),
            if (cat == BoardCategory.KD) line("Minimum", "${s.kdMinimumFights} kills + deaths") else null,
            builtAt?.let { line("Updated", "${(System.currentTimeMillis() - it) / 60_000L} min ago") },
            notice?.let { text(it, NamedTextColor.YELLOW) },
        )))
        for ((i, e) in shown.withIndex()) {
            val slot = L.BOARD_SLOTS.getOrNull(i) ?: break
            val (position, subject, value) = e
            val medal = when (position) { 1 -> NamedTextColor.GOLD; 2 -> NamedTextColor.WHITE; 3 -> NamedTextColor.RED; else -> NamedTextColor.YELLOW }
            val uuid = runCatching { UUID.fromString(subject) }.getOrNull()
            val item = if (uuid != null) GuiStyle.skull(Bukkit.getOfflinePlayer(uuid), bold("#$position ${nameOf(uuid)}", medal), listOf(text(value, NamedTextColor.WHITE)))
            else GuiStyle.item(Material.WHITE_BANNER, bold("#$position ${subject.removePrefix("team:")}", medal), listOf(text(value, NamedTextColor.WHITE)))
            gui.setItem(slot, item) { pl, _ ->
                if (uuid != null && pl.hasPermission(LifestealProfileManager.PERMISSION_OTHERS)) { GuiStyle.clickSound(pl); openProfile(pl, uuid, nameOf(uuid)) }
            }
        }
        if (shown.isEmpty()) gui.setItem(22, GuiStyle.item(Material.BARRIER, text("Nobody is on this leaderboard yet.", NamedTextColor.GRAY)))
        for ((i, c) in manager.boardCategories().take(L.BOARD_CATEGORY_SLOTS.size).withIndex()) {
            val selected = c == cat
            gui.setItem(L.BOARD_CATEGORY_SLOTS[i], GuiStyle.item(if (selected) Material.LIME_STAINED_GLASS_PANE else material(c.icon), bold(c.label, if (selected) NamedTextColor.GREEN else NamedTextColor.YELLOW),
                listOf(text(if (selected) "Selected" else "Click to view.")))) { pl, _ -> GuiStyle.clickSound(pl); openLeaderboards(pl, c, period, 0, back) }
        }
        if (back != null) gui.setItem(L.BOARD_BACK, GuiStyle.back()) { pl, _ -> back(pl) }
        if (p > 0) gui.setItem(L.BOARD_PREV, GuiStyle.previousPage(p, pages)) { pl, _ -> GuiStyle.pageSound(pl); openLeaderboards(pl, cat, period, p - 1, back) }
        if (p < pages - 1) gui.setItem(L.BOARD_NEXT, GuiStyle.nextPage(p, pages)) { pl, _ -> GuiStyle.pageSound(pl); openLeaderboards(pl, cat, period, p + 1, back) }
        if (cat.seasonal && cat.allTime) {
            val other = if (period == BoardPeriod.SEASON) BoardPeriod.ALL_TIME else BoardPeriod.SEASON
            gui.setItem(L.BOARD_PERIOD, GuiStyle.item(Material.CLOCK, bold("Showing: ${period.label}", NamedTextColor.AQUA), listOf(text("Click for: ${other.label}")))) { pl, _ ->
                GuiStyle.clickSound(pl); openLeaderboards(pl, cat, other, 0, back)
            }
        }
        gui.setItem(L.BOARD_SELF, GuiStyle.skull(player, bold("Your placement", NamedTextColor.GREEN), listOf(text(own ?: "Not on this leaderboard yet.", NamedTextColor.WHITE))))
        gui.setItem(L.BOARD_CLOSE, GuiStyle.close()) { pl, _ -> pl.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Customize ───────────────────────────────────────────

    private fun openCustomize(player: Player, p: LifestealProfileManager.Profile) {
        val s = manager.settings ?: ProfileSettings.DEFAULTS
        val gui = CustomGui(title("&8Customize Profile"), L.CUSTOM_SIZE)
        val titles = manager.unlockedTitles(p.rows)
        val borders = manager.unlockedBorders(p.rows)
        gui.setItem(L.CUSTOM_TITLE, GuiStyle.item(Material.NAME_TAG, bold("Profile Title", NamedTextColor.GOLD), listOf(
            line("Current", p.settings.title?.let { titles[it] }?.let { plugin.commsManager.parseLegacy(it).let { c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c) } } ?: "None"),
            line("Unlocked", "${titles.size}"),
            text("Earned from achievement rewards.", NamedTextColor.DARK_GRAY),
        ))) { pl, _ ->
            val options = listOf<Pair<String?, Component>>(null to text("No title", NamedTextColor.GRAY)) + titles.map { (k, v) -> k to title(v) }
            pick(pl, "Pick a Title", options.map { (k, label) -> GuiStyle.item(Material.NAME_TAG, label) to { pp: Player -> save(pp, p, p.settings.copy(title = k)) } }) { openProfile(it, p.uuid, p.name) }
        }
        gui.setItem(L.CUSTOM_BORDER, GuiStyle.item(material(ProfileDecorations.borderMaterial(p.settings.border ?: s.defaultBorder), Material.GRAY_STAINED_GLASS_PANE), bold("Profile Border", NamedTextColor.AQUA), listOf(
            line("Current", ProfileDecorations.borderLabel(p.settings.border ?: s.defaultBorder)),
            line("Unlocked", "${borders.size}"),
        ))) { pl, _ ->
            pick(pl, "Pick a Border", borders.sorted().map { color ->
                GuiStyle.item(material(ProfileDecorations.borderMaterial(color), Material.GRAY_STAINED_GLASS_PANE), text(ProfileDecorations.borderLabel(color), NamedTextColor.WHITE)) to
                    { pp: Player -> save(pp, p, p.settings.copy(border = color.takeIf { it != s.defaultBorder })) }
            }) { openProfile(it, p.uuid, p.name) }
        }
        gui.setItem(L.CUSTOM_FEATURED, GuiStyle.item(Material.ITEM_FRAME, bold("Featured Achievements", NamedTextColor.LIGHT_PURPLE), listOf(
            line("Featured", "${p.settings.featured.size}/${ProfileDecorations.MAX_FEATURED}"),
            text("Click a completed achievement to add or remove it.", NamedTextColor.DARK_GRAY),
        ))) { pl, _ ->
            val options = p.rows.mapNotNull { row ->
                val def = manager.achievement(row.key.achievementId) ?: return@mapNotNull null
                val tier = def.tier(row.key.tier) ?: return@mapNotNull null
                val keyText = AchievementRules.keyText(row.key)
                val selected = keyText in p.settings.featured
                GuiStyle.item(if (selected) Material.LIME_DYE else material(def.icon), bold(def.tierLabel(tier), if (selected) NamedTextColor.GREEN else NamedTextColor.GOLD),
                    listOf(text(if (row.key.period == Seasons.PERMANENT) "Permanent" else "Season ${row.key.period}"), text(if (selected) "Featured - click to remove" else "Click to feature"))) to { pp: Player ->
                    val next = if (selected) p.settings.featured - keyText else (p.settings.featured + keyText).takeLast(ProfileDecorations.MAX_FEATURED)
                    save(pp, p, p.settings.copy(featured = next))
                }
            }
            pick(pl, "Feature Achievements", options) { openProfile(it, p.uuid, p.name) }
        }
        if (s.allowPrivate) {
            gui.setItem(L.CUSTOM_PRIVACY, GuiStyle.toggle("Private Profile", p.settings.private, listOf(text("Private: other players can't open your profile."), text("You still appear on leaderboards.")))) { pl, _ ->
                save(pl, p, p.settings.copy(private = !p.settings.private))
            }
        }
        gui.setItem(L.CUSTOM_BACK, GuiStyle.back("Back to Profile")) { pl, _ -> openProfile(pl, p.uuid, p.name) }
        gui.setItem(L.CUSTOM_CLOSE, GuiStyle.close()) { pl, _ -> pl.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    private fun save(player: Player, p: LifestealProfileManager.Profile, next: ProfileStore.Settings) {
        manager.saveSettings(player, next) { ok ->
            if (!player.isOnline) return@saveSettings
            if (ok) GuiStyle.successSound(player) else { GuiStyle.errorSound(player); manager.send(player, text("That choice isn't available any more.", NamedTextColor.RED)) }
            openProfile(player, p.uuid, p.name)
        }
    }

    /** A one-page picker (at most 45 options) with Back. */
    private fun pick(player: Player, title: String, options: List<Pair<org.bukkit.inventory.ItemStack, (Player) -> Unit>>, back: (Player) -> Unit) {
        val gui = CustomGui(title("&8$title"), L.LIST_SIZE)
        for ((i, o) in options.take(L.PAGE_SIZE).withIndex()) gui.setItem(i, o.first) { pl, _ -> GuiStyle.clickSound(pl); o.second(pl) }
        if (options.isEmpty()) gui.setItem(22, GuiStyle.item(Material.BARRIER, text("Nothing unlocked yet.", NamedTextColor.GRAY)))
        gui.setItem(L.LIST_BACK, GuiStyle.back()) { pl, _ -> back(pl) }
        gui.setItem(L.LIST_CLOSE, GuiStyle.close()) { pl, _ -> pl.closeInventory() }
        plugin.guiManager.open(player, gui)
    }
}
