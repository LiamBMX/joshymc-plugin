// Checks the /tags menus (issue #1393): the Bukkit-free ChatTagMenus layout, pagination and
// Bundled/Special classification, the bundled tags.yml permissions it relies on, and that the
// manager's menus keep the security rules (re-checked ownership, no filler, private previews).
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/chat-tag-menu-check.init.gradle.kts chatTagMenuCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.manager.ChatTagDefaults
import com.liam.joshymc.manager.ChatTagMenus
import com.liam.joshymc.manager.ChatTagMenus.Kind
import org.bukkit.configuration.file.YamlConfiguration
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

fun main() {
    // ── Main menu ───────────────────────────────────────
    check("main menu is 27 slots", ChatTagMenus.MAIN_SIZE == 27)
    check("Bundled Chat Tags in slot 12", ChatTagMenus.BUNDLED_SLOT == 12)
    check("Special Chat Tags in slot 14", ChatTagMenus.SPECIAL_SLOT == 14)

    val manager = File("src/main/kotlin/com/liam/joshymc/manager/ChatTagManager.kt").readText()
    val mainMenu = manager.substringAfter("fun openMainMenu(").substringBefore("fun openBundleMenu(")
    check("main menu sets exactly two slots", Regex("gui\\.setItem\\(").findAll(mainMenu).count() == 2)
    check("main menu sets only the two category slots",
        "ChatTagMenus.BUNDLED_SLOT" in mainMenu && "ChatTagMenus.SPECIAL_SLOT" in mainMenu)
    check("no filler anywhere in the tag menus", "filler" !in manager && "GLASS" !in manager)
    check("/tags still opens the main menu", "fun openCategoryMenu(player: Player) = openMainMenu(player)" in manager)
    check("main menu descriptions match the issue",
        "Browse themed collections of chat tags available" in mainMenu &&
            "through the JoshyMC store." in mainMenu &&
            "Browse individually unlockable and exclusive" in mainMenu)

    // ── 54-slot browse pages ────────────────────────────
    check("browse pages are 54 slots", ChatTagMenus.BROWSE_SIZE == 54)
    check("45 entries per page", ChatTagMenus.PAGE_SIZE == 45)
    check("nav slots 45/49/51/53",
        ChatTagMenus.PREV_SLOT == 45 && ChatTagMenus.BACK_SLOT == 49 &&
            ChatTagMenus.REMOVE_SLOT == 51 && ChatTagMenus.NEXT_SLOT == 53)
    val nav = setOf(ChatTagMenus.PREV_SLOT, ChatTagMenus.BACK_SLOT, ChatTagMenus.REMOVE_SLOT, ChatTagMenus.NEXT_SLOT)
    check("nav slots are in the bottom row", nav.all { it / 9 == 5 })
    check("entry slots are 0..44", ChatTagMenus.slots(100) == (0..44).toList())
    check("entry slots never hit nav", ChatTagMenus.slots(45).none { it in nav })
    check("a short page uses the first slots", ChatTagMenus.slots(3) == listOf(0, 1, 2))
    check("no entries, no slots", ChatTagMenus.slots(0).isEmpty() && ChatTagMenus.slots(-4).isEmpty())

    // ── Pagination ──────────────────────────────────────
    check("empty list has one page", ChatTagMenus.totalPages(0) == 1)
    check("45 fit on one page", ChatTagMenus.totalPages(45) == 1)
    check("46 need two pages", ChatTagMenus.totalPages(46) == 2)
    check("1000 tags = 23 pages (no page cap)", ChatTagMenus.totalPages(1000) == 23)
    val list = (1..100).toList()
    check("page 0 = first 45", ChatTagMenus.pageOf(list, 0) == (1..45).toList())
    check("page 1 = next 45", ChatTagMenus.pageOf(list, 1) == (46..90).toList())
    check("page 2 = the rest", ChatTagMenus.pageOf(list, 2) == (91..100).toList())
    check("stale page past the end clamps to the last", ChatTagMenus.pageOf(list, 9) == (91..100).toList())
    check("negative page clamps to the first", ChatTagMenus.pageOf(list, -1) == (1..45).toList())
    check("every entry is on exactly one page",
        (0 until ChatTagMenus.totalPages(list.size)).flatMap { ChatTagMenus.pageOf(list, it) } == list)
    check("no Previous on the first page", !ChatTagMenus.hasPrevious(0, 100))
    check("Next on the first of three", ChatTagMenus.hasNext(0, 100))
    check("no Next on the last page", !ChatTagMenus.hasNext(2, 100) && ChatTagMenus.hasPrevious(2, 100))
    check("single page has neither", !ChatTagMenus.hasNext(0, 10) && !ChatTagMenus.hasPrevious(0, 10))

    // ── Classification ──────────────────────────────────
    check("voucher category is Special", ChatTagMenus.kindOf(ChatTagDefaults.VOUCHER_CATEGORY) == Kind.SPECIAL)
    check("themed categories are Bundled", ChatTagMenus.kindOf("vibe") == Kind.BUNDLED && ChatTagMenus.kindOf("cosmic") == Kind.BUNDLED)
    check("a name that merely sounds special is still Bundled", ChatTagMenus.kindOf("special") == Kind.BUNDLED)
    val order = listOf("vibe", "voucher", "nature", "brand_new")
    check("bundles keep tags.yml order and drop Special", ChatTagMenus.bundles(order) == listOf("vibe", "nature", "brand_new"))
    check("Special display name", ChatTagMenus.displayName("voucher") == "Special Chat Tags")
    check("bundle display name", ChatTagMenus.displayName("cyberpunk") == "Cyberpunk")
    check("multi-word bundle display name", ChatTagMenus.displayName("brand_new") == "Brand New")

    // ── Bundled tags.yml: what the menus classify ───────
    val tags = YamlConfiguration.loadConfiguration(File("src/main/resources/tags.yml")).getConfigurationSection("tags")
    check("tags.yml has a tags section", tags != null)
    tags!!
    for (category in tags.getKeys(false)) {
        val section = tags.getConfigurationSection(category) ?: continue
        for (id in section.getKeys(false)) {
            val permission = section.getString("$id.permission")
            when (ChatTagMenus.kindOf(category)) {
                Kind.SPECIAL -> {
                    check("special $id has its own node", permission == ChatTagDefaults.VOUCHER_PERMISSION_PREFIX + id)
                    check("special $id is not a shared/bulk node", permission != null && !permission.endsWith(".*") && permission != "joshymc.tag.voucher")
                }
                // Bundled defaults are free (permission: null). The menus never grant anything; this pins
                // the documented state so a change here is noticed and the docs updated.
                Kind.BUNDLED -> check("bundled $category.$id is unchanged (free)", permission == null)
            }
        }
    }
    check("tags.yml has at least one bundle", ChatTagMenus.bundles(tags.getKeys(false).toList()).isNotEmpty())

    // ── Selection security (source checks on the manager) ──
    val click = manager.substringAfter("private fun onTagClick(").substringBefore("private fun addNavigation(")
    check("clicks re-read the tag by id", "getTag(tagId)" in click)
    check("clicks refuse a tag that moved or vanished", "tag == null || tag.category != category" in click)
    check("clicks re-check ownership before equipping",
        click.indexOf("canUse(player, tag)") in 0 until click.indexOf("setPlayerTag(player.uniqueId, tag.id)"))
    check("right-click previews before any equip path",
        click.indexOf("if (rightClick)") in 0 until click.indexOf("setPlayerTag("))
    val preview = manager.substringAfter("private fun sendPreview(").substringBefore("private fun onTagClick(")
    check("previews are private", "broadcast" !in preview && "Bukkit." !in preview && "setPlayerTag" !in preview)
    val remove = manager.substringAfter("private fun addRemoveTagButton(").substringBefore("/**\n     * Merge any tag")
    check("Remove Current Tag only unequips", "setPlayerTag(p.uniqueId, null)" in remove && "chat_tag_unlocks" !in remove)
    check("canUse still honors per-tag node, then voucher unlock",
        manager.contains("if (player.hasPermission(tag.permission)) return true") &&
            manager.contains("return hasUnlocked(player.uniqueId, tag.id)"))

    // ── Gamemode scope unchanged ────────────────────────
    check("/tag stays SURVIVAL-scoped", GamemodeAssignments.COMMANDS["tag"] == GamemodeScope.SURVIVAL)

    println("ChatTagMenuCheck: all $checks checks passed.")
}
