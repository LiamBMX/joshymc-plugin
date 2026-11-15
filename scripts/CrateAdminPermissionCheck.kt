// Checks the split crate administration permissions and the crate editor audit (issue #1541):
// the Bukkit-free rules in CratePermissions and CrateEditorAudit, the delegated-command policy
// for /crate, plugin.yml, and the wiring in CrateCommand / CrateEditorCommand / CrateManager.
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/crate-admin-permission-check.init.gradle.kts crateAdminPermissionCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.crate.CrateEditorAudit
import com.liam.joshymc.crate.CrateEditorAudit.Operation
import com.liam.joshymc.crate.CrateEditorAudit.Reason
import com.liam.joshymc.crate.CrateEditorAudit.Result
import com.liam.joshymc.crate.CrateEditorAudit.RewardRef
import com.liam.joshymc.crate.CratePermissions as P
import com.liam.joshymc.staff.DelegatedCommandRules
import com.liam.joshymc.staff.DelegatedCommandRules.Decision
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

/** A sender holding exactly [nodes] (no op, no wildcard: LuckPerms grants are explicit). */
private fun holder(vararg nodes: String): (String) -> Boolean = { it in nodes }

private val ADMIN_SUBS = listOf("setlocation", "removelocation", "locations", "givekey", "force", "models", "rotate", "display", "monthly", "rewards", "diagnose")
private val PLAYER_SUBS = listOf("menu", "browse", "help", "list", "preview")

private fun allowedSubs(has: (String) -> Boolean) = ADMIN_SUBS.filter { P.allows(it, has) }.toSet()

/** Body of a Kotlin function in [src], from its signature to the next function at the same indent. */
private fun body(src: String, signature: String): String {
    val start = src.indexOf(signature)
    check("found $signature", start >= 0)
    val rest = src.substring(start + signature.length)
    val next = Regex("\\n    (private |override |internal )?fun ").find(rest)?.range?.first ?: rest.length
    return rest.substring(0, next)
}

fun main() {
    // ── 1. Node names and independence ──
    check("node names", P.GIVEKEY == "joshymc.crate.givekey" && P.FORCE == "joshymc.crate.force" &&
        P.MONTHLY == "joshymc.crate.monthly" && P.DISPLAY == "joshymc.crate.display" &&
        P.LOCATION == "joshymc.crate.location" && P.EDITOR == "joshymc.crateeditor" && P.RECOVER == "joshymc.crate.rewards.recover")
    check("legacy node is never a staff node", P.LEGACY_ADMIN !in P.STAFF_NODES)
    check("reward nodes are givekey, force and the editor only", P.REWARD_NODES == setOf(P.GIVEKEY, P.FORCE, P.EDITOR))
    check("every admin subcommand has exactly one node", P.SUBCOMMANDS.keys == ADMIN_SUBS.toSet() - "diagnose")

    // ── 2. Ordinary player (test 1) ──
    val player = holder("joshymc.crate.use")
    check("1. ordinary player: no admin subcommand", allowedSubs(player).isEmpty())
    check("1. ordinary player: player subcommands still allowed", PLAYER_SUBS.all { P.allows(it, player) })
    check("1. ordinary player: no tab completion", P.adminSubcommands(player).isEmpty())
    check("1. ordinary player is not crate staff", !P.isCrateStaff(player))

    // ── 3. Display-only staff (tests 2, 16, 17) ──
    val display = holder(P.DISPLAY)
    check("2. display: display, rotate, models, diagnose", allowedSubs(display) == setOf("display", "rotate", "models", "diagnose"))
    check("16. display can't give keys", !P.allows("givekey", display))
    check("16. display can't force", !P.allows("force", display))
    check("16. display can't change locations", !P.allows("setlocation", display) && !P.allows("removelocation", display))
    check("16. display can't change the monthly crate", !P.allows("monthly", display))
    check("16/17. display holds no reward-generating node", P.REWARD_NODES.none(display))

    // ── 4. Location-only staff (test 3) ──
    val location = holder(P.LOCATION)
    check("3. location: setlocation, removelocation, locations, diagnose",
        allowedSubs(location) == setOf("setlocation", "removelocation", "locations", "diagnose"))
    check("3. location can't mint keys", !P.allows("givekey", location) && !P.allows("force", location))
    check("3. location can't change models", !P.allows("display", location) && !P.allows("rotate", location) && !P.allows("models", location))

    // ── 5. Monthly (test 4) ──
    val monthly = holder(P.MONTHLY)
    check("4. monthly: only /crate monthly", allowedSubs(monthly) == setOf("monthly"))

    // ── 6. Givekey / force (tests 5, 6) ──
    check("5. givekey: only /crate givekey", allowedSubs(holder(P.GIVEKEY)) == setOf("givekey"))
    check("6. force: only /crate force", allowedSubs(holder(P.FORCE)) == setOf("force"))
    check("5/6. givekey and force are separate", !P.allows("force", holder(P.GIVEKEY)) && !P.allows("givekey", holder(P.FORCE)))

    // ── 7. Editor (test 7) ──
    check("7. editor alone grants no /crate admin subcommand", allowedSubs(holder(P.EDITOR)).isEmpty())
    check("7. no /crate node grants the editor", P.SUBCOMMANDS.values.none { it == P.EDITOR })

    // ── 8. Legacy broad node (test 8) ──
    val legacy = holder(P.LEGACY_ADMIN)
    check("8. joshymc.crate.admin alone grants nothing", allowedSubs(legacy).isEmpty() && P.adminSubcommands(legacy).isEmpty())
    check("8. joshymc.crate.admin is not crate staff", !P.isCrateStaff(legacy))
    check("8. unknown words need nothing (they print usage)", P.allows("nonsense", player))
    check("case-insensitive", !P.allows("GiveKey", player) && P.allows("GIVEKEY", holder(P.GIVEKEY)))

    // ── 9. Delegated commands (test 9) ──
    fun dc(cmd: String, canonical: String? = null) = DelegatedCommandRules.decide(cmd, canonical)
    for (sub in ADMIN_SUBS + listOf("inspect", "edit", "anything")) {
        check("9. delegated /crate $sub refused", dc("crate $sub x") == Decision.STAFF_SUBCOMMAND)
        check("9. delegated /crates $sub refused", dc("crates $sub") == Decision.STAFF_SUBCOMMAND)
        check("9. delegated namespaced /joshymc:crate $sub refused", dc("/joshymc:crate $sub") == Decision.STAFF_SUBCOMMAND)
        check("9. delegated alias resolved to crate refused ($sub)", dc("cr $sub", canonical = "crate") == Decision.STAFF_SUBCOMMAND)
    }
    for (sub in PLAYER_SUBS) check("9. delegated /crate $sub allowed", dc("crate $sub vote") == Decision.ALLOWED)
    check("9. delegated bare /crate allowed", dc("crate") == Decision.ALLOWED)
    check("9. delegated /crateeditor refused", dc("crateeditor") == Decision.STAFF_COMMAND && dc("editcrate", "crateeditor") == Decision.STAFF_COMMAND)
    check("9. player subcommands are exactly the shared list", DelegatedCommandRules.PLAYER_SUBCOMMANDS_ONLY["crate"] == P.PLAYER_SUBCOMMANDS &&
        P.PLAYER_SUBCOMMANDS == PLAYER_SUBS.toSet())
    check("9. other commands unaffected", dc("warp shop") == Decision.ALLOWED && dc("kit starter") == Decision.ALLOWED)

    // ── 10-15. Audit rows ──
    val ref = RewardRef(index = 3, material = "DIAMOND_SWORD", amount = 1, weight = 25, name = "&6Fancy  Sword=1",
        customItem = "autumns_edge", voucher = false, fingerprint = CrateEditorAudit.fingerprint("AAAA"))
    val add = CrateEditorAudit.details("vote", Result.SUCCESS, reward = ref)
    check("10. reward addition audit", add.startsWith("crate=vote result=SUCCESS") && "reward_index=3" in add &&
        "material=DIAMOND_SWORD" in add && "weight=25" in add && "custom_item=autumns_edge" in add && "fingerprint=" in add)
    check("10. names are cleaned (no colour codes, spaces or '=')", "name=Fancy_Sword_1" in add)
    check("10. snapshot itself is never logged", "AAAA" !in add)
    val fp = CrateEditorAudit.fingerprint("AAAA")!!
    check("fingerprint is 16 hex digits and stable", fp.length == 16 && fp.all { it in "0123456789abcdef" } && fp == CrateEditorAudit.fingerprint("AAAA"))
    check("fingerprints differ for different snapshots", fp != CrateEditorAudit.fingerprint("AAAB"))
    check("no snapshot = no fingerprint", CrateEditorAudit.fingerprint(null) == null && CrateEditorAudit.fingerprint("") == null)
    val voucher = CrateEditorAudit.details("vote", Result.SUCCESS, reward = ref.copy(voucher = true))
    check("17. premium voucher rewards are flagged", "voucher=true" in voucher)
    val remove = CrateEditorAudit.details("vote", Result.SUCCESS, reward = ref)
    check("11. reward deletion audit carries the reward", "reward_index=3" in remove && "fingerprint=$fp" in remove)
    val weight = CrateEditorAudit.details("vote", Result.SUCCESS, reward = ref, previous = "25", new = "50")
    check("12. weight audit has previous and new", "previous=25" in weight && "new=50" in weight)
    val delete = CrateEditorAudit.details("vote", Result.SUCCESS, previous = "Vote Crate", extra = mapOf("rewards" to "12", "locations" to "2"))
    check("13. crate deletion audit", "previous=Vote_Crate" in delete && "rewards=12" in delete && "locations=2" in delete)
    val rejected = CrateEditorAudit.details("vote", Result.REJECTED, Reason.NO_PERMISSION)
    check("14. rejected edits are distinguishable", rejected == "crate=vote result=REJECTED reason=no_permission")
    check("14. stale edits are distinguishable", "reason=reward_changed" in CrateEditorAudit.details("vote", Result.REJECTED, Reason.STALE, ref))
    check("15. failed saves are distinguishable", "result=FAILED" in CrateEditorAudit.details("vote", Result.FAILED, reward = ref))
    check("clean caps length", CrateEditorAudit.clean("x".repeat(200)).length == 49 && CrateEditorAudit.clean(null) == "-" && CrateEditorAudit.clean("  ") == "-")
    check("clean strips control characters", CrateEditorAudit.clean("a\nb\u0000c") == "a_bc")
    check("actions are unique and prefixed", Operation.entries.map { it.action }.toSet().size == Operation.entries.size &&
        Operation.entries.all { it.action.startsWith("CRATE_EDITOR_") })
    check("reward-affecting operations", listOf(Operation.REWARD_ADD, Operation.REWARD_REMOVE, Operation.REWARD_WEIGHT,
        Operation.DELETE, Operation.CREATE, Operation.KEY_ITEM, Operation.MODE).all { it.rewardAffecting } && !Operation.IDLE_PARTICLE.rewardAffecting)

    // ── Wiring: CrateCommand ──
    val command = File("src/main/kotlin/com/liam/joshymc/command/CrateCommand.kt").readText()
    check("CrateCommand never checks the legacy node", "joshymc.crate.admin" !in command)
    for ((fn, node) in listOf("handleForceOpen" to P.FORCE, "handleGiveKey" to P.GIVEKEY, "handleSetLocation" to P.LOCATION,
        "handleRemoveLocation" to P.LOCATION, "handleLocations" to P.LOCATION, "handleModels" to P.DISPLAY,
        "handleRotate" to P.DISPLAY, "handleDisplay" to P.DISPLAY, "handleMonthly" to P.MONTHLY, "handleRewards" to P.RECOVER)) {
        val b = body(command, "private fun $fn(")
        check("$fn checks $node first", b.trimStart().lines().drop(1).first().contains("if (!sender.hasPermission(\"$node\"))"))
    }
    check("diagnose uses the shared rule", body(command, "private fun handleDiagnose(").contains("CratePermissions.diagnose { sender.hasPermission(it) }"))
    check("17. force needs force, not givekey", !body(command, "private fun handleForceOpen(").contains(P.GIVEKEY))
    check("tab completion uses the per-node list", "CratePermissions.adminSubcommands { sender.hasPermission(it) }" in command)
    check("givekey tab completion gated", command.split("\"givekey\" -> if (!sender.hasPermission(\"joshymc.crate.givekey\"))").size == 3)
    check("location changes audited", "CRATE_LOCATION_SET" in command && "CRATE_LOCATION_REMOVE" in command)
    check("keys and forced opens still audited", "CRATE_KEY_GIVE" in command && "CRATE_FORCE_OPEN" in command)

    // ── Wiring: CrateEditorCommand ──
    val editor = File("src/main/kotlin/com/liam/joshymc/command/CrateEditorCommand.kt").readText()
    check("7. /crateeditor checks the editor node", "if (!sender.hasPermission(\"joshymc.crateeditor\"))" in editor)
    check("7. editor re-checks the node", "if (p.hasPermission(CratePermissions.EDITOR)) return true" in editor)
    for (menu in listOf("fun openMainMenu(player: Player) {", "fun openEditMenu(player: Player, crateId: String) {", "private fun openParticleColorGui(player: Player, crateId: String) {")) {
        check("7. $menu re-checks the node", editor.substringAfter(menu).trimStart().startsWith("if (!authorized(player)) return"))
    }
    // Every crateManager mutation in the editor sits inside applyAudited, after an authorized(...) check.
    val mutations = listOf("removeReward(", "addReward(", "updateRewardWeight(", "deleteCrate(", "createCrate(", "setCrateDisplayName(",
        "setCrateKeyMaterial(", "setCrateMode(", "setCrateAnimationType(", "setCrateAnimationGlass(", "setCrateIdleParticle(",
        "setCrateWinParticle(", "setCrateParticleColor(")
    for (m in mutations) {
        val calls = Regex(Regex.escape("plugin.crateManager.$m")).findAll(editor).map { it.range.first }.toList()
        check("$m is called from the editor", calls.isNotEmpty())
        for (at in calls) {
            // The click handler / chat step the call is in.
            val upTo = editor.substring(0, at)
            val step = upTo.substring(maxOf(upTo.lastIndexOf("gui.setItem("), upTo.lastIndexOf("is PendingCrateAction.")))
            check("15. $m runs inside applyAudited", "applyAudited(" in step)
            check("14. $m is preceded by an authorization check", step.indexOf("authorized(") in 0 until step.indexOf("applyAudited("))
        }
    }
    check("11/12. reward clicks refuse a changed list", editor.contains("staleReason(crateId, rewardIndex, shown)") &&
        editor.contains("staleReason(crateId, pending.rewardIndex, pending.expected)"))
    check("chat steps run on the main thread and re-check", "runTask(plugin, Runnable { handlePending(player, pending, message) })" in editor &&
        editor.split("is PendingCrateAction.").drop(1).count { step -> step.lines().take(5).any { "if (!authorized(player" in it } } == 4)
    check("enchant refusal audited", "CrateEditorAudit.Reason.ENCHANT_PROBLEM" in editor)
    check("no-op weight is not logged", "Nothing to change, so nothing to log." in editor)
    check("editor never logs the snapshot", !Regex("details = .*itemBase64").containsMatchIn(editor) && "AdminAudit.log(plugin, p, op.action, details = CrateEditorAudit.details(" in editor)
    check("voucher validation preserved", "plugin.customEnchantManager.enchantProblems(held)" in editor)

    // ── Wiring: CrateManager and the rest of the source ──
    val manager = File("src/main/kotlin/com/liam/joshymc/manager/CrateManager.kt").readText()
    check("quick rotate uses the display node", "player.hasPermission(com.liam.joshymc.crate.CratePermissions.DISPLAY)" in manager)
    check("crate refusal hints go to any crate staff", manager.split("CratePermissions.isCrateStaff { player.hasPermission(it) }").size == 3)
    check("crate voucher validation untouched", "freshVoucherCopy" in manager || "VoucherCopies" in manager || "voucherTemplateProblem" in manager)
    val legacyUses = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
        .filter { "hasPermission(\"joshymc.crate.admin\")" in it.readText() }.map { it.name }.toList()
    check("8. no source file checks joshymc.crate.admin (found $legacyUses)", legacyUses.isEmpty())

    // ── plugin.yml ──
    val yml = File("src/main/resources/plugin.yml").readText()
    fun node(name: String) = Regex("\\n  ${Regex.escape(name)}:\\n((    .*\\n)+)").find(yml)?.groupValues?.get(1)
    for (n in listOf(P.GIVEKEY, P.FORCE, P.MONTHLY, P.DISPLAY, P.LOCATION, P.EDITOR)) {
        val entry = node(n)
        check("$n declared", entry != null)
        check("$n is op by default", entry!!.contains("default: op"))
        check("$n has no children", "children" !in entry)
    }
    val legacyEntry = node(P.LEGACY_ADMIN)
    check("8. legacy node declared as deprecated", legacyEntry != null && "Deprecated" in legacyEntry)
    check("8. legacy node defaults to false", legacyEntry!!.contains("default: false"))
    check("8. legacy node is no parent", "children" !in legacyEntry)
    for (n in listOf(P.GIVEKEY, P.FORCE, P.MONTHLY, P.DISPLAY, P.LOCATION)) {
        // Declared once and never listed as some other node's child (no broad parent grants it).
        check("no parent hands out $n", Regex("(?m)^\\s+${Regex.escape(n)}:").findAll(yml).count() == 1)
    }

    // ── Docs ──
    val docs = File("docs/staff-permissions.md").readText()
    for (n in listOf(P.GIVEKEY, P.FORCE, P.MONTHLY, P.DISPLAY, P.LOCATION)) check("docs list $n", n in docs)
    check("docs have the migration checklist", "Crate permission migration" in docs)
    check("docs no longer recommend granting the legacy node",
        !docs.contains("permission set joshymc.crate.admin true"))

    println("All $checks crate admin permission checks passed.")
}
