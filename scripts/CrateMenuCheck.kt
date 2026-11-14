// Checks the crate GUI redesign (issue #1514): browser layout, preview pagination, rarity tiers,
// displayed chances, hidden rewards, Select / Select 3 pick rules, and the wiring that keeps keys,
// draws and payouts exactly as they were.
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/crate-menu-check.init.gradle.kts crateMenuCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.crate.CrateMenuLayout as M
import com.liam.joshymc.crate.CrateOdds
import com.liam.joshymc.crate.CrateRarity
import com.liam.joshymc.crate.CrateRewardText as T
import com.liam.joshymc.crate.CrateSelection as S
import com.liam.joshymc.manager.CratePreviewLayout
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

/** The text of the function starting at [signature] up to its closing brace at class level. */
private fun body(src: String, signature: String): String {
    val start = src.indexOf(signature)
    check("found '$signature'", start >= 0)
    val end = src.indexOf("\n    }\n", start)
    return src.substring(start, if (end < 0) src.length else end)
}

private fun count(text: String, what: String): Int = text.windowed(what.length) { it == what }.count { it }

private fun info(
    amount: Int = 3, weight: Int = 1, total: Int = 40, rarity: CrateRarity.Tier? = null,
    hidden: Boolean = false, unavailable: String? = null, pick: Boolean = false, name: String = "Diamond",
) = T.Info(name, amount, weight, total, rarity, hidden, unavailable, pick)

fun main() {
    // ── 1-4: browser menu (selection, permanent / monthly navigation) ──
    run {
        val empty = M.page(0, 0, 0)
        check("no crates: 27 slots, one page, no cells", empty.size == 27 && empty.pageCount == 1 && empty.cells.isEmpty())
        check("close at bottom center", empty.close == empty.size - 5)
        check("no page buttons on one page", empty.previous == null && empty.next == null)

        val five = M.page(0, 5, 0)
        check("five permanent crates: one row of 27 slots", five.size == 27 && five.cells.size == 5)
        check("five permanent: label at row start", five.labels == mapOf(9 to M.Section.PERMANENT))
        check("five permanent: centered in columns 1..7", five.cells.keys.sorted() == listOf(11, 12, 13, 14, 15))
        check("five permanent: in order", five.cells.toSortedMap().values.map { it.index } == (0 until 5).toList())

        val mixed = M.page(1, 5, 0)
        check("monthly first, then permanent", mixed.labels == mapOf(9 to M.Section.MONTHLY, 18 to M.Section.PERMANENT))
        check("one monthly crate centered", mixed.cells[13] == M.Cell(M.Section.MONTHLY, 0))
        check("mixed: 36 slots", mixed.size == 36)
        check("cells never on a label or button", mixed.cells.keys.none { it in mixed.labels.keys || it == mixed.close || it == M.INFO_SLOT })
        check("cells in the content rows", mixed.cells.keys.all { it / 9 in 1 until mixed.size / 9 - 1 })

        for (monthly in 0..12) for (permanent in 0..20) {
            val pages = M.pageCount(monthly, permanent)
            val seen = mutableListOf<M.Cell>()
            for (p in 0 until pages) {
                val page = M.page(monthly, permanent, p)
                check("$monthly/$permanent p$p: size fits", page.size in 27..54 && page.size % 9 == 0)
                check("$monthly/$permanent p$p: cells in bounds", page.cells.keys.all { it in 9 until page.size - 9 })
                check("$monthly/$permanent p$p: labels column 0", page.labels.keys.all { it % 9 == 0 })
                check("$monthly/$permanent p$p: crates never in column 0 or 8", page.cells.keys.all { it % 9 in 1..7 })
                check("$monthly/$permanent p$p: prev only after page 0", (page.previous != null) == (p > 0))
                check("$monthly/$permanent p$p: next only before last", (page.next != null) == (p < pages - 1))
                seen += page.cells.toSortedMap().values
            }
            val expected = (0 until monthly).map { M.Cell(M.Section.MONTHLY, it) } + (0 until permanent).map { M.Cell(M.Section.PERMANENT, it) }
            check("$monthly/$permanent: every crate exactly once, in order", seen == expected)
        }
        check("all 12 monthly + 5 permanent fit one page", M.pageCount(12, 5) == 1 && M.page(12, 5, 0).cells.size == 17)
        check("out-of-range page clamps", M.page(0, 40, 99).page == M.pageCount(0, 40) - 1 && M.page(0, 40, -2).page == 0)
        check("even rows keep the middle column empty", M.columns(2) == listOf(2, 4) && M.columns(6) == listOf(0, 1, 2, 4, 5, 6))
    }

    // ── 5: preview pagination (browser preview with its button row) ──
    run {
        for (total in listOf(0, 1, 7, 9, 14, 21, 44, 45, 46, 90, 91, 200)) {
            val pages = M.previewPageCount(total)
            val size = M.previewSize(total)
            check("$total: size is rows + one button row", size % 9 == 0 && size in 18..54)
            val buttons = listOf(M.previewBack(size), M.previewPrevious(size), M.previewInfo(size), M.previewNext(size), M.previewClose(size))
            check("$total: buttons in the bottom row", buttons.all { it / 9 == size / 9 - 1 } && buttons.toSet().size == 5)
            val shown = mutableListOf<Int>()
            for (p in 0 until pages) {
                val start = M.previewPageStart(total, p)
                val n = M.previewPageSize(total, p)
                check("$total p$p: continues the previous page", start == shown.size)
                val slots = M.previewSlots(n)
                check("$total p$p: one slot per reward above the buttons", slots.size == n && slots.toSet().size == n && slots.all { it < size - 9 })
                repeat(n) { shown += start + it }
            }
            check("$total: every reward once, in order", shown == (0 until total).toList())
        }
        check("crate-block preview layout unchanged (14 = 18 slots)", CratePreviewLayout.size(14) == 18)
    }

    // ── 6-9: names, quantities, chances, hidden rewards ──
    run {
        check("2.5% (1 in 40)", CrateOdds.describe(1, 40) == "2.5% (1 in 40)")
        check("50%", CrateOdds.describe(50, 100) == "50%")
        check("12.5% no odds", CrateOdds.describe(1, 8) == "12.5%")
        check("10% shows odds boundary", CrateOdds.describe(1, 10) == "10% (1 in 10)")
        check("0.25%", CrateOdds.describe(1, 400) == "0.25% (1 in 400)")
        check("tiny chance", CrateOdds.describe(1, 1_000_000) == "<0.01% (1 in 1000000)")
        check("non-integer odds", CrateOdds.describe(3, 100) == "3% (1 in 33.3)")
        check("100%", CrateOdds.describe(5, 5) == "100%")
        check("zero total = unknown", CrateOdds.describe(1, 0) == "Unknown")
        check("locale independent", run {
            val old = java.util.Locale.getDefault()
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val text = CrateOdds.describe(1, 40)
            java.util.Locale.setDefault(old)
            text == "2.5% (1 in 40)"
        })
        // Chances are exactly weight / total, summing to 100% over a crate.
        val weights = listOf(50, 30, 15, 4, 1)
        check("chances sum to 100", kotlin.math.abs(weights.sumOf { CrateOdds.percent(it, weights.sum())!! } - 100.0) < 1e-9)

        val legendary = CrateRarity.DEFAULTS.first { it.id == "legendary" }
        val preview = T.lines(info(rarity = legendary), T.Context.PREVIEW)
        check("preview lines: rarity, quantity, chance, status", preview.map { it.kind } ==
            listOf(T.Kind.RARITY, T.Kind.QUANTITY, T.Kind.CHANCE, T.Kind.STATUS))
        check("preview quantity", preview[1].value == "x3")
        check("preview chance from weight", preview[2].label == "Chance" && preview[2].value == "2.5% (1 in 40)")
        check("preview obtainable", preview[3].value == "Obtainable" && preview[3].good)
        check("no rarity line without a rarity", T.lines(info(), T.Context.PREVIEW).none { it.kind == T.Kind.RARITY })
        check("unavailable reward says why", T.lines(info(unavailable = "only on Survival"), T.Context.PREVIEW).last().let {
            !it.good && it.value == "Unavailable: only on Survival"
        })
        check("pick crates label the chance as mass-open", T.lines(info(pick = true), T.Context.PREVIEW).first { it.kind == T.Kind.CHANCE }.label == "Mass-open chance")
        check("pick screens show no chance", T.lines(info(), T.Context.PICK).none { it.kind == T.Kind.CHANCE })
        check("reel shows no chance", T.lines(info(), T.Context.REEL).none { it.kind == T.Kind.CHANCE })
        check("name kept", T.name(info(name = "Frostbite"), T.Context.PREVIEW) == "Frostbite")

        val secret = info(hidden = true, name = "Golden Crest")
        check("hidden: preview conceals", T.name(secret, T.Context.PREVIEW) == T.MYSTERY_NAME &&
            T.lines(secret, T.Context.PREVIEW).first { it.kind == T.Kind.QUANTITY }.value == "???")
        check("hidden: real chance still shown", T.lines(secret, T.Context.PREVIEW).first { it.kind == T.Kind.CHANCE }.value == "2.5% (1 in 40)")
        check("hidden: reel conceals", T.name(secret, T.Context.REEL) == T.MYSTERY_NAME)
        check("hidden: revealed when won", T.name(secret, T.Context.RESULT) == "Golden Crest")
        check("hidden: visible when picking", T.name(secret, T.Context.PICK) == "Golden Crest")
    }

    // ── Rarity tiers (presentation only) ──
    run {
        val defaults = CrateRarity.parse(true, null)
        check("missing section = six default tiers", defaults.tiers.keys.toList() == listOf("common", "uncommon", "rare", "epic", "legendary", "mythic"))
        check("tier lookup is case-insensitive", defaults.tier(" Legendary ")?.label == "Legendary")
        check("unset rarity = none", defaults.tier(null) == null && !defaults.unknown(null))
        check("unknown rarity flagged, no tier", defaults.tier("ultra") == null && defaults.unknown("ultra"))
        check("disabled = no tiers shown", CrateRarity.parse(false, null).tier("rare") == null)
        val custom = CrateRarity.parse(true, listOf(
            CrateRarity.Raw("Seasonal", "Seasonal", "#12ab34"),
            CrateRarity.Raw("bad id!", "x", "#000000"),
            CrateRarity.Raw("nocolor", "x", "red"),
            CrateRarity.Raw("seasonal", "dup", "#000000"),
            CrateRarity.Raw("plain", null, "FFFFFF"),
        ))
        check("custom tier read", custom.tier("seasonal") == CrateRarity.Tier("seasonal", "Seasonal", 0x12AB34))
        check("blank label = capitalised id", custom.tier("plain")?.label == "Plain")
        check("broken entries skipped and reported", custom.tiers.size == 2 && custom.problems.size == 3)
        check("colour parsing", CrateRarity.parseColor("#FFAA00") == 0xFFAA00 && CrateRarity.parseColor("#FFAA0") == null && CrateRarity.parseColor(null) == null)
    }

    // ── 12-13, 16, 19, 23-24: pick rules ──
    run {
        check("pick one", S.toggle(emptySet(), 2, 5) == S.Toggle.Changed(setOf(2)))
        check("click again deselects", S.toggle(setOf(2), 2, 5) == S.Toggle.Changed(emptySet()))
        check("fourth pick refused", S.toggle(setOf(0, 1, 2), 3, 5) == S.Toggle.Full)
        check("deselect allowed when full", S.toggle(setOf(0, 1, 2), 1, 5) == S.Toggle.Changed(setOf(0, 2)))
        check("stale / forged slot refused", S.toggle(emptySet(), 7, 5) == S.Toggle.Invalid && S.toggle(emptySet(), -1, 5) == S.Toggle.Invalid)
        check("complete needs exactly 3 existing", S.complete(setOf(0, 1, 2), 3) && !S.complete(setOf(0, 1), 3) && !S.complete(setOf(0, 1, 5), 3))
        val shot = S.OneShot()
        check("one payout per confirmation screen", shot.take() && !shot.take() && !shot.take() && shot.used)
    }

    // ── Wiring: payouts, keys and draws unchanged (10-11, 14-15, 20-28) ──
    val mgr = File("src/main/kotlin/com/liam/joshymc/manager/CrateManager.kt").readText()

    val selectConfirm = body(mgr, "private fun openSelectConfirm(")
    val select3Confirm = body(mgr, "private fun openSelect3Confirm(")
    for ((name, text) in listOf("Select" to selectConfirm, "Select 3" to select3Confirm)) {
        val take = text.indexOf("if (!claim.take()) return@setItem")
        val stale = text.indexOf("if (pickStale(p, crate)) return@setItem", take)
        val key = text.indexOf("if (!isKey(keyInHand, crate.id))", take)
        val consume = text.indexOf("consumeOneKey(p)")
        check("$name: one-shot gate first", take in 0 until stale)
        check("$name: stale check before the key", stale in 0 until key)
        check("$name: key check before consuming", key in 0 until consume)
        check("$name: exactly one key consumed", count(text, "consumeOneKey(") == 1)
        check("$name: result screen only after payout", text.indexOf("openPickResult(") > text.indexOf("giveItemSafely("))
        check("$name: items delivered are reward items, never display stacks", "giveItemSafely(p, rewardItem)" in text && "giveItemSafely(p, rewardDisplay" !in text)
    }
    check("Select: inventory-full refuses before the key", selectConfirm.indexOf("hasFreeSlot") in 0 until selectConfirm.indexOf("consumeOneKey("))
    check("Select 3: fit check before the key", select3Confirm.indexOf("select3RewardsFit(p, rewardItems)") in 0 until select3Confirm.indexOf("consumeOneKey("))

    val selectGui = body(mgr, "private fun openSelectGui(")
    check("Select picker never consumes or pays", "consumeOneKey" !in selectGui && "giveItemSafely" !in selectGui && "buildRewardItem" !in selectGui)
    check("Select picker click re-checks the crate", "if (pickStale(p, crate)) return@setItem" in selectGui)
    val select3Gui = body(mgr, "private fun openSelect3Gui(")
    check("Select 3 picker never consumes or pays", "consumeOneKey" !in select3Gui && "giveItemSafely" !in select3Gui)
    check("Select 3 picker uses the pick rules", "CrateSelection.toggle(" in select3Gui && "CrateSelection.complete(" in select3Gui)
    check("Select 3 session ends on close (disconnect safe)", "gui.onClose = { p -> select3Sessions.remove(p.uniqueId) }" in select3Gui)

    val stale = body(mgr, "private fun pickStale(")
    check("stale = a different crate definition", "crates[crate.id] !== crate" in stale)
    check("stale re-runs the open rules", "crateProblem(crate, randomDraw = false) ?: gamemodeProblem(player, crate)" in stale)

    val result = body(mgr, "private fun openPickResult(")
    check("result screen pays nothing", listOf("consumeOneKey", "giveItemSafely", "buildRewardItem", "claimPendingReward", "depositItemSafely").none { it in result })
    val reelResult = body(mgr, "private fun showReelResult(")
    check("reel result only draws", listOf("consumeOneKey", "giveItemSafely", "claimPendingReward", "selectWeightedReward").none { it in reelResult })

    val spin = body(mgr, "private fun scheduleAnimationStep(")
    val pulse = body(mgr, "private fun schedulePulseStep(")
    for ((name, text) in listOf("Spin" to spin, "Pulse" to pulse)) {
        check("$name: paid before the result shows", text.indexOf("finishInFlight(player, pendingId") in 0 until text.indexOf("showReelResult(inv, crate, winner)"))
        check("$name: reveals the stored winner", "showReelResult(inv, crate, winner)" in text)
        check("$name: only closes its own GUI", "player.openInventory.topInventory == inv" in text)
    }
    check("Spin winner still lands from the stored draw", "val incoming = if (step == maxSteps - 4) winner else selectWeightedReward(crate)" in spin)

    // 28: the draw itself is untouched and never reads rarity, odds or display text.
    val draw = body(mgr, "private fun selectWeightedReward(")
    check("weighted draw unchanged", "val totalWeight = crate.rewards.sumOf { it.weight }" in draw &&
        "var random = (Math.random() * totalWeight).toInt()" in draw && "random -= reward.weight" in draw)
    for (forbidden in listOf("rarity", "hidden", "CrateOdds", "CrateRarity")) {
        check("draw never reads $forbidden", forbidden !in draw && forbidden !in body(mgr, "private fun selectDistinctWeightedRewards("))
    }
    // Issue #1529: the row is claimed once (conditional PENDING -> DELIVERING) before anything is
    // given, and only deleted after the player was saved (CrateDeliveryProtocol.deliver, covered
    // by crateRewardIntegrityCheck).
    check("claim still takes the pending row before giving", body(mgr, "private fun claimPendingReward(").let {
        val backup = it.indexOf("pendingStore.beginDelivery(pendingId")
        backup in 0 until it.indexOf("plugin.depositItemSafely(player, item.clone())") &&
            "CrateDeliveryProtocol.deliver(" in it
    })
    check("join recovery unchanged", "deliverPendingRewards(player)" in body(mgr, "fun onPlayerJoin("))

    // 27: config compatibility — the new keys are optional and saved back by the editor.
    val load = body(mgr, "private fun loadCrates(")
    check("rarity optional", "rewardSection.getString(\"rarity\")" in load && "it.isNotEmpty()" in load)
    check("hidden defaults to false", "rewardSection.getBoolean(\"hidden\", false)" in load)
    check("listed defaults to true", "crateSection.getBoolean(\"listed\", true)" in load)
    check("unknown rarity never blocks a crate", load.substringAfter("rarities.unknown(rarity)").substringBefore("val hidden").let { "problems.add" !in it })
    val save = body(mgr, "private fun saveCrates(")
    check("editor keeps rarity / hidden / listed", "\$rewardPath.rarity" in save && "\$rewardPath.hidden" in save && "\$path.listed" in save)

    // Browser: permission re-checked on click, never opens or takes keys.
    val browser = File("src/main/kotlin/com/liam/joshymc/crate/CrateBrowserMenu.kt").readText()
    check("browser checks permission on open and click", count(browser, "hasPermission(PERMISSION)") >= 2)
    check("browser re-reads the crate on click", "crates.getCrate(crateId)" in browser && "!current.listed" in browser)
    check("browser never opens crates or touches keys", listOf("openCrate", "consumeOneKey", "giveKey", "createKeyStack", "giveItemSafely").none { it in browser })
    check("browser opens through GuiManager", "plugin.guiManager.open(player, gui)" in browser)
    check("browser icon is not a real key", "crateKeyKey" !in body(mgr, "fun crateIcon("))
    val command = File("src/main/kotlin/com/liam/joshymc/command/CrateCommand.kt").readText()
    check("/crate opens the browser for players with joshymc.crate.use",
        "sender is Player && sender.hasPermission(\"joshymc.crate.use\")) plugin.crateManager.browser.open(sender)" in command)

    // 17-18: GUI click protection is GuiManager's (unchanged): top-inventory clicks cancelled,
    // shift / double-click / number keys blocked, drags cancelled, 200 ms cooldown.
    val gm = File("src/main/kotlin/com/liam/joshymc/gui/GuiManager.kt").readText()
    check("shift-click blocked", "if (event.isShiftClick) {\n            event.isCancelled = true" in gm)
    check("double-click blocked", "ClickType.DOUBLE_CLICK" in gm)
    check("drag into GUI cancelled", "fun onDrag(" in gm && "event.isCancelled = true" in body(gm, "fun onDrag("))
    check("click cooldown", "CLICK_COOLDOWN_MS = 200L" in gm)

    // Config defaults present, deleting a tier stays deleted.
    val config = File("src/main/resources/config.yml").readText()
    check("bundled rarity tiers", "  rarities:\n    common:" in config && "mythic: {label: \"Mythic\", color: \"#FF5555\"}" in config)
    check("rarity tiers not backfilled one by one", "\"crates.rarities.\"" in File("src/main/kotlin/com/liam/joshymc/Joshymc.kt").readText())

    println("Crate menu checks passed ($checks checks).")
}
