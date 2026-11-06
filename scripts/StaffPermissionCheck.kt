// Checks the staff permission overhaul (issue #1383): the staff hierarchy and LuckPerms group
// mapping, rank protection (lower/equal/self), rank assignment limits, the legacy punishment
// level caps, console-only voucher reward commands and portal console actions, /sudo limits,
// Creative item protection, signed premium vouchers, the plugin.yml staff bundles and node
// defaults, and the source wiring of the fixed paths. The Gradle build has no test source set,
// so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/staff-permission-check.init.gradle.kts staffPermissionCheck
//
// Paper's classes target Java 25 and this runs on the Java 21 toolchain, so it never loads a
// Bukkit class: rules are Bukkit-free, YAML goes through snakeyaml, command wiring is checked by
// source text. Exits non-zero on the first failed check. Live LuckPerms groups are not in the
// repository and can't be checked here.

import com.liam.joshymc.flight.SurvivalFlight
import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.punish.PunishDurations
import com.liam.joshymc.punish.StaffLevel
import com.liam.joshymc.staff.CreativeItemRules
import com.liam.joshymc.staff.LegacyPunishAuthority
import com.liam.joshymc.staff.LegacyPunishAuthority.Action
import com.liam.joshymc.staff.PortalActionRules
import com.liam.joshymc.staff.PremiumVoucherRules
import com.liam.joshymc.staff.RankAssignmentRules
import com.liam.joshymc.staff.StaffActor
import com.liam.joshymc.staff.StaffProtection
import com.liam.joshymc.staff.StaffRank
import com.liam.joshymc.staff.StaffRankGroups
import com.liam.joshymc.staff.StaffTarget
import com.liam.joshymc.staff.SudoRules
import com.liam.joshymc.staff.VoucherAdminRules
import org.yaml.snakeyaml.Yaml
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

private fun resource(name: String): Map<String, Any?> =
    File("src/main/resources/$name").reader(Charsets.UTF_8).use { Yaml().load<Map<String, Any?>>(it) }

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private fun Map<String, Any?>.path(path: String): Any? {
    var node: Any? = this
    for (part in path.split('.')) node = (node as? Map<*, *>)?.get(part)
    return node
}

private fun staff(rank: StaffRank?) = StaffActor.Staff(rank)
private fun target(rank: StaffRank?, self: Boolean = false) = StaffTarget(rank, self)

fun main() {
    // ── Hierarchy ───────────────────────────────────────────
    val expectedOrder = listOf(
        "trainee", "helper", "jr_mod", "mod", "lead_mod", "jr_admin", "admin", "lead_admin",
        "community_manager", "staff_manager", "head_manager", "owner",
    )
    check("twelve Minecraft staff ranks in organizational order", StaffRank.entries.map { it.id } == expectedOrder)
    check("Staff Manager is above Community Manager", StaffRank.STAFF_MANAGER.above(StaffRank.COMMUNITY_MANAGER))
    check("Community Manager is above Lead Admin and Lead Mod",
        StaffRank.COMMUNITY_MANAGER.above(StaffRank.LEAD_ADMIN) && StaffRank.COMMUNITY_MANAGER.above(StaffRank.LEAD_MOD))
    check("Head Manager is below Owner", StaffRank.OWNER.above(StaffRank.HEAD_MANAGER))
    check("management ranks are on the management track, not administration",
        listOf(StaffRank.COMMUNITY_MANAGER, StaffRank.STAFF_MANAGER, StaffRank.HEAD_MANAGER).all { it.track == StaffRank.Track.MANAGEMENT })
    check("Jr Admin / Admin / Lead Admin are administration",
        listOf(StaffRank.JR_ADMIN, StaffRank.ADMIN, StaffRank.LEAD_ADMIN).all { it.track == StaffRank.Track.ADMINISTRATION })
    for (discordOnly in listOf("partnership_manager", "event_manager", "giveaway_manager", "build_manager", "media", "discord")) {
        check("$discordOnly is not a Minecraft staff rank", StaffRank.fromId(discordOnly) == null)
    }
    check("fromId accepts the no-underscore form", StaffRank.fromId("jrmod") == StaffRank.JR_MOD && StaffRank.fromId("Head-Manager") == StaffRank.HEAD_MANAGER)

    // ── Group mapping (authoritative data, no display names) ─────
    val groups = StaffRankGroups()
    check("default groups map ids and no-underscore names", groups.rankForGroup("leadadmin") == StaffRank.LEAD_ADMIN && groups.rankForGroup("lead_admin") == StaffRank.LEAD_ADMIN)
    check("existing ranks.list staff ids map", listOf("owner", "admin", "lead_mod", "mod", "jr_mod", "helper", "trainee").all { groups.rankForGroup(it) != null })
    check("donor/player ranks never map to staff", listOf("vip", "member", "default", "scout", "pioneer", "media", "discord").all { groups.rankForGroup(it) == null })
    check("resolve reads explicit group nodes", groups.resolve(setOf("group.mod", "group.default"), emptyList()) == StaffRank.MOD)
    check("resolve picks the highest", groups.resolve(setOf("group.mod", "group.headmanager"), listOf("helper")) == StaffRank.HEAD_MANAGER)
    check("resolve reads JoshyMC records", groups.resolve(emptySet(), listOf("lead_mod")) == StaffRank.LEAD_MOD)
    check("prefix-looking permissions don't count", groups.resolve(setOf("prefix.100.&cOwner", "joshymc.admin"), emptyList()) == null)
    val custom = StaffRankGroups(mapOf(StaffRank.STAFF_MANAGER to listOf("StaffMgr")))
    check("configured groups replace the defaults", custom.rankForGroup("staffmgr") == StaffRank.STAFF_MANAGER && custom.rankForGroup("staff_manager") == null)
    val clash = StaffRankGroups(mapOf(StaffRank.MOD to listOf("x"), StaffRank.ADMIN to listOf("x")))
    check("a group on two ranks is reported and the higher wins", clash.problems.isNotEmpty() && clash.rankForGroup("x") == StaffRank.ADMIN)

    // ── Rank protection ─────────────────────────────────────
    val d = StaffProtection.Decision.entries
    check("console may act on anyone", StaffProtection.decide(StaffActor.Console, target(StaffRank.OWNER)) == StaffProtection.Decision.ALLOWED)
    check("nobody acts on themselves", StaffProtection.decide(staff(StaffRank.OWNER), target(StaffRank.OWNER, self = true)) == StaffProtection.Decision.SELF)
    check("lower can't target higher", StaffProtection.decide(staff(StaffRank.MOD), target(StaffRank.ADMIN)) == StaffProtection.Decision.HIGHER_RANK)
    check("equal rank refused by default", StaffProtection.decide(staff(StaffRank.MOD), target(StaffRank.MOD)) == StaffProtection.Decision.EQUAL_RANK)
    check("equal rank only with explicit approval", StaffProtection.decide(staff(StaffRank.MOD), target(StaffRank.MOD), allowEqual = true) == StaffProtection.Decision.ALLOWED)
    check("higher may target lower", StaffProtection.decide(staff(StaffRank.LEAD_MOD), target(StaffRank.MOD)) == StaffProtection.Decision.ALLOWED)
    check("anyone may target non-staff", StaffProtection.decide(staff(null), target(null)) == StaffProtection.Decision.ALLOWED)
    check("non-staff can't target staff", StaffProtection.decide(staff(null), target(StaffRank.TRAINEE)) == StaffProtection.Decision.NOT_STAFF)
    check("Owner is protected from Head Manager", !StaffProtection.decide(staff(StaffRank.HEAD_MANAGER), target(StaffRank.OWNER)).allowed)
    check("Staff Manager may discipline admins and moderators",
        listOf(StaffRank.LEAD_ADMIN, StaffRank.ADMIN, StaffRank.LEAD_MOD, StaffRank.TRAINEE).all { StaffProtection.decide(staff(StaffRank.STAFF_MANAGER), target(it)).allowed })
    check("Community Manager can't act on Staff Manager", !StaffProtection.decide(staff(StaffRank.COMMUNITY_MANAGER), target(StaffRank.STAFF_MANAGER)).allowed)
    check("Jr Admin / Lead Admin / Head Manager are protected", listOf(StaffRank.JR_ADMIN, StaffRank.LEAD_ADMIN, StaffRank.HEAD_MANAGER).all {
        !StaffProtection.decide(staff(StaffRank.LEAD_MOD), target(it)).allowed
    })
    check("every refusal has a message", d.filter { it != StaffProtection.Decision.ALLOWED }.all { !it.message.isNullOrBlank() })

    // ── Rank assignment ─────────────────────────────────────
    val ra = RankAssignmentRules
    check("no self-promotion", ra.decide(staff(StaffRank.ADMIN), target(StaffRank.ADMIN, self = true), StaffRank.LEAD_ADMIN) == RankAssignmentRules.Decision.SELF)
    check("no self donor change either", ra.decide(staff(StaffRank.ADMIN), target(StaffRank.ADMIN, self = true), null) == RankAssignmentRules.Decision.SELF)
    check("Owner is console only", ra.decide(staff(StaffRank.OWNER), target(null), StaffRank.OWNER) == RankAssignmentRules.Decision.OWNER)
    check("console may assign Owner", ra.decide(StaffActor.Console, target(null), StaffRank.OWNER).allowed)
    check("only ranks below your own", ra.decide(staff(StaffRank.ADMIN), target(null), StaffRank.ADMIN) == RankAssignmentRules.Decision.RANK_TOO_HIGH)
    check("Admin may give Lead Mod", ra.decide(staff(StaffRank.ADMIN), target(StaffRank.MOD), StaffRank.LEAD_MOD).allowed)
    check("can't modify equal/higher staff", ra.decide(staff(StaffRank.ADMIN), target(StaffRank.LEAD_ADMIN), StaffRank.HELPER) == RankAssignmentRules.Decision.TARGET_PROTECTED)
    check("donor management still works", ra.decide(staff(StaffRank.ADMIN), target(null), null).allowed)
    check("non-staff can't hand out staff ranks", ra.decide(staff(null), target(null), StaffRank.TRAINEE) == RankAssignmentRules.Decision.NOT_STAFF)

    // ── Legacy punishment caps ──────────────────────────────
    val day = PunishDurations.DAY
    fun ok(level: StaffLevel?, action: Action, ms: Long? = null) = LegacyPunishAuthority.problem(level, action, ms) == null
    check("no level = nothing", !ok(null, Action.WARN))
    check("Trainee may warn", ok(StaffLevel.TRAINEE, Action.WARN))
    check("Trainee can't mute", !ok(StaffLevel.TRAINEE, Action.TEMPMUTE))
    check("Helper may temp-mute", ok(StaffLevel.HELPER, Action.TEMPMUTE, day))
    check("Helper/Jr Mod can't permanently mute", !ok(StaffLevel.HELPER, Action.MUTE) && !ok(StaffLevel.JRMOD, Action.MUTE))
    check("Mod may permanently mute", ok(StaffLevel.MOD, Action.MUTE))
    check("Jr Mod can't ban at all", !ok(StaffLevel.JRMOD, Action.TEMPBAN, day) && !ok(StaffLevel.JRMOD, Action.BAN))
    check("Mod temp-ban capped at 7 days", ok(StaffLevel.MOD, Action.TEMPBAN, 7 * day) && !ok(StaffLevel.MOD, Action.TEMPBAN, 8 * day))
    check("Lead Mod / Jr Admin capped at 30 days", ok(StaffLevel.LEADMOD, Action.TEMPBAN, 30 * day) && !ok(StaffLevel.JRADMIN, Action.TEMPBAN, 31 * day))
    check("permanent ban needs Admin", !ok(StaffLevel.JRADMIN, Action.BAN) && ok(StaffLevel.ADMIN, Action.BAN))
    check("unwarn needs Jr Mod (same as rule overturn of warnings)", !ok(StaffLevel.HELPER, Action.UNWARN) && ok(StaffLevel.JRMOD, Action.UNWARN))
    check("unban needs Mod", !ok(StaffLevel.JRMOD, Action.UNBAN) && ok(StaffLevel.MOD, Action.UNBAN))
    check("kick needs Mod like the rule system", !ok(StaffLevel.JRMOD, Action.KICK) && ok(StaffLevel.MOD, Action.KICK))
    check("Sr Admin (console/op) may do everything", Action.entries.all { ok(StaffLevel.SRADMIN, it, 365 * day) })
    for (level in StaffLevel.entries) for (action in Action.entries) {
        if (action == Action.BAN) check("permanent ban implies rule-system authority (${level.id})", ok(level, action) == level.atLeast(StaffLevel.ADMIN))
    }

    // ── Vouchers / portals / sudo / creative ────────────────
    check("setcommand is console only", VoucherAdminRules.gate("setcommand") == VoucherAdminRules.Gate.CONSOLE_ONLY)
    check("package create (price) is console only", VoucherAdminRules.gate("create", "10") == VoucherAdminRules.Gate.CONSOLE_ONLY)
    check("chat tag voucher create stays a permission", VoucherAdminRules.gate("create", "&6Tag") == VoucherAdminRules.Gate.NONE)
    check("setprice / setrank need the pricing node", VoucherAdminRules.gate("SetPrice") == VoucherAdminRules.Gate.PRICING && VoucherAdminRules.gate("setrank") == VoucherAdminRules.Gate.PRICING)
    check("give / list / delete keep joshymc.vouchers", listOf("give", "list", "info", "delete", "setname").all { VoucherAdminRules.gate(it) == VoucherAdminRules.Gate.NONE })
    check("portal command runs as the player", PortalActionRules.runnerFor("command") == PortalActionRules.Runner.PLAYER)
    check("portal console action is console only", PortalActionRules.consoleOnly("CONSOLE") && PortalActionRules.runnerFor("console") == PortalActionRules.Runner.CONSOLE)
    check("tp/warp portals untouched", PortalActionRules.runnerFor("warp") == PortalActionRules.Runner.NONE && !PortalActionRules.consoleOnly("tp"))
    for (cmd in listOf("lp user x parent add owner", "/luckperms user x permission set *", "minecraft:op me", "eco give me 1dc", "purchase fulfill me credits 9", "execute as @a run op me", "Sudo x lp")) {
        check("sudo blocks '$cmd'", SudoRules.blocked(cmd))
    }
    check("sudo allows ordinary commands", !SudoRules.blocked("spawn") && !SudoRules.blocked("/warp shop"))
    check("creative protects joshymc data", CreativeItemRules.protectedItem(listOf("joshymc:custom_item_id")) && CreativeItemRules.protectedItem(listOf("minecraft:x", "JoshyMC:voucher_uuid")))
    check("creative leaves vanilla items", !CreativeItemRules.protectedItem(emptyList()) && !CreativeItemRules.protectedItem(listOf("worldedit:wand")))

    // ── Signed vouchers ─────────────────────────────────────
    val secret = ByteArray(32) { it.toByte() }
    val payload = PremiumVoucherRules.payload(PremiumVoucherRules.Kind.CREDIT, 1, "u-1", "500")
    val sig = SurvivalFlight.sign(secret, payload)
    check("signature verifies", SurvivalFlight.verify(secret, payload, sig))
    check("changed value fails", !SurvivalFlight.verify(secret, PremiumVoucherRules.payload(PremiumVoucherRules.Kind.CREDIT, 1, "u-1", "5000"), sig))
    check("credit and rank payloads differ", payload != PremiumVoucherRules.payload(PremiumVoucherRules.Kind.RANK, 1, "u-1", "500"))
    check("forged signature refused", PremiumVoucherRules.verdict("bad", valid = false, acceptUnsigned = true) == PremiumVoucherRules.Verdict.FORGED)
    check("legacy accepted while migrating", PremiumVoucherRules.redeemable(PremiumVoucherRules.verdict(null, false, acceptUnsigned = true)))
    check("legacy refused once turned off", !PremiumVoucherRules.redeemable(PremiumVoucherRules.verdict(null, false, acceptUnsigned = false)))
    check("signed accepted", PremiumVoucherRules.redeemable(PremiumVoucherRules.verdict(sig, true, false)))

    // ── config.yml / plugin.yml ─────────────────────────────
    val config = resource("config.yml")
    check("staff-ranks.allow-equal-rank-actions defaults false", config.path("staff-ranks.allow-equal-rank-actions") == false)
    val configured = (config.path("staff-ranks.groups") as? Map<*, *>).orEmpty()
    check("staff-ranks.groups lists every rank", StaffRank.entries.all { configured.containsKey(it.id) })
    check("staff-ranks.groups keys are all staff ranks", configured.keys.all { StaffRank.fromId(it.toString()) != null })
    check("vouchers.accept-unsigned-legacy defaults true (migration)", config.path("vouchers.accept-unsigned-legacy") == true)

    val perms = (resource("plugin.yml")["permissions"] as Map<*, *>)
    fun default(node: String) = (perms[node] as? Map<*, *>)?.get("default")?.toString()
    fun children(node: String) = ((perms[node] as? Map<*, *>)?.get("children") as? Map<*, *>).orEmpty().keys.map { it.toString() }
    check("joshymc.vouchers.pricing default false", default("joshymc.vouchers.pricing") == "false")
    check("joshymc.creative.bypass default false", default("joshymc.creative.bypass") == "false")
    check("Trainee bundle no longer grants mutes", "joshymc.staff.mute" !in children("joshymc.staff.trainee"))
    check("Jr Mod bundle no longer grants bans", "joshymc.staff.ban" !in children("joshymc.staff.jrmod"))
    check("no staff bundle defaults to true", perms.keys.map { it.toString() }.filter { it.startsWith("joshymc.staff.") }.all { default(it) != "true" })
    check("punish levels default false", StaffLevel.entries.all { default(it.permission) == "false" })
    check("no management-only node grants an admin power", perms.keys.none { it.toString().startsWith("joshymc.staff.staffmanager") || it.toString().startsWith("joshymc.staff.communitymanager") })

    // ── Source wiring ───────────────────────────────────────
    val legacy = source("command/PunishmentCommands.kt")
    for (action in listOf("BAN", "TEMPBAN", "MUTE", "TEMPMUTE", "WARN", "KICK", "UNBAN", "UNMUTE", "UNWARN")) {
        check("/$action goes through legacyGuard", "legacyGuard(plugin, sender, " in legacy && "LegacyPunishAuthority.Action.$action" in legacy)
    }
    val punishCommand = source("command/PunishCommand.kt")
    check("/punish legacy forms use the guard, not ranks.list weights", "legacyGuard(plugin, sender, targetUuid, action)" in punishCommand && "senderWeight" !in punishCommand)
    check("/punish rule system uses staff rank protection", "staffRankManager.protectionProblem" in source("punish/RulePunishManager.kt"))
    val punishments = source("manager/PunishmentManager.kt")
    check("legacy unban/unmute never lift rule punishments", "active = 1 AND offense IS NULL\"" in punishments && "fun revokeLegacy" in punishments)
    val admin = source("manager/AdminManager.kt")
    check("/admin balance presets are audited op-only", "economyManager.setBalance(" !in admin && "economyManager.adminSet(" in admin && "if (!p.isOp)" in admin)
    check("/admin clicks re-check the tier", "fun CustomGui.guardedItem" in admin && admin.split("guardedItem(Tier.").size > 20)
    check("/admin rank selector uses rank protection", "rankChangeProblem(p, targetUuid, rank.id)" in admin)
    check("/admin Creative needs /gamemode creative rights", "mayGiveCreative(p, tp)" in admin)
    check("/admin restore refuses self/higher staff and foreign snapshots", "protectionProblem(p, targetUuid)" in admin && "WHERE id = ? AND player_uuid = ?" in admin)
    check("/admin chat reasons are re-checked", "legacyPunishProblem(admin, targetUuid, action, pending.duration)" in admin)
    check("/admin subcommands check their own tier", "AdminManager.Tier.ADMIN" in source("command/AdminCommand.kt"))
    check("/rank changes use rank protection", source("command/RankCommand.kt").split("refusedChange(sender, target.uniqueId").size == 5)
    check("/voucher gates reward commands", "VoucherAdminRules.gate(args[0], args.getOrNull(2))" in source("command/VoucherCommand.kt"))
    val portal = source("manager/PortalManager.kt")
    check("portal command runs as the player", "player.performCommand(cmd)" in portal)
    check("/sudo is limited and audited", "SudoRules.blocked(cmd)" in source("command/EssentialsCommands.kt") && "\"SUDO\"" in source("command/EssentialsCommands.kt"))
    check("creative listener is registered", "CreativeValuablesListener(plugin)" in source("manager/ListenerManager.kt"))
    check("credit vouchers are signed and checked", source("manager/CreditVoucherManager.kt").let { "premiumVoucherSigner.sign(" in it && "premiumVoucherSigner.check(" in it })
    check("rank vouchers are signed and checked", source("manager/RankVoucherManager.kt").let { "premiumVoucherSigner.sign(" in it && "premiumVoucherSigner.check(" in it })
    for ((file, action) in listOf(
        "command/EcoCommand.kt" to "ECO_GIVE", "command/CreditsCommand.kt" to "CREDITS_GIVE", "command/TokensCommand.kt" to "TOKENS_GIVE",
        "command/CrateCommand.kt" to "CRATE_KEY_GIVE", "command/CrateCommand.kt" to "CRATE_FORCE_OPEN", "command/JoshyCommand.kt" to "ITEM_GIVE",
        "command/RankVoucherCommand.kt" to "RANK_VOUCHER_GIVE", "command/CreditVoucherCommand.kt" to "CREDIT_VOUCHER_GIVE",
        "command/SellWandCommand.kt" to "SELLWAND_GIVE", "command/VoucherCommand.kt" to "VOUCHER_GIVE",
    )) check("$action is audited", "AdminAudit.log(plugin, sender, \"$action\"" in source(file))

    // ── Gamemode isolation of the commands above ────────────
    val scopes = GamemodeAssignments.COMMANDS
    for (c in listOf("joshymc", "enchant", "sudo", "admin", "rank", "punish", "ban", "portal", "gamemode")) {
        check("/$c is NETWORK (works on both servers; each server enforces its own database)", scopes[c] == GamemodeScope.NETWORK)
    }
    for (c in listOf("voucher", "creditvoucher", "rankvoucher", "crate")) check("/$c is SURVIVAL", scopes[c] == GamemodeScope.SURVIVAL)
    for (c in listOf("withdrawheart", "ranked")) check("/$c is LIFESTEAL", scopes[c] == GamemodeScope.LIFESTEAL)

    println("OK: $checks staff permission checks passed.")
}
