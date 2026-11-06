// Checks the Unscramble chat game rules and the shared chat game slot/rotation (issue #1395).
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/chat-game-check.init.gradle.kts chatGameCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.chatgame.ChatGameRotation
import com.liam.joshymc.chatgame.ChatGameSlot
import com.liam.joshymc.chatgame.Unscramble
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private enum class Type { MATH, TYPE, UNSCRAMBLE }

private data class Game(val type: Type, val answer: String)

/**
 * Mirrors ChatGamesManager.handleChat: match, then claim the slot; only a
 * successful claim pays. Cancelled chat never reaches it (MONITOR +
 * ignoreCancelled), which [cancelled] stands in for.
 */
private class Server {
    val slot = ChatGameSlot<Game>()
    val payouts = AtomicInteger()
    val winners = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun chat(player: String, message: String, cancelled: Boolean = false): Boolean {
        if (cancelled) return false
        val game = slot.active() ?: return false
        if (!Unscramble.matches(message, game.answer)) return false
        if (!slot.end(game)) return false
        payouts.incrementAndGet()
        winners += player
        return true
    }
}

fun main() {
    val random = Random(1395)

    // ── Configuration loading / validation ──
    val parsed = Unscramble.parsePool(
        listOf("diamond", "  Emerald ", "", null, "DIAMOND", "TNT", "AAAA", "ender pearl", "GOLD1",
            "LISTEN", "SILENT", "ABCDEFGHIJKLMNOPQ", "Creeper")
    )
    check("valid words normalized to upper case and trimmed", parsed.words == listOf("DIAMOND", "EMERALD", "LISTEN", "CREEPER"))
    check("blank / null entries skipped", parsed.problems.count { it.startsWith("blank") } == 2)
    check("duplicate skipped", parsed.problems.any { it.contains("duplicate") })
    check("too short skipped", parsed.problems.any { it.startsWith("'TNT'") })
    check("single letter word skipped (can't scramble)", parsed.problems.any { it.startsWith("'AAAA'") })
    check("spaces refused (letters only)", parsed.problems.any { it.startsWith("'ender pearl'") })
    check("digits refused (letters only)", parsed.problems.any { it.startsWith("'GOLD1'") })
    check("anagram of earlier word skipped (ambiguous)", parsed.problems.any { it.startsWith("'SILENT'") })
    check("too long skipped", parsed.problems.any { it.startsWith("'ABCDEFGHIJKLMNOPQ'") })
    check("blocked words skipped", Unscramble.parsePool(listOf("DIAMOND", "BADWORD")) { it == "BADWORD" }.words == listOf("DIAMOND"))
    check("default pool is fully valid", Unscramble.parsePool(Unscramble.DEFAULT_WORDS).problems.isEmpty())

    // Plain SnakeYAML: Bukkit's YamlConfiguration is Java 25 bytecode and this check runs on 21.
    @Suppress("UNCHECKED_CAST")
    val root = File("src/main/resources/config.yml").reader().use { Yaml().load<Map<String, Any?>>(it) }
    @Suppress("UNCHECKED_CAST")
    fun section(map: Map<String, Any?>, key: String) = map[key] as Map<String, Any?>
    val chatGames = section(root, "chat-games")
    val unscramble = section(chatGames, "unscramble")
    val messages = section(unscramble, "messages")
    check("bundled config has unscramble enabled", unscramble["enabled"] == true)
    val bundled = Unscramble.parsePool(unscramble["words"] as List<Any?>)
    check("bundled word pool has no invalid entries: ${bundled.problems}", bundled.problems.isEmpty())
    check("bundled word pool is big enough to avoid repetition", bundled.words.size >= 40)
    check("bundled pool contains every default word", bundled.words.containsAll(Unscramble.DEFAULT_WORDS))
    check("bundled custom messages default to the standard announcements",
        listOf("start", "win", "timeout").all { messages[it] == "" })
    check("existing reward unchanged", (section(chatGames, "reward")["credits"] as Number).toDouble() == 3.0)
    check("existing interval unchanged", chatGames["interval-seconds"] == 900)
    check("existing time limit unchanged", chatGames["solve-window-seconds"] == 60)

    // ── Scrambling ──
    for (word in bundled.words) {
        repeat(25) {
            val s = Unscramble.scramble(word, random, bundled.words)
            check("$word scrambles", s != null)
            check("$word scramble differs from the answer", s != word)
            check("$word scramble keeps every letter", s!!.toCharArray().sorted() == word.toCharArray().sorted())
            check("$word scramble same length", s.length == word.length)
            check("$word scramble is not another pool word", s !in bundled.words)
        }
    }
    check("all-same-letter word can't scramble", Unscramble.scramble("AAAA", random) == null)
    check("blocked scrambles are avoided", Unscramble.scramble("AB", random) { it == "BA" } == null)
    check("two-letter word scrambles to its swap", Unscramble.scramble("AB", random) == "BA")

    // ── Word selection ──
    check("empty pool gives no round", Unscramble.pick(emptyList(), null, random) == null)
    var last: String? = null
    repeat(500) {
        val round = Unscramble.pick(bundled.words, last, random)
        check("round picked", round != null)
        check("same word never twice in a row", round!!.answer != last)
        check("answer comes from the pool", round.answer in bundled.words)
        last = round.answer
    }
    val single = Unscramble.pick(listOf("DIAMOND"), "DIAMOND", random)
    check("single-word pool may repeat its only word", single?.answer == "DIAMOND")
    val seen = HashSet<String>()
    repeat(2000) { seen += Unscramble.pick(bundled.words, null, random)!!.answer }
    check("selection reaches the whole pool", seen.size == bundled.words.size)
    check("unscramblable-only pool gives up (bounded)", Unscramble.pick(listOf("AAAA"), null, random) == null)

    // ── Answer matching (Java and Bedrock send the same plain chat text) ──
    check("exact answer", Unscramble.matches("DIAMOND", "DIAMOND"))
    check("lower case answer", Unscramble.matches("diamond", "DIAMOND"))
    check("mixed case answer", Unscramble.matches("DiAmOnD", "DIAMOND"))
    check("surrounding whitespace ignored", Unscramble.matches("  diamond \t", "DIAMOND"))
    check("scrambled word is wrong", !Unscramble.matches("NODMIAD", "DIAMOND"))
    check("partial answer refused", !Unscramble.matches("DIAMON", "DIAMOND"))
    check("extra text refused", !Unscramble.matches("diamond lol", "DIAMOND"))
    check("plural refused", !Unscramble.matches("DIAMONDS", "DIAMOND"))
    check("inner space refused", !Unscramble.matches("DIA MOND", "DIAMOND"))
    check("empty refused", !Unscramble.matches("", "DIAMOND"))
    check("every bundled answer is plain A-Z", bundled.words.all { w -> w.all { it in 'A'..'Z' } })

    // ── One game at a time, one winner, exactly one reward ──
    val server = Server()
    val game = Game(Type.UNSCRAMBLE, "DIAMOND")
    check("game starts", server.slot.tryStart(game))
    check("second game refused while one runs", !server.slot.tryStart(Game(Type.MATH, "5")))
    check("wrong answer doesn't win", !server.chat("Steve", "NODMIAD"))
    check("cancelled (muted / staff chat / team chat) answer doesn't win", !server.chat("Muted", "diamond", cancelled = true))
    check("game still running after wrong answers", server.slot.active() == game)
    check("correct answer wins", server.chat("Alex", " diamond "))
    check("second correct answer doesn't win again", !server.chat("Steve", "DIAMOND"))
    check("paid exactly once", server.payouts.get() == 1)
    check("timeout after a win does nothing", !server.slot.end(game))
    check("slot free after the win", server.slot.active() == null)

    // Simultaneous correct answers from the async chat threads.
    repeat(200) { round ->
        val s = Server()
        val g = Game(Type.UNSCRAMBLE, "NETHERITE")
        s.slot.tryStart(g)
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val go = CountDownLatch(1)
        repeat(threads) { i -> pool.execute { go.await(); s.chat("P$i", if (i % 2 == 0) "netherite" else "NETHERITE ") } }
        go.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        check("round $round: exactly one winner", s.winners.size == 1)
        check("round $round: exactly one payout", s.payouts.get() == 1)
    }

    // ── Timeout and cancellation ──
    val t = Server()
    val tg = Game(Type.UNSCRAMBLE, "BEACON")
    t.slot.tryStart(tg)
    check("timeout ends the round", t.slot.end(tg))
    check("answer after timeout doesn't win", !t.chat("Alex", "BEACON"))
    check("no payout after timeout", t.payouts.get() == 0)
    val cg = Game(Type.UNSCRAMBLE, "SHULKER")
    t.slot.tryStart(cg)
    check("cancel returns the running game", t.slot.cancel() == cg)
    check("answer after cancel doesn't win", !t.chat("Alex", "SHULKER"))
    check("stale timeout after cancel does nothing", !t.slot.end(cg))
    val next = Game(Type.MATH, "12")
    check("new game after cancel starts", t.slot.tryStart(next))
    check("old game's timeout doesn't end the new game", !t.slot.end(cg) && t.slot.active() == next)

    // ── Rotation ──
    val all = Type.entries.toList()
    var lastType: Type? = null
    val counts = HashMap<Type, Int>()
    repeat(6000) {
        val type = ChatGameRotation.choose(all, lastType, setOf(Type.UNSCRAMBLE), random)!!
        check("Unscramble never twice in a row while others exist", !(type == Type.UNSCRAMBLE && lastType == Type.UNSCRAMBLE))
        counts.merge(type, 1, Int::plus)
        lastType = type
    }
    check("every type is picked", all.all { (counts[it] ?: 0) > 500 })
    var mathRepeats = 0
    lastType = Type.MATH
    repeat(3000) {
        val type = ChatGameRotation.choose(all, lastType, setOf(Type.UNSCRAMBLE), random)!!
        if (type == Type.MATH && lastType == Type.MATH) mathRepeats++
        lastType = type
    }
    check("existing games keep their selection (Math may repeat)", mathRepeats > 0)
    val disabled = listOf(Type.MATH, Type.TYPE)
    repeat(500) {
        check("disabled Unscramble is never picked", ChatGameRotation.choose(disabled, null, setOf(Type.UNSCRAMBLE), random) != Type.UNSCRAMBLE)
    }
    check("only Unscramble available may repeat", ChatGameRotation.choose(listOf(Type.UNSCRAMBLE), Type.UNSCRAMBLE, setOf(Type.UNSCRAMBLE), random) == Type.UNSCRAMBLE)
    check("nothing available picks nothing", ChatGameRotation.choose(emptyList<Type>(), null, emptySet(), random) == null)

    // ── Survival / Lifesteal isolation: each server has its own slot ──
    val survival = Server()
    val lifesteal = Server()
    survival.slot.tryStart(Game(Type.UNSCRAMBLE, "COPPER"))
    lifesteal.slot.tryStart(Game(Type.UNSCRAMBLE, "COPPER"))
    check("survival winner", survival.chat("Alex", "copper"))
    check("lifesteal round unaffected by the survival win", lifesteal.slot.active() != null && lifesteal.payouts.get() == 0)
    check("lifesteal winner independent", lifesteal.chat("Steve", "copper"))

    println("Chat game checks passed ($checks checks).")
}
