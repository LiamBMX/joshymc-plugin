package com.liam.joshymc.punish

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * /punish menus (issue #1298): the confirmation screen and the rules browser, which doubles as
 * the rule picker for `/punish <player>`. Every button calls back into [RulePunishManager], which
 * re-checks permissions server-side, so a menu never grants anything by itself.
 */
class PunishMenus(private val plugin: Joshymc) {

    private val manager get() = plugin.rulePunishManager

    fun openConfirmation(staff: Player, view: RulePunishManager.ConfirmationView) {
        val gui = CustomGui(Component.text("Confirm Punishment"), 27)
        val summary = manager.summaryLines(view.target.name, view.plan, view.staffName, view.evidence)
        gui.setItem(4, GuiStyle.skull(Bukkit.getOfflinePlayer(view.target.uuid), Component.text(view.target.name, NamedTextColor.GOLD), summary))

        val needsEvidence = view.plan.needsEvidence && view.evidence == null
        val confirmLore = buildList {
            add(Component.text(view.plan.describe(), NamedTextColor.RED))
            add(Component.text("for ${view.plan.rule.displayName}", NamedTextColor.GRAY))
            if (needsEvidence) {
                add(Component.empty())
                add(GuiStyle.error("Evidence or a written justification"))
                add(GuiStyle.error("is required. Click to type it."))
            }
        }
        gui.setItem(11, GuiStyle.item(Material.LIME_CONCRETE, Component.text("Confirm Punishment", NamedTextColor.GREEN), confirmLore)) { p, _ ->
            p.closeInventory()
            if (needsEvidence) {
                GuiStyle.errorSound(p)
                Msg.reply(p, Component.text("Click here and add the evidence: /punish confirm ${view.id} <evidence>", NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.suggestCommand("/punish confirm ${view.id} ")))
            } else {
                manager.confirm(p, view.id)
            }
        }
        gui.setItem(13, GuiStyle.item(Material.BOOK, Component.text("View History", NamedTextColor.AQUA),
            listOf(Component.text("This confirmation stays open.", NamedTextColor.GRAY)))) { p, _ ->
            p.closeInventory()
            manager.sendHistory(p, view.target, null)
            Msg.reply(p, Component.text("Confirm: /punish confirm ${view.id}", NamedTextColor.GRAY).clickEvent(ClickEvent.suggestCommand("/punish confirm ${view.id}")))
        }
        gui.setItem(15, GuiStyle.item(Material.RED_CONCRETE, Component.text("Cancel", NamedTextColor.RED))) { p, _ ->
            p.closeInventory()
            manager.cancel(p, view.id)
        }
        plugin.guiManager.open(staff, gui)
    }

    // ── Rules browser / picker ──────────────────────────────

    /** Category overview. With a [target], clicking a usable rule starts punishing them. */
    fun openCategories(viewer: Player, target: RulePunishManager.Target?) {
        val title = target?.let { "Punish: ${it.name}" } ?: "Server Rules"
        val gui = CustomGui(Component.text(title.take(28)), 45)
        val usable = manager.usableRules(viewer).map { it.id }.toSet()
        for ((index, category) in RuleCategory.entries.withIndex()) {
            val rules = manager.registry.inCategory(category)
            val count = rules.count { it.id in usable }
            val lore = listOf(
                Component.text("${rules.size} rule${if (rules.size == 1) "" else "s"}", NamedTextColor.GRAY),
                if (count > 0) Component.text("$count you can punish", NamedTextColor.GREEN) else Component.text("None you can punish", NamedTextColor.DARK_GRAY),
            )
            gui.setItem(CATEGORY_SLOTS[index], GuiStyle.item(categoryIcon(category), Component.text(category.displayName, NamedTextColor.GOLD), lore)) { p, _ ->
                GuiStyle.pageSound(p)
                openCategory(p, category, target)
            }
        }
        gui.setItem(GuiStyle.closeSlot(45), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(viewer, gui)
    }

    fun openCategory(viewer: Player, category: RuleCategory, target: RulePunishManager.Target?) {
        val rules = manager.registry.inCategory(category)
        val gui = CustomGui(Component.text(category.displayName.take(28)), 27)
        val level = manager.levelOf(viewer)
        val start = 13 - rules.size / 2
        for ((index, rule) in rules.withIndex()) {
            val canUse = level != null && !rule.informational && rule.minLevel != null && level.atLeast(rule.minLevel)
            val lore = mutableListOf<Component>()
            lore.add(Component.text("Rule #${rule.id} - ${rule.keyword}", NamedTextColor.DARK_GRAY))
            if (rule.informational) {
                lore.add(Component.text("Informational only. Can't be punished.", NamedTextColor.GRAY))
            } else {
                rule.tiers!!.forEachIndexed { i, slot ->
                    val alt = slot.alternate?.let { " (or ${it.describe()})" } ?: ""
                    lore.add(Component.text("${i + 1}. ${slot.primary.describe()}$alt", NamedTextColor.GRAY))
                }
                lore.add(Component.text("Needs ${rule.minLevel!!.displayName}+ - ${rule.gamemodes.joinToString(", ") { it.displayName }}", NamedTextColor.DARK_GRAY))
                if (target != null && canUse) {
                    val next = (manager.previousCount(target.uuid, rule) + 1)
                    lore.add(Component.empty())
                    lore.add(Component.text("${target.name}: offense $next", NamedTextColor.YELLOW))
                    lore.add(Component.text("Click to punish", NamedTextColor.GREEN))
                }
            }
            val material = when {
                rule.informational -> Material.BOOK
                canUse -> Material.PAPER
                else -> Material.GRAY_DYE
            }
            val color = if (canUse) NamedTextColor.WHITE else NamedTextColor.GRAY
            val onClick: ((Player, org.bukkit.event.inventory.InventoryClickEvent) -> Unit)? =
                if (target != null && canUse) { p, _ ->
                    p.closeInventory()
                    manager.begin(p, target, rule, PunishFlags())
                } else null
            gui.setItem(start + index, GuiStyle.item(material, Component.text(rule.displayName, color), lore), onClick)
        }
        gui.setItem(GuiStyle.backSlot(27), GuiStyle.back("Back to Categories")) { p, _ ->
            GuiStyle.pageSound(p)
            openCategories(p, target)
        }
        gui.setItem(GuiStyle.closeSlot(27), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(viewer, gui)
    }

    private fun categoryIcon(category: RuleCategory): Material = when (category) {
        RuleCategory.GENERAL -> Material.DIAMOND_SWORD
        RuleCategory.ACCOUNT -> Material.NAME_TAG
        RuleCategory.ECONOMY -> Material.GOLD_INGOT
        RuleCategory.CREDITS -> Material.EMERALD
        RuleCategory.CRATES -> Material.CHEST
        RuleCategory.SPAWNERS -> Material.SPAWNER
        RuleCategory.CLAIMS -> Material.GOLDEN_SHOVEL
        RuleCategory.REGIONS -> Material.FILLED_MAP
        RuleCategory.PVP -> Material.IRON_SWORD
        RuleCategory.TEAMS -> Material.WHITE_BANNER
        RuleCategory.KITS -> Material.LEATHER_CHESTPLATE
        RuleCategory.QUESTS -> Material.WRITABLE_BOOK
        RuleCategory.STOCKS -> Material.CLOCK
        RuleCategory.AUCTIONS -> Material.GOLD_NUGGET
        RuleCategory.CHAT -> Material.OAK_SIGN
        RuleCategory.COMMANDS -> Material.COMMAND_BLOCK
        RuleCategory.ITEMS -> Material.NETHER_STAR
        RuleCategory.PERFORMANCE -> Material.REDSTONE
        RuleCategory.STAFF -> Material.IRON_HELMET
        RuleCategory.LOOPHOLES -> Material.TRIPWIRE_HOOK
    }

    companion object {
        /** 20 categories: two full rows of 7, then 6 around the middle of the third row. */
        private val CATEGORY_SLOTS = (10..16).toList() + (19..25).toList() + listOf(28, 29, 30, 32, 33, 34)
    }
}
