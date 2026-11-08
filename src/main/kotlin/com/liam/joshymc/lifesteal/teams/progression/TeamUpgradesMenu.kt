package com.liam.joshymc.lifesteal.teams.progression

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.lifesteal.bounty.BountyMoney
import com.liam.joshymc.lifesteal.teams.LifestealTeamMenu
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Team
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

/**
 * The Team Upgrades GUI (issue #1426): `/team upgrades`, or the Team Upgrades button of the Lifesteal
 * team menu. Plain chests through the shared GuiManager (every click on the menu is cancelled and
 * shift/number-key moves into it are blocked, so no item can be taken or put in).
 *
 * The screens only **show** the cached state. Every button calls a manager action, which checks the
 * player again, and the store checks once more inside its transaction (current leader, ACTIVE team,
 * level, price, bank balance, and the exact tier the confirmation screen showed). A confirmation
 * screen carries one transaction id, so a double click or a stale screen can never buy twice.
 */
class TeamUpgradesMenu(private val plugin: Joshymc) {

    private val manager get() = plugin.lifestealTeamProgressionManager
    private val teams get() = plugin.lifestealTeamManager

    private fun text(content: String, color: TextColor = NamedTextColor.GRAY): Component =
        Component.text(content, color).decoration(TextDecoration.ITALIC, false)

    private fun title(raw: String): Component = text(if (raw.length > 28) raw.take(27) + "…" else raw, NamedTextColor.DARK_GRAY)

    /** Null (and a chat message) when [player] can't use the menu right now. */
    private fun context(player: Player): Pair<Team, TeamProgressionSettings>? {
        manager.unavailableReason(player)?.let { plugin.commsManager.send(player, text(it, NamedTextColor.RED)); return null }
        val team = teams.teamOf(player.uniqueId) ?: run {
            plugin.commsManager.send(player, text("You're not in a Lifesteal team. Create one with /team create <name>.", NamedTextColor.RED)); return null
        }
        val s = manager.settings ?: return null
        return team to s
    }

    // ── Overview ────────────────────────────────────────────

    fun open(player: Player, loaded: Boolean = false) {
        val (team, s) = context(player) ?: return
        val snap = manager.snapshot(team.id)
        if (snap == null && !loaded) {
            manager.reload(team.id) { if (player.isOnline) open(player, loaded = true) }
            return
        }
        val tiers = snap?.tiers.orEmpty()
        val perks = TeamPerks.of(tiers, s)
        val progress = TeamLevels.progress(snap?.totalXp ?: 0, s.levels)
        val leader = team.leader == player.uniqueId
        val gui = CustomGui(title(s.gui.mainTitle), 54)

        gui.setItem(4, GuiStyle.item(Material.WHITE_BANNER, manager.styledName(team), listOf(
            text("Team Level: ", NamedTextColor.GRAY).append(text("${progress.level}", NamedTextColor.GOLD)),
            bar(progress),
            text("XP: ${fmt(progress.xpIntoLevel)} / ${fmt(progress.xpForNext)} to Level ${progress.level + 1}", NamedTextColor.GRAY),
            text("Total Team XP: ${fmt(progress.totalXp)}", NamedTextColor.GRAY),
            text("This season (${manager.seasonId()}): ${fmt(snap?.seasonXp ?: 0)}", NamedTextColor.GRAY),
            Component.empty(),
            text("Team bank: ", NamedTextColor.GRAY).append(text("${BountyMoney.format(snap?.bankCents ?: 0)} / ${BountyMoney.format(perks.bankCapacityCents)}", NamedTextColor.GREEN)),
            Component.empty(),
            text(if (leader) "You lead this team: you can buy upgrades." else "Only the team leader can buy upgrades.", if (leader) NamedTextColor.GOLD else NamedTextColor.YELLOW),
        )))

        gui.setItem(13, GuiStyle.item(Material.EXPERIENCE_BOTTLE, text("Team Level & XP", NamedTextColor.AQUA), buildList {
            add(text("Level ${progress.level}", NamedTextColor.GOLD))
            add(bar(progress))
            add(text("Next level needs ${fmt(progress.xpForNext)} XP.", NamedTextColor.GRAY))
            add(text("Team XP bonus: +${perks.xpBonusPercent}%", NamedTextColor.GREEN))
            add(Component.empty())
            add(text("Earned by:", NamedTextColor.WHITE))
            for (source in XpSource.GAMEPLAY) {
                val amount = s.xp.amounts[source] ?: 0
                if (amount <= 0) continue
                val per = if (source == XpSource.EXPLORATION) " per 100 blocks" else if (source == XpSource.ORE_MINED) " per ore" else ""
                add(text(" ${source.label}: $amount XP$per (max ${fmt(s.xp.dailyCaps[source] ?: 0)}/day)", NamedTextColor.GRAY))
            }
            add(text(" Shared objectives: see below", NamedTextColor.GRAY))
            add(text("Team limit: ${fmt(s.xp.teamDailyCap)}/day, ${fmt(s.xp.memberDailyCap)} per member.", NamedTextColor.DARK_GRAY))
        }))

        val categorySlots = listOf(19, 20, 21, 23, 24, 25)
        UpgradeCategory.entries.forEachIndexed { index, category ->
            gui.setItem(categorySlots[index], categoryIcon(category, s, tiers[category] ?: 0, progress.level, snap?.bankCents ?: 0)) { p, _ ->
                GuiStyle.clickSound(p)
                openCategory(p, category)
            }
        }

        val utility = perks.utility
        gui.setItem(29, featureItem(Material.PLAYER_HEAD, "Member Contributions", TeamPerks.UtilityFeature.CONTRIBUTIONS in utility,
            "Team XP each member earned this season.")) { p, _ ->
            if (TeamPerks.UtilityFeature.CONTRIBUTIONS !in (manager.perks(team.id)?.utility ?: emptySet())) return@setItem GuiStyle.errorSound(p)
            GuiStyle.clickSound(p); openContributions(p)
        }
        gui.setItem(31, GuiStyle.item(Material.NAME_TAG, text("Team Style", NamedTextColor.LIGHT_PURPLE), listOf(
            text("Colour: ${snap?.color?.let { TeamStyle.label(it) } ?: "Default"}", NamedTextColor.GRAY),
            text("Title: ${snap?.title ?: "None"}", NamedTextColor.GRAY),
            text("${perks.colorCount} colour(s), ${snap?.titles?.size ?: 0} title(s) unlocked", NamedTextColor.DARK_GRAY),
            Component.empty(),
            text(if (leader) "Click to change" else "Click to view", NamedTextColor.YELLOW),
        ))) { p, _ -> GuiStyle.clickSound(p); openStyle(p) }
        gui.setItem(33, featureItem(Material.WRITABLE_BOOK, "Team Activity", TeamPerks.UtilityFeature.ACTIVITY_LOG in utility,
            "The latest Team XP your team earned.")) { p, _ ->
            if (TeamPerks.UtilityFeature.ACTIVITY_LOG !in (manager.perks(team.id)?.utility ?: emptySet())) return@setItem GuiStyle.errorSound(p)
            GuiStyle.clickSound(p); openActivity(p)
        }

        val cycle = snap?.cycle ?: manager.cycle(s)
        val done = s.objectives.list.count { snap?.objectives?.get(it.id)?.completed == true && snap.cycle == cycle }
        gui.setItem(38, GuiStyle.item(Material.TARGET, text("Shared Objectives", NamedTextColor.GREEN), listOf(
            text("$done/${s.objectives.list.size} completed (${cycleLabel(s.objectives.cycle)})", NamedTextColor.GRAY),
            Component.empty(), text("Click to view", NamedTextColor.YELLOW),
        ))) { p, _ -> GuiStyle.clickSound(p); openObjectives(p) }
        val claimable = TeamLevels.milestonesUpTo(progress.level, s.milestones).count { it !in (snap?.claimed ?: emptySet()) }
        gui.setItem(40, GuiStyle.item(if (claimable > 0) Material.NETHER_STAR else Material.BEACON, text("Team Milestones", NamedTextColor.LIGHT_PURPLE), listOf(
            text(if (claimable > 0) "$claimable reward(s) ready to claim!" else "No rewards waiting.", if (claimable > 0) NamedTextColor.GREEN else NamedTextColor.GRAY),
            text("Next: Level ${TeamLevels.nextMilestones(progress.level, 1, s.milestones).firstOrNull() ?: "-"}", NamedTextColor.GRAY),
            Component.empty(), text("Click to view", NamedTextColor.YELLOW),
        ))) { p, _ -> GuiStyle.clickSound(p); openMilestones(p) }
        gui.setItem(42, GuiStyle.item(Material.GOLD_BLOCK, text("Team Bank", NamedTextColor.GOLD), listOf(
            text("Balance: ${BountyMoney.format(snap?.bankCents ?: 0)}", NamedTextColor.GREEN),
            text("Limit: ${BountyMoney.format(perks.bankCapacityCents)}", NamedTextColor.GRAY),
            text("Coins here only buy team upgrades.", NamedTextColor.DARK_GRAY),
            Component.empty(), text("Click to deposit", NamedTextColor.YELLOW),
        ))) { p, _ -> GuiStyle.clickSound(p); openBank(p) }

        gui.setItem(GuiStyle.backSlot(54), GuiStyle.back("Team Menu")) { p, _ -> GuiStyle.clickSound(p); LifestealTeamMenu(plugin).open(p) }
        gui.setItem(GuiStyle.closeSlot(54), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    private fun featureItem(material: Material, name: String, unlocked: Boolean, what: String) =
        GuiStyle.item(if (unlocked) material else Material.GRAY_DYE, text(name, if (unlocked) NamedTextColor.AQUA else NamedTextColor.DARK_GRAY), listOf(
            text(what, NamedTextColor.GRAY),
            Component.empty(),
            if (unlocked) text("Click to view", NamedTextColor.YELLOW) else text("Locked: needs a Team Utility upgrade", NamedTextColor.RED),
        ))

    private fun categoryIcon(category: UpgradeCategory, s: TeamProgressionSettings, tier: Int, level: Int, bank: Long) =
        GuiStyle.item(material(category), text(category.label, NamedTextColor.GOLD).decorate(TextDecoration.BOLD), buildList {
            val c = s.category(category)
            add(text("Tier: ${roman(tier)} / ${roman(c.maxTier)}", NamedTextColor.WHITE))
            add(text(describe(category), NamedTextColor.GRAY))
            add(Component.empty())
            add(status(category, c, tier, level, bank))
            c.tiers.getOrNull(tier)?.let { next ->
                add(text("Next: ${effect(category, next, s)}", NamedTextColor.GRAY))
                add(text("Cost: ${BountyMoney.format(next.costCents)}  Level ${next.requiredLevel}", NamedTextColor.GRAY))
            }
            add(Component.empty())
            add(text("Click for tiers", NamedTextColor.YELLOW))
        })

    private fun status(category: UpgradeCategory, c: TeamProgressionSettings.Category, tier: Int, level: Int, bank: Long): Component {
        val next = c.tiers.getOrNull(tier)
        return when {
            !category.systemAvailable -> text("Not available yet (needs ${category.requiresSystem})", NamedTextColor.DARK_GRAY)
            !c.enabled -> text("Turned off on this server", NamedTextColor.DARK_GRAY)
            next == null -> text("✔ Fully upgraded", NamedTextColor.GREEN)
            level < next.requiredLevel -> text("✖ Needs Team Level ${next.requiredLevel}", NamedTextColor.RED)
            bank < next.costCents -> text("✖ Team bank needs ${BountyMoney.format(next.costCents)}", NamedTextColor.RED)
            else -> text("● Ready to buy", NamedTextColor.GREEN)
        }
    }

    // ── Category tiers & confirmation ───────────────────────

    fun openCategory(player: Player, category: UpgradeCategory) {
        val (team, s) = context(player) ?: return
        val snap = manager.snapshot(team.id)
        val c = s.category(category)
        val current = snap?.tiers?.get(category) ?: 0
        val level = TeamLevels.levelFor(snap?.totalXp ?: 0, s.levels)
        val bank = snap?.bankCents ?: 0
        val leader = team.leader == player.uniqueId
        val gui = CustomGui(title(category.label), 45)
        gui.setItem(4, GuiStyle.item(material(category), text(category.label, NamedTextColor.GOLD).decorate(TextDecoration.BOLD), listOf(
            text(describe(category), NamedTextColor.GRAY),
            text("Current tier: ${roman(current)}", NamedTextColor.WHITE),
            status(category, c, current, level, bank),
        )))
        val slots = (10..16) + (19..25)
        c.tiers.take(slots.size).forEachIndexed { index, tier ->
            val number = index + 1
            val owned = number <= current
            val next = number == current + 1
            val canBuy = next && leader && c.enabled && category.systemAvailable && level >= tier.requiredLevel && bank >= tier.costCents
            val material = when {
                owned -> Material.LIME_STAINED_GLASS_PANE
                canBuy -> Material.YELLOW_STAINED_GLASS_PANE
                next -> Material.ORANGE_STAINED_GLASS_PANE
                else -> Material.GRAY_STAINED_GLASS_PANE
            }
            gui.setItem(slots[index], GuiStyle.item(material, text("Tier ${roman(number)}", if (owned) NamedTextColor.GREEN else NamedTextColor.YELLOW), buildList {
                add(text(effect(category, tier, s), NamedTextColor.WHITE))
                add(text("Cost: ${BountyMoney.format(tier.costCents)} (team bank)", NamedTextColor.GRAY))
                add(text("Needs Team Level ${tier.requiredLevel}", if (level >= tier.requiredLevel) NamedTextColor.GRAY else NamedTextColor.RED))
                add(Component.empty())
                add(when {
                    owned -> text("✔ Owned", NamedTextColor.GREEN)
                    !next -> text("Buy tier ${roman(number - 1)} first", NamedTextColor.DARK_GRAY)
                    !leader -> text("Only the team leader can buy this", NamedTextColor.YELLOW)
                    canBuy -> text("Click to buy", NamedTextColor.GREEN)
                    else -> status(category, c, current, level, bank)
                })
            })) { p, _ ->
                if (!canBuy) return@setItem GuiStyle.errorSound(p)
                GuiStyle.clickSound(p)
                confirmPurchase(p, team.id, category, current)
            }
        }
        gui.setItem(GuiStyle.backSlot(45), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.setItem(GuiStyle.closeSlot(45), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    private fun confirmPurchase(player: Player, teamId: String, category: UpgradeCategory, expectedTier: Int) {
        val s = manager.settings ?: return
        val tier = s.category(category).tiers.getOrNull(expectedTier) ?: return
        val transactionId = UUID.randomUUID().toString()
        val detail = listOf(
            text("${category.label} ${roman(expectedTier)} → ${roman(expectedTier + 1)}", NamedTextColor.WHITE),
            text(effect(category, tier, s), NamedTextColor.GRAY),
            text("Cost: ${BountyMoney.format(tier.costCents)} from the team bank", NamedTextColor.GOLD),
        )
        confirm(player, "Buy ${category.label}?", detail, onConfirm = { p ->
            manager.purchase(p, teamId, category, expectedTier, transactionId) { if (p.isOnline) openCategory(p, category) }
        }, onCancel = { p -> openCategory(p, category) })
    }

    private fun confirm(player: Player, question: String, detail: List<Component>, onConfirm: (Player) -> Unit, onCancel: (Player) -> Unit) {
        val gui = CustomGui(title(question), 27)
        var used = false
        gui.setItem(11, GuiStyle.item(Material.LIME_CONCRETE, text("Confirm", NamedTextColor.GREEN), detail)) { p, _ ->
            if (used) return@setItem
            used = true
            p.closeInventory()
            onConfirm(p)
        }
        gui.setItem(13, GuiStyle.item(Material.PAPER, text(question, NamedTextColor.YELLOW), detail))
        gui.setItem(15, GuiStyle.item(Material.RED_CONCRETE, text("Cancel", NamedTextColor.RED))) { p, _ -> GuiStyle.clickSound(p); onCancel(p) }
        plugin.guiManager.open(player, gui)
    }

    // ── Objectives ──────────────────────────────────────────

    fun openObjectives(player: Player) {
        val (team, s) = context(player) ?: return
        val snap = manager.snapshot(team.id)
        val cycle = manager.cycle(s)
        val gui = CustomGui(title("Shared Objectives"), 54)
        gui.setItem(4, GuiStyle.item(Material.TARGET, text("Shared Objectives", NamedTextColor.GREEN), buildList {
            add(text("Every member's eligible play counts.", NamedTextColor.GRAY))
            add(text("Each objective pays once per ${cycleLabel(s.objectives.cycle)}.", NamedTextColor.GRAY))
            if (s.objectives.cycle == ObjectiveCycle.WEEKLY) {
                add(text("Resets in ${duration(TeamProgressCalendar.untilWeekEnd(System.currentTimeMillis(), s.xp.timezone))}", NamedTextColor.YELLOW))
            }
        }))
        ObjectivePool.entries.forEachIndexed { row, pool ->
            val base = (row + 1) * 9
            gui.setItem(base, GuiStyle.item(poolMaterial(pool), text(pool.label, NamedTextColor.GOLD)))
            s.objectives.list.filter { it.pool == pool }.take(8).forEachIndexed { i, objective ->
                val state = snap?.objectives?.get(objective.id)?.takeIf { snap.cycle == cycle }
                val progress = state?.progress ?: 0
                val complete = state?.completed == true
                gui.setItem(base + 1 + i, GuiStyle.item(if (complete) Material.LIME_DYE else Material.LIGHT_GRAY_DYE,
                    text(objective.name, if (complete) NamedTextColor.GREEN else NamedTextColor.WHITE), listOf(
                        bar(progress.toDouble() / objective.target),
                        text("${fmt(progress)} / ${fmt(objective.target)} ${objective.type.unit}", NamedTextColor.GRAY),
                        text("Reward: ${fmt(objective.xp)} Team XP", NamedTextColor.AQUA),
                        if (complete) text("✔ Completed", NamedTextColor.GREEN) else text("In progress", NamedTextColor.YELLOW),
                    )))
            }
        }
        gui.setItem(GuiStyle.backSlot(54), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.setItem(GuiStyle.closeSlot(54), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Milestones ──────────────────────────────────────────

    fun openMilestones(player: Player) {
        val (team, s) = context(player) ?: return
        val snap = manager.snapshot(team.id)
        val level = TeamLevels.levelFor(snap?.totalXp ?: 0, s.levels)
        val claimed = snap?.claimed.orEmpty()
        val reached = TeamLevels.milestonesUpTo(level, s.milestones)
        val upcoming = TeamLevels.nextMilestones(level, 7, s.milestones)
        // Unclaimed first so nothing claimable is ever pushed off the screen, then the latest claimed.
        val shown = (reached.filter { it !in claimed } + reached.filter { it in claimed }.takeLast(14)).take(21).sorted() + upcoming
        val gui = CustomGui(title("Team Milestones"), 54)
        gui.setItem(4, GuiStyle.item(Material.BEACON, text("Team Milestones", NamedTextColor.LIGHT_PURPLE), listOf(
            text("Your team is Level $level.", NamedTextColor.GRAY),
            text("Each milestone can be claimed once per team.", NamedTextColor.GRAY),
            text("Coins go to the team bank.", NamedTextColor.GRAY),
        )))
        val slots = (10..16) + (19..25) + (28..34) + (37..43)
        shown.take(slots.size).forEachIndexed { index, milestone ->
            val reward = TeamLevels.rewardFor(milestone, s.milestones)
            val isClaimed = milestone in claimed
            val ready = milestone <= level && !isClaimed
            gui.setItem(slots[index], GuiStyle.item(
                when { isClaimed -> Material.MINECART; ready -> Material.CHEST_MINECART; else -> Material.GRAY_DYE },
                text("Level $milestone", when { isClaimed -> NamedTextColor.GREEN; ready -> NamedTextColor.GOLD; else -> NamedTextColor.GRAY }),
                buildList {
                    if (reward.coinsCents > 0) add(text("${BountyMoney.format(reward.coinsCents)} for the team bank", NamedTextColor.WHITE))
                    reward.title?.let { add(text("Team title: $it", NamedTextColor.LIGHT_PURPLE)) }
                    add(Component.empty())
                    add(when {
                        isClaimed -> text("✔ Claimed", NamedTextColor.GREEN)
                        ready -> text("Click to claim", NamedTextColor.GREEN)
                        else -> text("Reach Level $milestone", NamedTextColor.RED)
                    })
                })) { p, _ ->
                if (!ready) return@setItem GuiStyle.errorSound(p)
                GuiStyle.successSound(p)
                manager.claimMilestone(p, milestone) { if (p.isOnline) openMilestones(p) }
            }
        }
        gui.setItem(GuiStyle.backSlot(54), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.setItem(GuiStyle.closeSlot(54), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Bank ────────────────────────────────────────────────

    fun openBank(player: Player) {
        val (team, s) = context(player) ?: return
        val snap = manager.snapshot(team.id)
        val perks = TeamPerks.of(snap?.tiers.orEmpty(), s)
        val balance = snap?.bankCents ?: 0
        val gui = CustomGui(title("Team Bank"), 36)
        gui.setItem(4, GuiStyle.item(Material.GOLD_BLOCK, text("Team Bank", NamedTextColor.GOLD), listOf(
            text("Balance: ${BountyMoney.format(balance)}", NamedTextColor.GREEN),
            text("Limit: ${BountyMoney.format(perks.bankCapacityCents)}", NamedTextColor.GRAY),
            Component.empty(),
            text("Any member can deposit their own coins.", NamedTextColor.GRAY),
            text("Coins can only be spent on team upgrades:", NamedTextColor.YELLOW),
            text("they can't be withdrawn and stay with the", NamedTextColor.YELLOW),
            text("team, even if you leave or it disbands.", NamedTextColor.YELLOW),
        )))
        val presets = DEPOSIT_PRESETS.map { it * 100 }.filter { it >= s.bank.minimumDepositCents }.take(7)
        val start = 10 + (7 - presets.size) / 2
        presets.forEachIndexed { index, cents ->
            gui.setItem(start + index, GuiStyle.item(Material.GOLD_NUGGET, text("Deposit ${BountyMoney.format(cents)}", NamedTextColor.GOLD), listOf(
                text("From your own balance.", NamedTextColor.GRAY), Component.empty(), text("Click to deposit", NamedTextColor.YELLOW),
            ))) { p, _ ->
                GuiStyle.clickSound(p)
                val reference = "deposit:${UUID.randomUUID()}"
                confirm(p, "Deposit ${BountyMoney.format(cents)}?", listOf(
                    text("${BountyMoney.format(cents)} from your balance to the team bank.", NamedTextColor.WHITE),
                    text("It can only be spent on team upgrades.", NamedTextColor.YELLOW),
                    text("It is never refunded.", NamedTextColor.RED),
                ), onConfirm = { q -> manager.deposit(q, cents, reference) { if (q.isOnline) openBank(q) } }, onCancel = { q -> openBank(q) })
            }
        }
        gui.setItem(22, GuiStyle.item(Material.OAK_SIGN, text("Other amounts", NamedTextColor.WHITE), listOf(
            text("/team bank deposit <amount>", NamedTextColor.YELLOW),
            text("Smallest deposit: ${BountyMoney.format(s.bank.minimumDepositCents)}", NamedTextColor.GRAY),
        )))
        gui.setItem(GuiStyle.backSlot(36), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.setItem(GuiStyle.closeSlot(36), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Style ───────────────────────────────────────────────

    fun openStyle(player: Player) {
        val (team, s) = context(player) ?: return
        val snap = manager.snapshot(team.id)
        val perks = TeamPerks.of(snap?.tiers.orEmpty(), s)
        val leader = team.leader == player.uniqueId
        val gui = CustomGui(title("Team Style"), 54)
        gui.setItem(4, GuiStyle.item(Material.NAME_TAG, manager.styledName(team), listOf(
            text("Colours come from Team Cosmetics upgrades,", NamedTextColor.GRAY),
            text("titles from Team Milestones.", NamedTextColor.GRAY),
            text(if (leader) "Click a colour or title to use it." else "Only the leader can change these.", NamedTextColor.YELLOW),
        )))
        val unlockedColors = s.cosmetics.colors.take(perks.colorCount)
        val colorSlots = listOf(10) + (11..16) + (19..25)
        (listOf<String?>(null) + s.cosmetics.colors).take(colorSlots.size).forEachIndexed { index, color ->
            val unlocked = color == null || color in unlockedColors
            val selected = snap?.color == color
            val named = color?.let { NamedTextColor.NAMES.value(it) } ?: NamedTextColor.GOLD
            gui.setItem(colorSlots[index], GuiStyle.item(if (unlocked) woolFor(color) else Material.GRAY_STAINED_GLASS_PANE,
                text(color?.let { TeamStyle.label(it) } ?: "Default (Gold)", named as TextColor), listOf(
                    when { selected -> text("✔ In use", NamedTextColor.GREEN); unlocked -> text(if (leader) "Click to use" else "Unlocked", NamedTextColor.YELLOW); else -> text("Locked", NamedTextColor.RED) },
                ))) { p, _ ->
                if (!leader || !unlocked || selected) return@setItem GuiStyle.errorSound(p)
                GuiStyle.clickSound(p)
                manager.setStyle(p, color, snap?.title) { if (p.isOnline) openStyle(p) }
            }
        }
        val titles = listOf<String?>(null) + snap?.titles.orEmpty()
        val titleSlots = (28..34) + (37..43)
        titles.take(titleSlots.size).forEachIndexed { index, title ->
            val selected = snap?.title == title
            gui.setItem(titleSlots[index], GuiStyle.item(Material.PAPER, text(title ?: "No title", NamedTextColor.LIGHT_PURPLE), listOf(
                if (selected) text("✔ In use", NamedTextColor.GREEN) else text(if (leader) "Click to use" else "Unlocked", NamedTextColor.YELLOW),
            ))) { p, _ ->
                if (!leader || selected) return@setItem GuiStyle.errorSound(p)
                GuiStyle.clickSound(p)
                manager.setStyle(p, snap?.color, title) { if (p.isOnline) openStyle(p) }
            }
        }
        gui.setItem(GuiStyle.backSlot(54), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.setItem(GuiStyle.closeSlot(54), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Utility views (read-only) ───────────────────────────

    private fun openContributions(player: Player) {
        val (team, _) = context(player) ?: return
        val season = manager.seasonId()
        manager.read("contributions ${team.id}", { it.contributions(team.id, season) }) { rows ->
            if (!player.isOnline || rows == null) return@read
            val gui = CustomGui(title("Member Contributions"), 36)
            gui.setItem(4, GuiStyle.item(Material.PLAYER_HEAD, text("Team XP this season", NamedTextColor.AQUA), listOf(text("Season $season", NamedTextColor.GRAY))))
            val slots = (10..16) + (19..25)
            val byMember = rows.associateBy { it.member }
            val list = (team.members.map { it.uuid } + rows.map { it.member }).distinct().sortedByDescending { byMember[it]?.xp ?: 0 }
            list.take(slots.size).forEachIndexed { index, uuid ->
                val current = team.isMember(uuid)
                gui.setItem(slots[index], GuiStyle.skull(Bukkit.getOfflinePlayer(uuid), text(teams.nameOf(uuid), if (current) NamedTextColor.WHITE else NamedTextColor.GRAY), listOf(
                    text("${fmt(byMember[uuid]?.xp ?: 0)} Team XP", NamedTextColor.GREEN),
                    if (current) Component.empty() else text("Former member", NamedTextColor.DARK_GRAY),
                )))
            }
            gui.setItem(GuiStyle.backSlot(36), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); open(p) }
            gui.setItem(GuiStyle.closeSlot(36), GuiStyle.close()) { p, _ -> p.closeInventory() }
            plugin.guiManager.open(player, gui)
        }
    }

    private fun openActivity(player: Player) {
        val (team, _) = context(player) ?: return
        val season = manager.seasonId()
        val breakdown = TeamPerks.UtilityFeature.SOURCE_BREAKDOWN in (manager.perks(team.id)?.utility ?: emptySet())
        manager.read("activity ${team.id}", { it.activity(team.id, 28) to if (breakdown) it.breakdown(team.id, season) else null }) { data ->
            if (!player.isOnline || data == null) return@read
            val (rows, sources) = data
            val gui = CustomGui(title("Team Activity"), 54)
            gui.setItem(4, GuiStyle.item(if (sources != null) Material.KNOWLEDGE_BOOK else Material.BOOK, text("Team XP by source", NamedTextColor.AQUA),
                if (sources == null) listOf(text("Locked: needs Team Utility III", NamedTextColor.RED))
                else XpSource.entries.map { text("${it.label}: ${fmt(sources[it.id] ?: 0)}", NamedTextColor.GRAY) }))
            val slots = (10..16) + (19..25) + (28..34) + (37..43)
            rows.take(slots.size).forEachIndexed { index, row ->
                val source = XpSource.fromId(row.source)
                gui.setItem(slots[index], GuiStyle.item(Material.PAPER, text("+${fmt(row.xp)} Team XP", NamedTextColor.GREEN), listOf(
                    text(source?.label ?: row.source, NamedTextColor.WHITE),
                    text(row.member?.let { teams.nameOf(it) } ?: "Team", NamedTextColor.GRAY),
                    text("${duration(System.currentTimeMillis() - row.at)} ago", NamedTextColor.DARK_GRAY),
                )))
            }
            gui.setItem(GuiStyle.backSlot(54), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); open(p) }
            gui.setItem(GuiStyle.closeSlot(54), GuiStyle.close()) { p, _ -> p.closeInventory() }
            plugin.guiManager.open(player, gui)
        }
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun bar(progress: TeamLevels.Progress): Component = bar(progress.fraction)

    private fun bar(fraction: Double): Component {
        val filled = (fraction.coerceIn(0.0, 1.0) * BAR).toInt()
        return text("|".repeat(filled), NamedTextColor.GREEN).append(text("|".repeat(BAR - filled), NamedTextColor.DARK_GRAY))
            .append(text(" ${(fraction.coerceIn(0.0, 1.0) * 100).toInt()}%", NamedTextColor.GRAY))
    }

    private fun describe(category: UpgradeCategory): String = when (category) {
        UpgradeCategory.STORAGE -> "More team storage slots."
        UpgradeCategory.HOME -> "More team homes."
        UpgradeCategory.ECONOMY -> "A bigger team bank."
        UpgradeCategory.PROGRESSION -> "Earn more Team XP (up to +${TeamProgressionConfig.MAX_XP_BONUS_PERCENT}%)."
        UpgradeCategory.COSMETICS -> "Team name colours."
        UpgradeCategory.UTILITY -> "Extra team statistics."
    }

    private fun effect(category: UpgradeCategory, tier: TeamProgressionSettings.Tier, s: TeamProgressionSettings): String = when (category) {
        UpgradeCategory.STORAGE -> "+${tier.value} team storage slots"
        UpgradeCategory.HOME -> "+${tier.value} team home(s)"
        UpgradeCategory.ECONOMY -> "+${BountyMoney.format(tier.value.toLong() * 100)} team bank limit"
        UpgradeCategory.PROGRESSION -> "+${tier.value}% Team XP in total"
        UpgradeCategory.COSMETICS -> "${tier.value.coerceAtMost(s.cosmetics.colors.size)} team colour(s) unlocked"
        UpgradeCategory.UTILITY -> "Unlocks: " + (TeamPerks.UtilityFeature.entries.getOrNull(tier.value - 1)?.label ?: "nothing new")
    }

    private fun material(category: UpgradeCategory): Material = when (category) {
        UpgradeCategory.STORAGE -> Material.CHEST
        UpgradeCategory.HOME -> Material.RED_BED
        UpgradeCategory.ECONOMY -> Material.GOLD_INGOT
        UpgradeCategory.PROGRESSION -> Material.EXPERIENCE_BOTTLE
        UpgradeCategory.COSMETICS -> Material.MAGENTA_DYE
        UpgradeCategory.UTILITY -> Material.COMPARATOR
    }

    private fun poolMaterial(pool: ObjectivePool): Material = when (pool) {
        ObjectivePool.COMBAT -> Material.IRON_SWORD
        ObjectivePool.MINING -> Material.IRON_PICKAXE
        ObjectivePool.EXPLORATION -> Material.COMPASS
        ObjectivePool.TEAMWORK -> Material.CAKE
    }

    private fun woolFor(color: String?): Material = when (color) {
        null, "gold" -> Material.ORANGE_WOOL
        "yellow" -> Material.YELLOW_WOOL
        "aqua" -> Material.LIGHT_BLUE_WOOL
        "dark_aqua" -> Material.CYAN_WOOL
        "green" -> Material.LIME_WOOL
        "dark_green" -> Material.GREEN_WOOL
        "blue", "dark_blue" -> Material.BLUE_WOOL
        "light_purple" -> Material.MAGENTA_WOOL
        "dark_purple" -> Material.PURPLE_WOOL
        "red" -> Material.RED_WOOL
        "dark_red" -> Material.BROWN_WOOL
        "gray" -> Material.LIGHT_GRAY_WOOL
        else -> Material.WHITE_WOOL
    }

    private fun cycleLabel(cycle: ObjectiveCycle): String = when (cycle) {
        ObjectiveCycle.WEEKLY -> "week"
        ObjectiveCycle.SEASON -> "season"
        ObjectiveCycle.NEVER -> "team, ever"
    }

    private fun fmt(value: Long): String = "%,d".format(value)

    private fun duration(millis: Long): String {
        val minutes = (millis / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${minutes / 60}h ${minutes % 60}m"
            else -> "${minutes / (24 * 60)}d ${(minutes / 60) % 24}h"
        }
    }

    companion object {
        private const val BAR = 20
        /** Bank deposit buttons, in coins (those below the minimum are hidden). */
        val DEPOSIT_PRESETS = listOf(10_000L, 50_000L, 100_000L, 250_000L, 500_000L, 1_000_000L, 2_500_000L)

        fun roman(n: Int): String {
            if (n <= 0) return "0"
            val values = listOf(10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
            var rest = n
            val out = StringBuilder()
            for ((value, symbol) in values) while (rest >= value) { out.append(symbol); rest -= value }
            return out.toString()
        }
    }
}
