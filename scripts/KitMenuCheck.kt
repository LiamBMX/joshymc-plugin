// Checks the /kit menus (issue #1379): the Bukkit-free kit catalog (gamemode isolation, categories,
// order), the menu layouts and the command scope. The Gradle build has no test source set, so it
// runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/kit-menu-check.init.gradle.kts kitMenuCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.gui.GuiIcon
import com.liam.joshymc.manager.KitCatalog
import com.liam.joshymc.manager.KitCatalog.Category
import com.liam.joshymc.manager.KitCatalog.Entry
import com.liam.joshymc.manager.KitGuiLayout
import java.io.File
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private val SURVIVAL = ServerGamemode.SURVIVAL
private val LIFESTEAL = ServerGamemode.LIFESTEAL
private val SURVIVAL_RANKS = listOf("scout", "pathfinder", "lumberjack", "trailblazer", "voyager", "ranger", "pioneer")

fun main() {
    // ── Main menu ───────────────────────────────────────
    check("main menu is 27 slots", KitCatalog.MAIN_SIZE == 27)
    check("main menu uses slots 11, 13, 15 only", KitCatalog.MAIN_SLOTS == listOf(11, 13, 15))
    check("main slots are all in the middle row", KitCatalog.MAIN_SLOTS.all { it / 9 == 1 })
    check("starter 11, rank 13, special 15",
        KitCatalog.STARTER_SLOT == 11 && KitCatalog.RANK_SLOT == 13 && KitCatalog.SPECIAL_SLOT == 15)
    val manager = File("src/main/kotlin/com/liam/joshymc/manager/KitManager.kt").readText()
    val mainMenu = manager.substringAfter("fun openKitGui(").substringBefore("private fun renderStarter(")
    check("main menu has no filler", "fill(" !in mainMenu && "FILLER" !in mainMenu && "GLASS" !in mainMenu)
    check("main menu title is Kits", "Component.text(\"Kits\"" in manager)
    check("default survival rank order is the donor tiers", KitCatalog.DEFAULT_SURVIVAL_RANK_ORDER == SURVIVAL_RANKS)

    // ── Gamemode isolation ──────────────────────────────
    check("missing gamemode = Survival", KitCatalog.gamemodeOf(null) == SURVIVAL && KitCatalog.gamemodeOf("  ") == SURVIVAL)
    check("lifesteal parsed", KitCatalog.gamemodeOf("Lifesteal") == LIFESTEAL)
    check("unknown gamemode shown nowhere", KitCatalog.gamemodeOf("skyblock") == null)
    check("unknown gamemode is flagged", KitCatalog.invalidGamemode("skyblock") && !KitCatalog.invalidGamemode(null))
    check("unknown category is flagged", KitCatalog.invalidCategory("vip") && !KitCatalog.invalidCategory("RANK"))

    val entries = listOf(
        Entry("starter", null, null),
        Entry("knight", null, null),
        Entry("pioneer", null, null),
        Entry("discord", null, null),
        Entry("scout", "survival", null),
        Entry("media", null, null),
        Entry("lumberjack", null, null),
        Entry("weird", "skyblock", "rank"),
        Entry("ls_starter", "lifesteal", "starter"),
        Entry("legacy", "lifesteal", null),
        Entry("rogue", "lifesteal", null),
        Entry("ls_media", "lifesteal", "special"),
    )

    val survivalRanks = KitCatalog.kitsFor(Category.RANK, entries, SURVIVAL, SURVIVAL_RANKS)
    check("survival rank kits in rank order, unlisted after: $survivalRanks",
        survivalRanks == listOf("scout", "lumberjack", "pioneer", "knight"))
    check("survival starter", KitCatalog.kitsFor(Category.STARTER, entries, SURVIVAL, SURVIVAL_RANKS) == listOf("starter"))
    check("survival special: media then discord",
        KitCatalog.kitsFor(Category.SPECIAL, entries, SURVIVAL, SURVIVAL_RANKS) == listOf("media", "discord"))

    val allSurvival = Category.entries.flatMap { KitCatalog.kitsFor(it, entries, SURVIVAL, SURVIVAL_RANKS) }
    check("no Lifesteal kit on Survival", allSurvival.none { it in setOf("ls_starter", "legacy", "rogue", "ls_media", "weird") })

    val lsOrder = listOf("rogue", "legacy")
    val lifestealAll = Category.entries.flatMap { KitCatalog.kitsFor(it, entries, LIFESTEAL, lsOrder) }
    check("no Survival kit on Lifesteal: $lifestealAll",
        lifestealAll.toSet() == setOf("ls_starter", "rogue", "legacy", "ls_media"))
    check("lifesteal rank kits follow the configured order",
        KitCatalog.kitsFor(Category.RANK, entries, LIFESTEAL, lsOrder) == listOf("rogue", "legacy"))
    check("lifesteal rank kits keep file order when unconfigured",
        KitCatalog.kitsFor(Category.RANK, entries, LIFESTEAL, emptyList()) == listOf("legacy", "rogue"))
    check("lifesteal explicit starter", KitCatalog.kitsFor(Category.STARTER, entries, LIFESTEAL, lsOrder) == listOf("ls_starter"))
    check("lifesteal special without discord is fine",
        KitCatalog.kitsFor(Category.SPECIAL, entries, LIFESTEAL, lsOrder) == listOf("ls_media"))
    check("absent special kits give an empty list",
        KitCatalog.kitsFor(Category.SPECIAL, listOf(Entry("starter", null, null)), SURVIVAL, SURVIVAL_RANKS).isEmpty())

    // Every visible kit is in exactly one category (nothing duplicated, nothing unreachable).
    val visibleSurvival = entries.filter { KitCatalog.visibleIn(it, SURVIVAL) }.map { it.name }
    check("every survival kit in exactly one category", allSurvival.sorted() == visibleSurvival.sorted())

    // Explicit category wins over the name.
    check("explicit category wins", KitCatalog.categoryOf(Entry("media", null, "rank"), SURVIVAL_RANKS) == Category.RANK)
    check("rank order is case-insensitive",
        KitCatalog.kitsFor(Category.RANK, listOf(Entry("Scout", null, null), Entry("Pioneer", null, null)), SURVIVAL, listOf("PIONEER", " scout"))
            == listOf("Pioneer", "Scout"))
    check("rankFor only for ordered ranks",
        KitCatalog.rankFor("Voyager", SURVIVAL_RANKS) == "voyager" && KitCatalog.rankFor("knight", SURVIVAL_RANKS) == null)

    // ── Submenu layout ──────────────────────────────────
    check("7 survival ranks fit a 27-slot submenu", KitGuiLayout.size(7) == 27)
    check("7 ranks fill the middle row in order", KitGuiLayout.slots(7) == (10..16).toList())
    check("media/discord sit either side of the center", KitGuiLayout.slots(2) == listOf(12, 14))
    check("close is the bottom-row center", KitGuiLayout.closeSlot(7) == 22)
    val submenu = manager.substringAfter("fun openCategoryGui(").substringBefore("private fun kitClick(")
    check("submenu has a Back to the main menu", "GuiStyle.back(" in submenu && "openKitGui(p)" in submenu)
    check("submenu has Close", "GuiStyle.close()" in submenu)

    // ── Discord / Media icons (issue #1412) ─────────────
    check("discord kit uses discord_icon", KitCatalog.guiIconFor("discord") == GuiIcon.DISCORD && GuiIcon.DISCORD.itemId == "discord_icon")
    check("media kit uses youtube_icon", KitCatalog.guiIconFor("Media") == GuiIcon.YOUTUBE && GuiIcon.YOUTUBE.itemId == "youtube_icon")
    check("lifesteal-prefixed kits get the icons",
        KitCatalog.guiIconFor("ls_media") == GuiIcon.YOUTUBE && KitCatalog.guiIconFor("lifesteal_discord") == GuiIcon.DISCORD &&
            KitCatalog.guiIconFor("discord_kit") == GuiIcon.DISCORD)
    check("other kits keep their own icon",
        listOf("starter", "scout", "knight", "pioneer", "legacy", "ls_starter", "mediakit", "discordx").all { KitCatalog.guiIconFor(it) == null })
    val kitIcon = manager.substringAfter("private fun kitIcon(").substringBefore("fun openKitPreviewGui(")
    check("kit icon uses the shared GuiStyle.icon", "KitCatalog.guiIconFor(kitDef.name)" in kitIcon && "GuiStyle.icon(" in kitIcon)
    check("kit icon still sets its own name and lore", "meta.displayName(" in kitIcon && "meta.lore(lore)" in kitIcon)

    // ── Claims stay revalidated ─────────────────────────
    val claim = manager.substringAfter("fun claimKit(").substringBefore("fun getCooldownRemaining(")
    check("claim re-resolves the kit in the current gamemode", "getKitFor(player, name)" in claim)
    // Issue #1428: through canUse, which keeps the kit permission for every Survival kit.
    check("claim still checks the permission", "canUse(player, kit)" in claim &&
        "if (!kit.isLifesteal) return player.hasPermission(kit.permission)" in manager)
    check("claim still checks the cooldown", "canClaim(player, name)" in claim)

    // ── Scope ───────────────────────────────────────────
    for (name in listOf("kit", "createkit", "editkit", "deletekit")) {
        check("/$name is NETWORK (filtered per gamemode by KitManager)", GamemodeAssignments.COMMANDS[name] == GamemodeScope.NETWORK)
    }

    println("All $checks kit menu checks passed.")
}
