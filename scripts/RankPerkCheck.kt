// Checks the purchasable rank perk wiring (issue #1381): the donor tier hierarchy, rank-based home /
// player-warp limits including donor tiers, the rank-perk bonuses in config.yml, the permission
// defaults donors could reach, and the gamemode scopes of the rank perk commands. The Gradle build
// has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/rank-perk-check.init.gradle.kts rankPerkCheck
//
// Paper's classes target Java 25 and this runs on the Java 21 toolchain, so it never loads a
// Bukkit class: YAML through snakeyaml, command wiring by source text. Exits non-zero on the
// first failed check. Live LuckPerms groups are not in the repository and can't be checked here.

import com.liam.joshymc.freerank.FreeRanks
import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.DonorTiers
import com.liam.joshymc.manager.RankLimits
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

@Suppress("UNCHECKED_CAST")
private fun Any?.map(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

private fun Map<String, Any?>.path(path: String): Any? {
    var node: Any? = this
    for (part in path.split('.')) node = (node as? Map<*, *>)?.get(part)
    return node
}

private val TIERS = listOf("scout", "pathfinder", "lumberjack", "trailblazer", "voyager", "ranger", "pioneer")

/**
 * Staff/admin power: these nodes and everything below them (`<node>.…`). None of them may be
 * granted to everyone by plugin.yml, and none of them is a donor perk.
 */
private val STAFF_NODES = listOf(
    "joshymc.admin", "joshymc.staff", "joshymc.eco", "joshymc.give", "joshymc.ban", "joshymc.tempban", "joshymc.unban",
    "joshymc.mute", "joshymc.tempmute", "joshymc.unmute", "joshymc.warn", "joshymc.unwarn", "joshymc.vanish",
    "joshymc.modmode", "joshymc.invsee", "joshymc.sudo", "joshymc.gamemode", "joshymc.god", "joshymc.fly",
    "joshymc.heal", "joshymc.feed", "joshymc.tp", "joshymc.tphere", "joshymc.crate.admin", "joshymc.crateeditor",
    "joshymc.vouchers", "joshymc.rankvoucher.admin", "joshymc.rank", "joshymc.rankperk", "joshymc.jgm",
    "joshymc.kit.bypass.*", "joshymc.kit.cooldown.bypass", "joshymc.createkit", "joshymc.deletekit",
    "joshymc.sethome.unlimited", "joshymc.claim.admin", "joshymc.claim.bypass", "joshymc.nick.others", "joshymc.mcr",
    "joshymc.punish.trainee", "joshymc.punish.network",
)

/** Name parts that only appear in staff/admin nodes. */
private val STAFF_NODE_PARTS = listOf(".admin", ".reload", ".bypass", ".others", ".override", ".recover")

/** Player-facing nodes with a staff-looking name part; each is default true on purpose. */
private val PLAYER_NODES_ALLOWED_TRUE = setOf(
    "joshymc.lifesteal.ranked.stats.others", // read-only ranked statistics of other players (issue #1365)
)

private fun isStaffNode(node: String): Boolean =
    STAFF_NODES.any { node == it || node.startsWith("$it.") } || STAFF_NODE_PARTS.any { it in node }

fun main() {
    // ── Donor tier hierarchy ────────────────────────────────
    check("donor tiers are the seven Survival ranks, lowest first", DonorTiers.ORDER == TIERS)
    for ((i, tier) in TIERS.withIndex()) {
        check("$tier is a tier", DonorTiers.isTier(tier) && DonorTiers.index(tier) == i)
        check("$tier nodes", DonorTiers.nodesFor(tier) == listOf("group.$tier", "joshymc.rankperk.$tier"))
        for (lower in TIERS.take(i)) {
            check("$tier is an upgrade over $lower", DonorTiers.compare(lower, tier) == DonorTiers.Comparison.UPGRADE)
            check("$lower is below $tier", DonorTiers.compare(tier, lower) == DonorTiers.Comparison.LOWER_TIER)
            check("$tier wins over $lower", DonorTiers.highest(listOf(lower, tier)) == tier)
        }
        check("$tier upgrade cleans only lower tiers", DonorTiers.below(tier, TIERS) == TIERS.take(i))
    }
    for (special in listOf("media", "discord", "default", "member", "vip", "helper", "mod", "admin", "owner", "legacy", "rogue")) {
        check("$special is not a donor tier", !DonorTiers.isTier(special))
    }
    check("staff ranks never count as a donor tier", DonorTiers.highest(listOf("owner", "admin", "mod")) == null)
    check("pioneer is the top tier", DonorTiers.highest(TIERS) == "pioneer")

    // Wildcard/op holders (staff) answer yes to every node, so only explicit grants may count.
    val everything: (String) -> Boolean = { true }
    check("a wildcard holder isn't Pioneer", DonorTiers.liveTiers(emptySet(), wildcard = true, hasPermission = everything).isEmpty())
    check("a wildcard holder with an explicit tier keeps it",
        DonorTiers.liveTiers(setOf("group.voyager"), wildcard = true, hasPermission = everything) == setOf("voyager"))
    check("inherited tier groups show up for ordinary players",
        DonorTiers.liveTiers(emptySet(), wildcard = false) { it == "group.scout" || it == "joshymc.rankperk.pathfinder" } == setOf("scout", "pathfinder"))
    check("a player with no tier nodes has no tier", DonorTiers.liveTiers(emptySet(), wildcard = false) { false }.isEmpty())
    check("Survival starter rank is scout", FreeRanks.starterRank(ServerGamemode.SURVIVAL) == "scout")
    check("Lifesteal has no starter rank yet", FreeRanks.starterRank(ServerGamemode.LIFESTEAL) == null)
    check("parent set reward commands are routed to the checked grant",
        DonorTiers.tierFromRewardCommand("lp user %player% parent set ranger") == "ranger")
    check("non-tier reward commands aren't tiers", DonorTiers.tierFromRewardCommand("lp user %player% parent add admin") == null)

    // ── Home / player-warp limits ───────────────────────────
    val limits = mapOf("owner" to -1, "vip" to 5, "member" to 3, "default" to 3, "scout" to 4, "voyager" to 8, "pioneer" to -1)
    val limitFor = { id: String -> limits[id] }
    fun limit(ranks: Collection<String>, tier: String?, fallback: Int = 3) = RankLimits.resolve(ranks, tier, limitFor, fallback)
    check("no rank uses default", limit(emptyList(), null) == 3)
    check("no entry at all uses the fallback", RankLimits.resolve(emptyList(), null, { null }, 2) == 2)
    check("highest JoshyMC rank wins", limit(listOf("member", "vip"), null) == 5)
    check("an unlimited rank is unlimited", limit(listOf("member", "owner"), null) == RankLimits.UNLIMITED)
    check("donor tier entry raises the limit", limit(emptyList(), "scout") == 4)
    check("higher donor tier gets its own limit", limit(emptyList(), "voyager") == 8)
    check("donor tier never lowers a higher rank", limit(listOf("vip"), "scout") == 5)
    check("unlimited donor tier", limit(listOf("member"), "pioneer") == RankLimits.UNLIMITED)
    check("donor tier without an entry changes nothing", limit(listOf("vip"), "ranger") == 5)
    check("donor tier without an entry keeps the default", limit(emptyList(), "trailblazer") == 3)
    check("non-tier ids are not looked up as a donor tier", limit(emptyList(), "owner") == 3)
    check("tier ids are normalized", limit(emptyList(), " Voyager ") == 8)
    check("unlimited rank beats a tier", limit(listOf("owner"), "scout") == RankLimits.UNLIMITED)
    for (file in listOf("command/SetHomeCommand.kt", "command/PlayerWarpCommand.kt")) {
        val text = source(file)
        check("$file uses RankLimits", "RankLimits.resolve(" in text)
        check("$file passes the donor tier", "currentDonorTier(player.uniqueId)" in text)
    }

    // ── Bundled configuration ───────────────────────────────
    val config = resource("config.yml")
    val perks = config.path("rank-perks").map()
    check("rank-perks lists exactly the donor tiers in order", perks.keys.toList() == TIERS)
    var previous: Triple<Int, Int, Double>? = null
    for (tier in TIERS) {
        val perk = perks[tier].map()
        val current = Triple(
            (perk["auction-extra-listings"] as Number).toInt(),
            (perk["orders-extra-orders"] as Number).toInt(),
            (perk["sell-multiplier"] as Number).toDouble(),
        )
        check("$tier bonuses are non-negative", current.first >= 0 && current.second >= 0 && current.third >= 1.0)
        previous?.let {
            check("$tier keeps every lower tier's bonus", current.first >= it.first && current.second >= it.second && current.third >= it.third)
        }
        previous = current
    }
    check("Survival rank kit order is the donor hierarchy", config.path("kits.rank-order.survival") == TIERS)
    check("Lifesteal has no rank kit order yet", (config.path("kits.rank-order.lifesteal") as? List<*>).isNullOrEmpty())
    val homeLimits = config.path("homes.limits-by-rank").map()
    val warpLimits = config.path("warps.limits-by-rank").map()
    for (tier in TIERS) {
        // The fix must not invent a benefit: tier entries only exist once an admin adds them.
        check("bundled homes has no $tier entry", tier !in homeLimits)
        check("bundled pwarps has no $tier entry", tier !in warpLimits)
    }
    val ranksList = config.path("ranks.list").map()
    for (tier in TIERS) check("$tier is not a ranks.list rank", tier !in ranksList)
    val joshymc = source("Joshymc.kt")
    for (prefix in listOf("homes.limits-by-rank.", "warps.limits-by-rank.", "rank-perks.")) {
        check("$prefix is never backfilled", "\"$prefix\"" in joshymc)
    }

    // ── Permission defaults (plugin.yml) ────────────────────
    val pluginYml = resource("plugin.yml")
    val perms = pluginYml.path("permissions").map()
    fun default(node: String) = perms[node].map()["default"]?.toString()
    for (tier in TIERS) {
        val node = "joshymc.rankperk.$tier"
        check("$node is declared", node in perms)
        check("$node is never granted by default", default(node) == "false")
        check("$node has no children", perms[node].map()["children"] == null)
    }
    for (node in listOf("joshymc.heal.others", "joshymc.feed.others", "joshymc.fly.others", "joshymc.nick.others", "joshymc.enderchest.others")) {
        check("$node is declared", node in perms)
        check("$node is op only", default(node) == "op")
    }
    for ((node, value) in perms) {
        val def = value.map()["default"]?.toString()
        if (def == "true" && node !in PLAYER_NODES_ALLOWED_TRUE) {
            check("$node (default true) is not a staff node", !isStaffNode(node))
        }
        // A node everyone has must not hand out a node that only staff/ops have.
        if (def == "true") {
            for ((child, granted) in value.map()["children"].map()) {
                if (granted == true) check("$node (default true) only grants player nodes ($child)", default(child) in setOf(null, "true"))
            }
        }
    }

    // ── /heal and /feed on other players ────────────────────
    val essentials = source("command/EssentialsCommands.kt")
    check("/heal <player> needs joshymc.heal.others", "!sender.hasPermission(\"joshymc.heal.others\") && !isSelf(sender, args[0])" in essentials)
    check("/feed <player> needs joshymc.feed.others", "!sender.hasPermission(\"joshymc.feed.others\") && !isSelf(sender, args[0])" in essentials)
    check("/heal only suggests players with .others", "args.size == 1 && sender.hasPermission(\"joshymc.heal.others\")" in essentials)
    check("/feed only suggests players with .others", "args.size == 1 && sender.hasPermission(\"joshymc.feed.others\")" in essentials)

    // ── Gamemode scopes of rank perk commands ───────────────
    val scopes = GamemodeAssignments.COMMANDS
    for (command in listOf("rankvoucher", "voucher", "cshop", "ah", "orders", "sell", "sethome", "home", "pwarp", "pv",
        "fly", "flytime", "chatcolor", "tag", "killeffect", "cosmetics", "mcr", "signitem", "hat", "craft", "anvil", "enderchest", "repair")) {
        check("/$command is a Survival perk", scopes[command] == GamemodeScope.SURVIVAL)
    }
    for (command in listOf("kit", "rank", "freerank", "purchase", "nick", "heal", "feed")) {
        check("/$command is network-wide", scopes[command] == GamemodeScope.NETWORK)
    }

    println("OK: $checks rank perk checks passed.")
}
