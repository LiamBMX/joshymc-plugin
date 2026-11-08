package com.liam.joshymc.lifesteal.ranks

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig.RankDef
import com.liam.joshymc.manager.KitCatalog
import com.liam.joshymc.manager.KitGuiLayout
import com.liam.joshymc.manager.KitManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

/**
 * `/lsranks` (issue #1428): every Lifesteal rank in hierarchy order with its description, status,
 * how it is obtained, requirements with the player's progress, benefits (unlocked or locked), kits
 * and cosmetics. Read-only except one button: claiming a progression rank whose requirements are
 * met, which goes through [LifestealRankManager.claimProgression] and is re-checked there. Nothing
 * here can buy or assign a rank.
 */
class LifestealRankMenu(private val plugin: Joshymc, private val manager: LifestealRankManager) {

    fun open(player: Player) {
        val settings = manager.settings ?: return
        val ranks = settings.ranks.filter { it.enabled }
        val size = KitGuiLayout.size(ranks.size)
        val gui = CustomGui(
            Component.text("Lifesteal Ranks", NamedTextColor.RED).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            size,
        )
        val owned = manager.ownedRanks(player.uniqueId)
        val current = LifestealRankRules.currentRank(settings, owned)

        gui.setItem(4, GuiStyle.skull(player, Component.text("Your Lifesteal Rank", NamedTextColor.GOLD), buildList {
            add(text("Current rank: ", NamedTextColor.GRAY).append(current?.let { name(it) } ?: text("None", NamedTextColor.WHITE)))
            val next = LifestealRankRules.nextProgressionRank(settings, current)
            if (next != null) {
                add(text("Next rank to earn: ", NamedTextColor.GRAY).append(name(next)))
                for (row in LifestealRankRules.progress(next, manager.stats(player.uniqueId, next))) add(requirementLine(row))
            }
            add(Component.empty())
            add(text("Ranks never reset with a season.", NamedTextColor.DARK_GRAY))
        }))

        val slots = KitGuiLayout.slots(ranks.size)
        for ((i, rank) in ranks.withIndex()) {
            if (i >= slots.size) break
            gui.setItem(slots[i], rankIcon(player, rank, owned)) { p, _ ->
                val fresh = manager.settings?.rank(rank.id)
                if (fresh != null && fresh.acquisition.progression && fresh.id !in manager.ownedRanks(p.uniqueId)) {
                    GuiStyle.clickSound(p)
                    p.closeInventory()
                    manager.claimProgression(p, fresh.id)
                } else {
                    GuiStyle.errorSound(p)
                }
            }
        }

        gui.setItem(GuiStyle.backSlot(size), GuiStyle.item(Material.CHEST, Component.text("Rank Kits", NamedTextColor.AQUA),
            listOf(text("Open the Lifesteal Rank Kits.", NamedTextColor.GRAY)))) { p, _ ->
            GuiStyle.clickSound(p)
            plugin.kitManager.openCategoryGui(p, KitCatalog.Category.RANK)
        }
        gui.setItem(KitGuiLayout.closeSlot(ranks.size), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    private fun rankIcon(player: Player, rank: RankDef, owned: Set<String>): ItemStack {
        val settings = manager.settings!!
        val unlocked = LifestealRankRules.reaches(settings, owned, rank)
        val isCurrent = LifestealRankRules.currentRank(settings, owned)?.id == rank.id
        val material = Material.matchMaterial(rank.cosmetics.icon)?.takeIf { it.isItem && !it.isAir } ?: Material.NAME_TAG
        val lore = mutableListOf<Component>()
        if (rank.description.isNotBlank()) lore += text(rank.description, NamedTextColor.GRAY)
        lore += Component.empty()
        lore += text("Status: ", NamedTextColor.GRAY).append(
            when {
                isCurrent -> text("Your current rank", NamedTextColor.GREEN)
                rank.id in owned -> text("Owned", NamedTextColor.GREEN)
                unlocked -> text("Included in your rank", NamedTextColor.GREEN)
                else -> text("Locked", NamedTextColor.RED)
            }
        )
        lore += text("Obtained: ", NamedTextColor.GRAY).append(text(acquisitionLabel(rank), NamedTextColor.WHITE))

        if (rank.requirements.isNotEmpty()) {
            lore += Component.empty()
            lore += text("Requirements", NamedTextColor.GOLD)
            for (row in LifestealRankRules.progress(rank, manager.stats(player.uniqueId, rank))) lore += requirementLine(row)
            if (rank.requirementScope == LifestealRankConfig.RequirementScope.SEASON) lore += text("PvP counts from this season's launch.", NamedTextColor.DARK_GRAY)
        }

        val perks = rank.perks.filterKeys { it !in settings.disabledPerks }.filterValues { it > 0 }
        if (perks.isNotEmpty() || rank.permissions.isNotEmpty() || rank.cosmetics.unlocks.isNotEmpty()) {
            lore += Component.empty()
            lore += text("Benefits" + if (unlocked) "" else " (locked)", if (unlocked) NamedTextColor.GREEN else NamedTextColor.RED)
            val color = if (unlocked) NamedTextColor.WHITE else NamedTextColor.DARK_GRAY
            for ((perk, value) in perks) lore += text("• ${perk.label}: $value", color)
            for (unlock in rank.cosmetics.unlocks) lore += text("• $unlock", color)
            lore += text("Includes every lower rank's benefits.", NamedTextColor.DARK_GRAY)
        }
        if (rank.kits.isNotEmpty()) {
            lore += Component.empty()
            lore += text("Kits: ", NamedTextColor.GRAY).append(text(rank.kits.joinToString(", ") { KitManager.displayName(it) }, NamedTextColor.AQUA))
        }
        if (rank.cosmetics.prefix.isNotBlank()) {
            lore += text("Prefix: ", NamedTextColor.GRAY).append(plugin.commsManager.parseLegacy(rank.cosmetics.prefix))
        }
        lore += Component.empty()
        val claimable = rank.acquisition.progression && rank.id !in owned &&
            LifestealRankRules.decideGrant(settings, owned, rank.id, LifestealRankRules.Source.PROGRESSION, false) == LifestealRankRules.Decision.GRANT &&
            LifestealRankRules.requirementsMet(rank, manager.stats(player.uniqueId, rank))
        when {
            claimable -> lore += text("Click to claim this rank", NamedTextColor.YELLOW)
            rank.acquisition.free && rank.id !in owned && !unlocked -> lore += text("Claim it free with /freerank", NamedTextColor.YELLOW)
        }

        val item = GuiStyle.item(material, name(rank).decoration(TextDecoration.BOLD, true), lore)
        item.editMeta { meta ->
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            if (isCurrent || claimable) meta.setEnchantmentGlintOverride(true)
        }
        return item
    }

    private fun acquisitionLabel(rank: RankDef): String {
        val ways = buildList {
            if (rank.acquisition.free) add("Free (/freerank)")
            if (rank.acquisition.progression) add("Earned by playing")
            if (rank.acquisition.store) add("Store")
        }
        return if (ways.isEmpty()) "Awarded by staff" else ways.joinToString(", ")
    }

    private fun requirementLine(row: LifestealRankRules.RequirementProgress): Component {
        val current = row.current?.toString() ?: "?"
        val mark = if (row.met) text(" ✔", NamedTextColor.GREEN) else Component.empty()
        return text("${row.type.label}: ", NamedTextColor.GRAY)
            .append(text("$current/${row.required}${row.type.unit}", if (row.met) NamedTextColor.GREEN else NamedTextColor.WHITE))
            .append(mark)
    }

    private fun name(rank: RankDef): Component =
        Component.text(rank.displayName, TextColor.fromHexString(rank.color) ?: NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)

    private fun text(s: String, color: NamedTextColor): Component = Component.text(s, color).decoration(TextDecoration.ITALIC, false)
}
