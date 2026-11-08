// Checks the Lifesteal ranks, rank perks, rank kits and progression (issue #1428): the bundled
// lifesteal-ranks.yml, every validation rule (pay-to-win perks, reserved staff/Survival groups,
// forbidden permission nodes), the hierarchy and inheritance, grant/upgrade decisions, progression,
// kit access and cooldowns, the rank store against a real SQLite database (one pending change per
// player, duplicate references, restart recovery, permanent ownership, append-only audit), the
// kit-claim reservation, the `lsrank` store reward and the Survival/Lifesteal wiring.
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-rank-check.init.gradle.kts lifestealRankCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig
import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig.Perk
import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig.RequirementType
import com.liam.joshymc.lifesteal.ranks.LifestealRankRules
import com.liam.joshymc.lifesteal.ranks.LifestealRankRules.Decision
import com.liam.joshymc.lifesteal.ranks.LifestealRankRules.KitAccess
import com.liam.joshymc.lifesteal.ranks.LifestealRankRules.Source
import com.liam.joshymc.lifesteal.ranks.LifestealRankStore
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.DonorTiers
import com.liam.joshymc.manager.PurchaseRewards
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }

private fun <T> withDb(dir: File, block: (DatabaseManager) -> T): T {
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        return block(db)
    } finally {
        db.shutdown()
    }
}

/** A [LifestealRankConfig.Source] over nested maps (what SnakeYAML returns). */
private class MapSource(private val root: Map<String, Any?>) : LifestealRankConfig.Source {
    private fun node(path: String): Any? {
        var cur: Any? = root
        for (part in path.split('.')) cur = (cur as? Map<*, *>)?.get(part) ?: return null
        return cur
    }
    override fun get(path: String): Any? = node(path).takeUnless { it is Map<*, *> }
    override fun keys(path: String): List<String>? = (node(path) as? Map<*, *>)?.keys?.map { it.toString() }
}

private val RESERVED = LifestealRankConfig.Reserved(
    DonorTiers.ORDER.toSet() + setOf("default", "owner", "admin", "mod", "helper", "trainee", "jr_mod", "lead_mod", "media", "discord")
)

@Suppress("UNCHECKED_CAST")
private fun yaml(text: String): Map<String, Any?> = Yaml().load<Map<String, Any?>>(text) ?: emptyMap()

private fun parse(text: String) = LifestealRankConfig.parse(MapSource(yaml(text)), RESERVED)

/** A three-rank test ladder (test-only ids; the shipped file has only the approved Rogue and Legacy). */
private val LADDER = """
config-version: 1
enabled: true
disabled-perks: []
ranks:
  low:
    display-name: Low
    color: "#111111"
    order: 1
    group: ls_low
    acquisition: { free: true }
    kits: [low_kit]
    permissions: [joshymc.chatcolor]
    perks: { homes: 2, sell-bonus-percent: 5, auction-extra-listings: 1 }
  mid:
    display-name: Mid
    color: "#222222"
    order: 2
    group: ls_mid
    acquisition: { progression: true, store: true }
    requirements: { playtime-hours: 10, pvp-kills: 5, unique-victims: 3 }
    requirements-scope: season
    kits: [mid_kit]
    permissions: [joshymc.tag.voucher.mid]
    perks: { homes: 4, kit-cooldown-reduction-percent: 25 }
  top:
    display-name: Top
    color: "#333333"
    order: 3
    group: ls_top
    acquisition: { store: true }
    kits: [top_kit]
    perks: { homes: 3, vaults: 5, order-extra-orders: 4 }
""".trimIndent()

private fun rankBlock(extra: String) = """
config-version: 1
ranks:
  one:
    display-name: One
    order: 1
$extra
""".trimIndent()

fun main() {
    // ── Bundled file: only the approved names, nothing invented ───────────────────
    val bundled = parse(File("src/main/resources/lifesteal-ranks.yml").readText())
    check("bundled lifesteal-ranks.yml is valid: ${bundled.problems}", bundled.settings != null && bundled.problems.none { it.severe })
    val b = bundled.settings!!
    check("bundled ranks are exactly Rogue then Legacy", b.ranks.map { it.id } == listOf("rogue", "legacy"))
    check("Rogue is the free starter rank (issue #1295)", b.freeRank?.id == "rogue")
    check("Legacy ends the ladder (issue #1379)", b.ranks.last().id == "legacy" && b.ranks.last().order > b.ranks.first().order)
    check("no bundled rank is for sale (no approved store package)", b.ranks.none { it.acquisition.store })
    check("no bundled rank is earned by progression (no approved requirements)", b.ranks.none { it.acquisition.progression || it.requirements.isNotEmpty() })
    check("no bundled perk values (none approved)", b.ranks.all { it.perks.isEmpty() })
    check("no bundled permissions", b.ranks.all { it.permissions.isEmpty() })
    check("bundled groups are rogue and legacy", b.ranks.map { it.group } == listOf("rogue", "legacy"))
    check("bundled groups aren't Survival tiers or staff", b.ranks.none { it.group in RESERVED.groups })
    check("Legacy can only be granted by staff", LifestealRankRules.decideGrant(b, emptySet(), "legacy", Source.STORE, false) == Decision.NOT_OFFERED &&
        LifestealRankRules.decideGrant(b, emptySet(), "legacy", Source.FREE, false) == Decision.NOT_OFFERED &&
        LifestealRankRules.decideGrant(b, emptySet(), "legacy", Source.ADMIN, false) == Decision.GRANT)
    check("bundled kits follow the ladder", LifestealRankRules.kitOrder(b) == listOf("rogue", "legacy"))
    check("bundled: global LuckPerms nodes by default", b.serverContext == null)
    check("bundled: kits refused while combat-tagged", b.refuseKitsWhileCombatTagged)

    // ── Validation ─────────────────────────────────────────────────────────────────
    val ladder = parse(LADDER)
    check("test ladder is valid: ${ladder.problems}", ladder.settings != null)
    val s = ladder.settings!!
    check("ranks sorted by order", s.ranks.map { it.id } == listOf("low", "mid", "top"))

    for (p2w in listOf("hearts", "max-hearts", "damage", "damage-reduction", "invulnerability", "combat-tag-bypass", "elo-bonus", "heart-steal-immunity", "illegal-enchants")) {
        val r = parse(rankBlock("    perks: { $p2w: 1 }"))
        check("pay-to-win perk '$p2w' refused", r.settings == null && r.problems.any { it.severe && "refused" in it.message })
    }
    check("unknown perk refused", parse(rankBlock("    perks: { flying-carpet: 1 }")).settings == null)
    check("sell bonus capped at 25%", parse(rankBlock("    perks: { sell-bonus-percent: 26 }")).settings == null &&
        parse(rankBlock("    perks: { sell-bonus-percent: 25 }")).settings != null)
    check("kit cooldown reduction capped at 50%", parse(rankBlock("    perks: { kit-cooldown-reduction-percent: 51 }")).settings == null)
    check("negative perk refused", parse(rankBlock("    perks: { homes: -1 }")).settings == null)
    check("perk caps are small", Perk.SELL_BONUS_PERCENT.max == 25 && Perk.KIT_COOLDOWN_REDUCTION_PERCENT.max == 50)
    for (group in listOf("admin", "owner", "mod", "scout", "pioneer", "default", "discord")) {
        check("reserved group '$group' refused", parse(rankBlock("    group: $group")).settings == null)
    }
    check("duplicate order refused", parse("""
        ranks:
          a: { display-name: A, order: 1 }
          b: { display-name: B, order: 1 }
    """.trimIndent()).settings == null)
    check("duplicate group refused", parse("""
        ranks:
          a: { display-name: A, order: 1, group: same }
          b: { display-name: B, order: 2, group: same }
    """.trimIndent()).settings == null)
    check("progression without requirements refused (would be free)", parse(rankBlock("    acquisition: { progression: true }")).settings == null)
    check("unknown requirement refused", parse(rankBlock("    acquisition: { progression: true }\n    requirements: { elo: 1000 }")).settings == null)
    check("bad scope refused", parse(rankBlock("    requirements-scope: forever")).settings == null)
    check("one kit belongs to one rank", parse("""
        ranks:
          a: { display-name: A, order: 1, kits: [k] }
          b: { display-name: B, order: 2, kits: [k] }
    """.trimIndent()).settings == null)
    check("missing ranks section refused", parse("config-version: 1").settings == null)
    check("newer config-version refused", parse("config-version: 99\n" + LADDER.substringAfter("\n")).settings == null)
    check("invalid server context refused", parse("luckperms: { server-context: 'a b' }\n" + LADDER.substringAfter("\n")).settings == null)
    check("server context parsed", parse("luckperms: { server-context: lifesteal }\n" + LADDER.substringAfter("\n")).settings?.serverContext == "lifesteal")

    // ── Permission safety (§10: never admin, op, creative, eco, credits admin, punish, hearts, ranked) ──
    val forbidden = listOf(
        "*", "op", "joshymc.*", "joshymc.admin", "joshymc.admin.helper", "joshymc.eco", "joshymc.eco.set", "joshymc.credits.give",
        "joshymc.punish", "joshymc.punish.mod", "joshymc.staff.trainee", "joshymc.ban", "joshymc.mute", "joshymc.give",
        "joshymc.gamemode", "joshymc.gmc", "minecraft.command.gamemode", "bukkit.command.op", "luckperms.user.parent.add",
        "joshymc.lifesteal.hearts.admin", "joshymc.lifesteal.hearts.give", "joshymc.lifesteal.ranked.admin",
        "joshymc.lifesteal.ranks.admin", "joshymc.lifesteal.progression.end-bypass", "joshymc.kit.bypass.*",
        "joshymc.kit.cooldown.bypass", "joshymc.kit.legacy", "joshymc.heal", "joshymc.feed", "joshymc.god", "joshymc.fly",
        "joshymc.speed", "joshymc.enchant", "joshymc.rank", "joshymc.rankperk.pioneer", "joshymc.heal.others",
        "joshymc.vouchers", "joshymc.combat.bypass", "essentials.fly", "-joshymc.chatcolor", "joshymc.internal.donortier.wildcardprobe",
    )
    for (node in forbidden) check("node '$node' refused", LifestealRankRules.forbiddenNodeReason(node) != null)
    for (node in listOf("joshymc.chatcolor", "joshymc.tag.voucher.mapleborn", "joshymc.nick", "joshymc.hat", "joshymc.trail.flame")) {
        check("player node '$node' allowed", LifestealRankRules.forbiddenNodeReason(node) == null)
    }
    check("forbidden node in a rank makes the file invalid", parse(rankBlock("    permissions: [joshymc.punish.admin]")).settings == null)

    // ── Hierarchy and inheritance ─────────────────────────────────────────────────
    check("no ranks = no current rank", LifestealRankRules.currentRank(s, emptySet()) == null)
    check("current rank is the highest owned", LifestealRankRules.currentRank(s, setOf("low", "top"))?.id == "top")
    check("owning a higher rank reaches the lower ones", LifestealRankRules.reaches(s, setOf("top"), s.rank("low")!!) &&
        LifestealRankRules.reaches(s, setOf("top"), s.rank("mid")!!))
    check("a lower rank doesn't reach a higher one", !LifestealRankRules.reaches(s, setOf("low"), s.rank("mid")!!))
    val topPerks = LifestealRankRules.perks(s, setOf("top"))
    check("perks are inherited, largest value wins", topPerks[Perk.HOMES] == 4 && topPerks[Perk.SELL_BONUS_PERCENT] == 5 &&
        topPerks[Perk.VAULTS] == 5 && topPerks[Perk.ORDER_EXTRA_ORDERS] == 4 && topPerks[Perk.KIT_COOLDOWN_REDUCTION_PERCENT] == 25)
    check("low rank perks only", LifestealRankRules.perks(s, setOf("low")) == mapOf(Perk.HOMES to 2, Perk.SELL_BONUS_PERCENT to 5, Perk.AUCTION_EXTRA_LISTINGS to 1))
    check("permissions are inherited", LifestealRankRules.permissions(s, setOf("top")) == setOf("joshymc.chatcolor", "joshymc.tag.voucher.mid"))
    check("no rank = no perks, no permissions", LifestealRankRules.perks(s, emptySet()).isEmpty() && LifestealRankRules.permissions(s, emptySet()).isEmpty())

    // ── Configuration disabling (§14) ──────────────────────────────────────────────
    val perkOff = parse(LADDER.replace("disabled-perks: []", "disabled-perks: [sell-bonus-percent]")).settings!!
    check("a disabled perk is 0 for everyone", Perk.SELL_BONUS_PERCENT !in LifestealRankRules.perks(perkOff, setOf("top")))
    val topOff = parse(LADDER.replace("    order: 3\n", "    order: 3\n    enabled: false\n")).settings!!
    check("a disabled rank stops counting (falls back to the next owned one)", LifestealRankRules.currentRank(topOff, setOf("low", "top"))?.id == "low")
    check("a disabled rank's kit is closed", LifestealRankRules.kitAccess(topOff, setOf("top"), "top_kit") == KitAccess.RANK_DISABLED)
    check("a disabled rank can't be granted", LifestealRankRules.decideGrant(topOff, emptySet(), "top", Source.ADMIN, false) == Decision.RANK_DISABLED)
    val systemOff = parse(LADDER.replace("enabled: true", "enabled: false")).settings!!
    check("system off: no perks", LifestealRankRules.perks(systemOff, setOf("top")).isEmpty())
    check("system off: no grants", LifestealRankRules.decideGrant(systemOff, emptySet(), "low", Source.ADMIN, false) == Decision.SYSTEM_DISABLED)
    check("system off: rank kits closed", LifestealRankRules.kitAccess(systemOff, setOf("top"), "top_kit") == KitAccess.SYSTEM_DISABLED)

    // ── Grants and upgrades (§8) ───────────────────────────────────────────────────
    check("free rank via /freerank", LifestealRankRules.decideGrant(s, emptySet(), "low", Source.FREE, false) == Decision.GRANT)
    check("free rank isn't for sale", LifestealRankRules.decideGrant(s, emptySet(), "low", Source.STORE, false) == Decision.NOT_OFFERED)
    check("store rank isn't free", LifestealRankRules.decideGrant(s, emptySet(), "top", Source.FREE, false) == Decision.NOT_OFFERED)
    check("upgrade low → top allowed", LifestealRankRules.decideGrant(s, setOf("low"), "top", Source.STORE, false) == Decision.GRANT)
    check("already-owned rank can't be bought", LifestealRankRules.decideGrant(s, setOf("top"), "top", Source.STORE, false) == Decision.ALREADY_OWNED)
    check("no downgrade purchase", LifestealRankRules.decideGrant(s, setOf("top"), "mid", Source.STORE, false) == Decision.HIGHER_OWNED)
    check("no downgrade by staff either", LifestealRankRules.decideGrant(s, setOf("top"), "low", Source.ADMIN, false) == Decision.HIGHER_OWNED)
    check("one change in flight per player", LifestealRankRules.decideGrant(s, setOf("low"), "top", Source.STORE, true) == Decision.PENDING)
    check("unknown rank", LifestealRankRules.decideGrant(s, emptySet(), "ghost", Source.ADMIN, false) == Decision.UNKNOWN_RANK)

    // ── Progression (§7) ───────────────────────────────────────────────────────────
    val mid = s.rank("mid")!!
    fun stats(play: Long?, kills: Long?, unique: Long?) = LifestealRankRules.Stats(mapOf(
        RequirementType.PLAYTIME_HOURS to play, RequirementType.PVP_KILLS to kills, RequirementType.UNIQUE_VICTIMS to unique))
    check("requirements met", LifestealRankRules.requirementsMet(mid, stats(10, 5, 3)))
    check("one requirement short", !LifestealRankRules.requirementsMet(mid, stats(10, 5, 2)))
    check("an unreadable statistic never counts", !LifestealRankRules.requirementsMet(mid, stats(10, null, 3)))
    check("a rank without requirements is never earned", !LifestealRankRules.requirementsMet(s.rank("top")!!, stats(999, 999, 999)))
    check("progression rank offered", LifestealRankRules.decideGrant(s, setOf("low"), "mid", Source.PROGRESSION, false) == Decision.GRANT)
    check("non-progression rank can't be claimed by progress", LifestealRankRules.decideGrant(s, setOf("low"), "top", Source.PROGRESSION, false) == Decision.NOT_OFFERED)
    check("next progression rank", LifestealRankRules.nextProgressionRank(s, s.rank("low"))?.id == "mid" &&
        LifestealRankRules.nextProgressionRank(s, s.rank("top")) == null)
    check("season scope parsed", mid.requirementScope == LifestealRankConfig.RequirementScope.SEASON)
    check("progress rows ordered", LifestealRankRules.progress(mid, stats(1, 2, 3)).map { it.type } ==
        listOf(RequirementType.PLAYTIME_HOURS, RequirementType.PVP_KILLS, RequirementType.UNIQUE_VICTIMS))

    // ── Kits (§5) ──────────────────────────────────────────────────────────────────
    check("own rank kit allowed", LifestealRankRules.kitAccess(s, setOf("low"), "low_kit") == KitAccess.ALLOWED)
    check("higher rank gets lower rank kits", LifestealRankRules.kitAccess(s, setOf("top"), "low_kit") == KitAccess.ALLOWED &&
        LifestealRankRules.kitAccess(s, setOf("top"), "mid_kit") == KitAccess.ALLOWED)
    check("lower rank can't use higher kits", LifestealRankRules.kitAccess(s, setOf("low"), "top_kit") == KitAccess.LOCKED)
    check("no rank = rank kits locked", LifestealRankRules.kitAccess(s, emptySet(), "low_kit") == KitAccess.LOCKED)
    check("starter/special kits aren't rank kits", LifestealRankRules.kitAccess(s, emptySet(), "starter") == KitAccess.NOT_A_RANK_KIT)
    check("kit order follows the ladder", LifestealRankRules.kitOrder(s) == listOf("low_kit", "mid_kit", "top_kit"))
    check("cooldown reduction applied", LifestealRankRules.kitCooldownMillis(24, 25) == 18L * 3_600_000L)
    check("cooldown reduction capped at 50%", LifestealRankRules.kitCooldownMillis(24, 90) == 12L * 3_600_000L)
    check("zero-hour kit has no cooldown", LifestealRankRules.kitCooldownMillis(0, 0) == 0L)
    check("cooldown remaining", LifestealRankRules.cooldownRemaining(1_000L, 500L, 1_200L) == 300L &&
        LifestealRankRules.cooldownRemaining(null, 500L, 1_200L) == 0L && LifestealRankRules.cooldownRemaining(1_000L, 100L, 5_000L) == 0L)

    // ── Store against SQLite: grants, duplicates, restart recovery, ownership ──────
    val dir = Files.createTempDirectory("joshymc-lifesteal-rank-check").toFile()
    try {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        var clock = 1_000L
        fun decide(target: String, source: Source) = { owned: Set<String>, pending: Boolean -> LifestealRankRules.decideGrant(s, owned, target, source, pending) }

        val firstId = withDb(dir) { db ->
            val store = LifestealRankStore(db) { clock }
            store.createTables()
            store.createTables() // idempotent

            val r1 = store.requestGrant(alice, "Alice", "low", "ls_low", null, "free", "freerank:$alice", "player:$alice", decide("low", Source.FREE))
            check("grant recorded PENDING before LuckPerms", r1 is LifestealRankStore.RequestResult.Requested && r1.op.status == LifestealRankStore.Status.PENDING)
            val op1 = (r1 as LifestealRankStore.RequestResult.Requested).op
            check("not owned until LuckPerms saved", store.owned(alice).isEmpty())
            val again = store.requestGrant(alice, "Alice", "low", "ls_low", null, "free", "freerank:$alice", "player:$alice", decide("low", Source.FREE))
            check("same reference = duplicate, nothing new (free rank once per player)", again is LifestealRankStore.RequestResult.Duplicate && again.existing.id == op1.id)
            val second = store.requestGrant(alice, "Alice", "top", "ls_top", null, "store", "purchase:1", "store:#1", decide("top", Source.STORE))
            check("second change while one is pending refused", second is LifestealRankStore.RequestResult.Refused && second.decision == Decision.PENDING)
            var threw = false
            try {
                db.execute("INSERT INTO lifesteal_rank_ops (id, uuid, player_name, rank_id, grp, action, source, actor, status, created_at, updated_at) VALUES ('x', ?, 'Alice', 'top', 'ls_top', 'GRANT', 'store', 'x', 'PENDING', 0, 0)", alice.toString())
            } catch (_: Exception) { threw = true }
            check("database itself refuses a second PENDING change for a player", threw)
            op1.id
        }

        // "Restart": a fresh connection finds the pending change and finishes it once.
        withDb(dir) { db ->
            val store = LifestealRankStore(db) { clock }
            store.createTables()
            check("pending change survives a restart", store.pending(10).map { it.id } == listOf(firstId))
            clock = 2_000L
            check("completed once", store.complete(firstId, "luckperms ADDED"))
            check("completing again does nothing", !store.complete(firstId))
            check("owned after completion", store.owned(alice) == setOf("low"))

            // Upgrade through the store: lower rank is kept (cumulative ownership).
            val up = store.requestGrant(alice, "Alice", "top", "ls_top", null, "store", "purchase:7", "store:#7", decide("top", Source.STORE))
            check("upgrade recorded", up is LifestealRankStore.RequestResult.Requested)
            val upId = (up as LifestealRankStore.RequestResult.Requested).op.id
            check("store retry of the same purchase = duplicate", store.requestGrant(alice, "Alice", "top", "ls_top", null, "store", "purchase:7", "store:#7", decide("top", Source.STORE)) is LifestealRankStore.RequestResult.Duplicate)
            store.complete(upId)
            check("upgrade keeps the lower rank", store.owned(alice) == setOf("low", "top"))
            val dup = store.requestGrant(alice, "Alice", "top", "ls_top", null, "store", "purchase:8", "store:#8", decide("top", Source.STORE))
            check("buying an owned rank again refused", dup is LifestealRankStore.RequestResult.Refused && dup.decision == Decision.ALREADY_OWNED)
            val down = store.requestGrant(alice, "Alice", "mid", "ls_mid", null, "store", "purchase:9", "store:#9", decide("mid", Source.STORE))
            check("downgrade purchase refused", down is LifestealRankStore.RequestResult.Refused && down.decision == Decision.HIGHER_OWNED)

            // LuckPerms failures: retried, then FAILED; operator retry; cancel.
            val b1 = store.requestGrant(bob, "Bob", "top", "ls_top", "lifesteal", "admin", null, "staff:x", decide("top", Source.ADMIN)) as LifestealRankStore.RequestResult.Requested
            check("server context stored", b1.op.context == "lifesteal")
            for (i in 1 until 5) check("attempt $i stays pending", store.recordFailure(b1.op.id, 5, "LuckPerms down") == LifestealRankStore.Status.PENDING)
            check("fifth failure = FAILED", store.recordFailure(b1.op.id, 5, "LuckPerms down") == LifestealRankStore.Status.FAILED)
            check("failed grant grants nothing", store.owned(bob).isEmpty())
            check("failed list", store.failed(10).any { it.id == b1.op.id })
            check("a failed change can't complete", !store.complete(b1.op.id))
            check("retry FAILED → PENDING", store.retry(b1.op.id, "staff:y") && store.get(b1.op.id)?.status == LifestealRankStore.Status.PENDING)
            check("retry twice refused", !store.retry(b1.op.id, "staff:y"))
            check("cancel", store.cancel(b1.op.id, "staff:y", "test") && store.get(b1.op.id)?.status == LifestealRankStore.Status.CANCELLED)
            check("cancelled can't complete", !store.complete(b1.op.id))

            // Revoke: ownership row kept, marked revoked; then a re-grant clears it.
            val rv = store.requestRevoke(alice, "Alice", "top", "ls_top", null, null, "staff:z") as LifestealRankStore.RequestResult.Requested
            store.complete(rv.op.id)
            check("revoked rank no longer owned, lower kept", store.owned(alice) == setOf("low"))
            check("revoke keeps the ownership row", store.ownership(alice).any { it.rankId == "top" && it.revokedAt != null })
            check("revoking a rank they don't own refused", store.requestRevoke(alice, "Alice", "top", "ls_top", null, null, "staff:z") is LifestealRankStore.RequestResult.Refused)

            var deleteThrew = false
            try { db.execute("DELETE FROM lifesteal_rank_ownership WHERE uuid = ?", alice.toString()) } catch (_: Exception) { deleteThrew = true }
            check("ownership rows can't be deleted (no season wipe)", deleteThrew && store.owned(alice) == setOf("low"))
            var auditThrew = false
            try { db.execute("UPDATE lifesteal_rank_audit SET actor = 'x'") } catch (_: Exception) { auditThrew = true }
            check("audit is append-only", auditThrew)
            val history = store.history(alice, 50).map { it.action }
            check("history records every step", listOf("GRANT_REQUESTED", "GRANT_COMPLETED", "REVOKE_REQUESTED", "REVOKE_COMPLETED").all { it in history })

            // Progression statistics come only from moved hearts.
            check("no heart table = statistic unavailable (never met)", store.pvpKills(alice, 0) == null)
            db.createTable("CREATE TABLE lifesteal_heart_transactions (id TEXT PRIMARY KEY, dedupe_key TEXT NOT NULL UNIQUE, type TEXT NOT NULL, outcome TEXT NOT NULL, killer TEXT, victim TEXT, actor TEXT, created_at INTEGER NOT NULL)")
            var n = 0
            fun kill(victim: UUID, outcome: String, at: Long, type: String = "PVP") = db.execute(
                "INSERT INTO lifesteal_heart_transactions VALUES (?, ?, ?, ?, ?, ?, NULL, ?)", "t${n}", "d${n++}", type, outcome, alice.toString(), victim.toString(), at)
            val v1 = UUID.randomUUID(); val v2 = UUID.randomUUID()
            kill(v1, "TRANSFERRED", 100); kill(v1, "TRANSFERRED", 200); kill(v2, "TRANSFERRED", 300)
            kill(v2, "COOLDOWN", 400); kill(v2, "TEAMMATES", 500); kill(v2, "GRACE_PERIOD", 600); kill(v1, "WITHDRAWN", 700, "WITHDRAW")
            check("only heart-moving PvP kills count", store.pvpKills(alice, 0) == 3L)
            check("unique victims", store.uniqueVictims(alice, 0) == 2L)
            check("season scope counts from launch", store.pvpKills(alice, 250) == 1L)

            // Kit reservations: one claim per cooldown, restart-proof.
            db.createTable("CREATE TABLE IF NOT EXISTS kit_cooldowns (uuid TEXT NOT NULL, kit TEXT NOT NULL, claimed_at INTEGER NOT NULL, PRIMARY KEY (uuid, kit))")
            check("first claim reserved", store.reserveKitClaim(alice, "rogue", 10_000, 5_000))
            check("second claim inside the cooldown refused", !store.reserveKitClaim(alice, "rogue", 12_000, 5_000))
            check("other players unaffected", store.reserveKitClaim(bob, "rogue", 12_000, 5_000))
            check("claim after the cooldown", store.reserveKitClaim(alice, "rogue", 15_000, 5_000) && store.kitClaimedAt(alice, "rogue") == 15_000L)
            store.releaseKitClaim(alice, "rogue", 15_000, 10_000)
            check("release restores the previous claim", store.kitClaimedAt(alice, "rogue") == 10_000L)
            store.releaseKitClaim(bob, "rogue", 12_000, null)
            check("release of a first claim removes it", store.kitClaimedAt(bob, "rogue") == null)
            check("bypass (0 ms) always reserves", store.reserveKitClaim(alice, "rogue", 10_001, 0))
        }
        withDb(dir) { db ->
            val store = LifestealRankStore(db) { clock }
            check("ownership persists across restarts", store.owned(alice) == setOf("low"))
            check("kit cooldown persists across restarts (reconnects can't reset it)", !store.reserveKitClaim(alice, "rogue", 10_002, 5_000))
        }
    } finally {
        dir.deleteRecursively()
    }

    // ── Store reward `lsrank` (§11) ────────────────────────────────────────────────
    val none = object : PurchaseRewards.Catalog {
        override fun crateExists(id: String) = false
        override fun moneyVoucherExists(id: String) = false
        override fun chatTagVoucherExists(id: String) = false
    }
    val sells = object : PurchaseRewards.Catalog {
        override fun crateExists(id: String) = false
        override fun moneyVoucherExists(id: String) = false
        override fun chatTagVoucherExists(id: String) = false
        override fun lifestealRankPurchasable(id: String) = id == "top"
    }
    check("lsrank refused where no Lifesteal rank is sold (e.g. Survival)", PurchaseRewards.parse(listOf("lsrank", "top"), none) is PurchaseRewards.Result.Error)
    check("lsrank refused for a rank not for sale", PurchaseRewards.parse(listOf("lsrank", "low"), sells) is PurchaseRewards.Result.Error)
    val parsed = PurchaseRewards.parse(listOf("lsrank", "TOP", "tbx-123"), sells)
    check("lsrank parsed with reference", parsed is PurchaseRewards.Result.Ok && parsed.value.spec.param == "top" && parsed.value.reference == "tbx-123" &&
        parsed.value.spec.kind.delivery == PurchaseRewards.Delivery.LIFESTEAL_RANK)
    check("lsrank never becomes a Survival donor tier", PurchaseRewards.parse(listOf("rank", "legacy"), sells) is PurchaseRewards.Result.Error)
    check("unexpanded placeholder refused", PurchaseRewards.parse(listOf("lsrank", "top", "{transaction}"), sells) is PurchaseRewards.Result.Error)

    // ── Wiring: Lifesteal only, Survival untouched ─────────────────────────────────
    check("/lsranks is LIFESTEAL scope", GamemodeAssignments.COMMANDS["lsranks"] == GamemodeScope.LIFESTEAL)
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("plugin.yml declares /lsranks", Regex("""(?m)^  lsranks:\n(    .*\n)*    permission: joshymc\.lifesteal\.ranks\.use$""").containsMatchIn(pluginYml))
    check("rank admin is op", Regex("""(?m)^  joshymc\.lifesteal\.ranks\.admin:\n    description: .*\n    default: op$""").containsMatchIn(pluginYml))
    check("viewing others is op", Regex("""(?m)^  joshymc\.lifesteal\.ranks\.view\.others:\n    description: .*\n    default: op$""").containsMatchIn(pluginYml))
    val src = { path: String -> File("src/main/kotlin/com/liam/joshymc/$path").readText() }
    val commandManager = src("manager/CommandManager.kt")
    check("/lsranks only registered where Lifesteal is hosted", "plugin.lifestealRankManager.hosted" in commandManager && "unregisterCommand(\"lsranks\")" in commandManager)
    val manager = src("lifesteal/ranks/LifestealRankManager.kt")
    check("never parent set", "parent set" !in manager.replace("never `parent set`", "").replace("never `parent set`,", ""))
    check("grants go through the one-group LuckPerms API by UUID", "lp.setGroup(op.uuid, op.group, present, op.context)" in manager)
    check("perks only apply in a Lifesteal world", "fun appliesTo(player: Player): Boolean = ready && settings?.enabled == true && isLifesteal(player)" in manager)
    check("rank permissions are removed outside Lifesteal", "if (!appliesTo(player)) return" in manager && "onWorldChange" in manager)
    check("a removed rank's pending grant is cancelled, never sent", "is no longer defined" in manager)
    check("Survival rank perk path unchanged outside Lifesteal", "takeIf { it.appliesTo(player) }?.let { return it.economyPerk(player) }" in src("manager/RankManager.kt"))
    val kit = src("manager/KitManager.kt")
    check("Survival kits keep their permission check", "if (!kit.isLifesteal) return player.hasPermission(kit.permission)" in kit)
    check("Lifesteal kits use the reserved claim", "if (kit.isLifesteal) return claimLifestealKit(player, kit)" in kit)
    check("Lifesteal kit claim: fit check before reserving", kit.indexOf("probe.addItem") in 0 until kit.indexOf("reserveKitClaim(player.uniqueId"))
    check("Lifesteal kit claim refused in combat / ranked / mod mode", "isTagged(player)" in kit && "lifestealRankedManager.holds(player)" in kit && "isModMode(player)) return refuse" in kit)
    check("/freerank on Lifesteal goes to the Lifesteal ranks", "plugin.lifestealRankManager.claimFree(player)" in src("freerank/FreeRankManager.kt"))
    check("Survival /freerank still Scout", com.liam.joshymc.freerank.FreeRanks.starterRank(com.liam.joshymc.gamemode.ServerGamemode.SURVIVAL) == "scout")
    val purchase = src("manager/PurchaseFulfillmentManager.kt")
    check("store rank change keyed by the delivery row", "\"purchase:\${row.id}\"" in purchase)
    check("Lifesteal rank delivery never in Survival donor tiers", "Delivery.LIFESTEAL_RANK -> if (isKnownPlayer(uuid)) processLifestealRank(row)" in purchase)
    check("shutdown step registered", "lifestealRankManager.stop" in src("Joshymc.kt"))
    check("admin grant/revoke respects staff protection and audits", src("lifesteal/ranks/LifestealRanksCommand.kt").let {
        "protectionProblem(sender, uuid)" in it && "You can't change your own Lifesteal rank." in it && "AdminAudit.log" in it })

    println("Lifesteal rank checks passed ($checks checks).")
}
