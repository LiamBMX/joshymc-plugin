// Checks /freetag (issue #1339): the Bukkit-free claim rules, the one-time claim store against a
// real SQLite database (in a fresh temp directory, deleted afterwards), and the command wiring.
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/free-tag-check.init.gradle.kts freeTagCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.freetag.FreeTagStore
import com.liam.joshymc.freetag.FreeTags
import com.liam.joshymc.freetag.FreeTags.Decision
import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.DatabaseManager
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

private fun withDb(dir: File, block: (DatabaseManager) -> Unit) {
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        block(db)
    } finally {
        db.shutdown()
    }
}

fun main() {
    // ── Voucher and messages ───────────────────────────────
    check("voucher id is chattag_joshymc", FreeTags.VOUCHER_ID == "chattag_joshymc")
    check("tag id is joshymc", FreeTags.TAG_ID == "joshymc")
    check("success message", FreeTags.MSG_SUCCESS == "&aYou successfully claimed your free &f[JoshyMC] &achat tag voucher!")
    check("already claimed message", FreeTags.MSG_ALREADY_CLAIMED == "&cYou have already claimed your free JoshyMC chat tag!")
    check("delivery failure message", FreeTags.MSG_DELIVERY_FAILED == "&cUnable to deliver your free chat tag. Please try again later.")

    // ── Decisions ──────────────────────────────────────────
    val s = ServerGamemode.SURVIVAL
    check("first claim on Survival", FreeTags.decide(s, claimed = false, tagAvailable = true, hasRoom = true) == Decision.CLAIM)
    check("second claim refused", FreeTags.decide(s, claimed = true, tagAvailable = true, hasRoom = true) == Decision.ALREADY_CLAIMED)
    check("claimed wins over a full inventory", FreeTags.decide(s, claimed = true, tagAvailable = true, hasRoom = false) == Decision.ALREADY_CLAIMED)
    check("missing tag can't deliver", FreeTags.decide(s, claimed = false, tagAvailable = false, hasRoom = true) == Decision.TAG_MISSING)
    check("full inventory can't deliver", FreeTags.decide(s, claimed = false, tagAvailable = true, hasRoom = false) == Decision.NO_ROOM)
    for (gm in ServerGamemode.entries.filter { it != ServerGamemode.SURVIVAL }) {
        check("unavailable on ${gm.id}", FreeTags.decide(gm, claimed = false, tagAvailable = true, hasRoom = true) == Decision.WRONG_GAMEMODE)
    }

    // ── Scope ──────────────────────────────────────────────
    check("freetag is SURVIVAL scope", GamemodeAssignments.COMMANDS["freetag"] == GamemodeScope.SURVIVAL)
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("plugin.yml declares /freetag", Regex("""(?m)^  freetag:\n(    .*\n)*    permission: joshymc\.freetag\.use$""").containsMatchIn(pluginYml))
    check("joshymc.freetag.use defaults to true", Regex("""(?m)^  joshymc\.freetag\.use:\n    description: .*\n    default: true$""").containsMatchIn(pluginYml))

    // ── Store ──────────────────────────────────────────────
    val dir = Files.createTempDirectory("joshymc-freetag-check").toFile()
    try {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        val carol = UUID.randomUUID()

        withDb(dir) { db ->
            val store = FreeTagStore(db)
            store.createTables()
            store.createTables() // idempotent

            check("nobody has claimed yet", !store.hasClaimed(alice))
            check("first reserve succeeds", store.reserve(alice, "Alice", FreeTags.VOUCHER_ID, 1L))
            check("reserved claim counts as claimed", store.hasClaimed(alice))
            check("spam: second reserve refused", !store.reserve(alice, "Alice", FreeTags.VOUCHER_ID, 2L))
            check("renamed player still refused (keyed by UUID)", !store.reserve(alice, "Alice2", FreeTags.VOUCHER_ID, 3L))
            check("complete once", store.markCompleted(alice))
            check("complete is one-shot", !store.markCompleted(alice))
            check("completed claim is never released", !store.release(alice))
            check("completed status", store.status(alice) == FreeTagStore.Status.COMPLETED)

            // Failed delivery: reservation dropped, player can claim again.
            check("bob reserves", store.reserve(bob, "Bob", FreeTags.VOUCHER_ID, 4L))
            check("failed delivery releases", store.release(bob))
            check("released claim isn't claimed", !store.hasClaimed(bob))
            check("bob can retry after a failure", store.reserve(bob, "Bob", FreeTags.VOUCHER_ID, 5L))
            check("bob completes", store.markCompleted(bob))

            // Interrupted delivery (crash mid-claim) is left PENDING.
            check("carol reserves", store.reserve(carol, "Carol", FreeTags.VOUCHER_ID, 6L))
        }

        // Restart: completed claims persist, the interrupted one is released.
        withDb(dir) { db ->
            val store = FreeTagStore(db)
            store.createTables()
            check("startup releases exactly the interrupted claim", store.releaseAllPending() == 1)
            check("alice's claim survived the restart", store.status(alice) == FreeTagStore.Status.COMPLETED)
            check("bob's claim survived the restart", store.status(bob) == FreeTagStore.Status.COMPLETED)
            check("alice still can't claim after restart", !store.reserve(alice, "Alice", FreeTags.VOUCHER_ID, 7L))
            check("carol can claim again", store.reserve(carol, "Carol", FreeTags.VOUCHER_ID, 8L))
            check("nothing else pending after release", store.releaseAllPending() == 1)
        }
    } finally {
        dir.deleteRecursively()
    }

    println("Free tag checks passed ($checks checks).")
}
