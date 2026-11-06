package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.chatgame.ChatGameRotation
import com.liam.joshymc.chatgame.ChatGameSlot
import com.liam.joshymc.chatgame.Unscramble
import com.liam.joshymc.util.ProfanityFilter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.concurrent.ThreadLocalRandom

/**
 * Server-wide chat games. Posts a random challenge to chat on a timer; the
 * first player whose chat message matches the answer wins Credits. Also
 * exposes [startGame] / [startRandomGame] for the `/chatgame` admin command.
 *
 * Thread model: the active game is held in a [ChatGameSlot] (one atomic
 * reference) because [handleChat] runs on the AsyncChatEvent thread while the
 * scheduler ticks the auto-start + auto-expire on the main thread.
 *
 * Unscramble (issue #1395) shows a scrambled word from
 * `chat-games.unscramble.words`; its rules are the Bukkit-free [Unscramble].
 */
class ChatGamesManager(private val plugin: Joshymc) {

    enum class GameType(val displayName: String) {
        MATH("Math"),
        TYPE("Type"),
        UNSCRAMBLE("Unscramble"),
    }

    data class ActiveGame(
        val type: GameType,
        val prompt: String,
        val answer: String,
        val startedAt: Long,
    )

    private val current = ChatGameSlot<ActiveGame>()

    private var enabled = true
    private var intervalSeconds = 900L
    private var minPlayers = 2
    private var solveWindowSeconds = 60L
    private var rewardCredits = 3.0

    private var lastTypePrompt: String? = null
    private var lastType: GameType? = null

    // Unscramble settings, read on the main thread in start(). The pool is an
    // immutable list swapped in whole.
    @Volatile private var unscrambleEnabled = true
    @Volatile private var unscrambleWords: List<String> = emptyList()
    private var unscrambleStartMessage = ""
    private var unscrambleWinMessage = ""
    private var unscrambleTimeoutMessage = ""
    private var lastUnscrambleWord: String? = null

    private var autoStartTaskId = -1

    // ── Lifecycle ───────────────────────────────────────────────────────

    fun start() {
        val cfg = plugin.config
        enabled = cfg.getBoolean("chat-games.enabled", true)
        if (!enabled) {
            plugin.logger.info("[ChatGames] Disabled in config.")
            return
        }

        // New `interval-seconds` field, with legacy `interval-minutes` kept
        // working for older configs. Floor at 30s so the auto-scheduler can't
        // be misconfigured into spam.
        intervalSeconds = if (cfg.contains("chat-games.interval-seconds")) {
            cfg.getLong("chat-games.interval-seconds", 900L)
        } else {
            cfg.getLong("chat-games.interval-minutes", 15L) * 60L
        }.coerceAtLeast(30L)

        minPlayers = cfg.getInt("chat-games.min-players", 2).coerceAtLeast(1)
        solveWindowSeconds = cfg.getLong("chat-games.solve-window-seconds", 60L).coerceAtLeast(10L)
        rewardCredits = cfg.getDouble("chat-games.reward.credits", 3.0)
        loadUnscramble()

        // Auto-start a game every interval, if no game is currently running and
        // there are enough players online to make it interesting.
        val periodTicks = intervalSeconds * 20L
        autoStartTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            if (current.active() != null) return@Runnable
            if (Bukkit.getOnlinePlayers().size < minPlayers) return@Runnable
            startRandomGame()
        }, periodTicks, periodTicks)

        plugin.logger.info("[ChatGames] Started — interval=${intervalSeconds}s, reward=${plugin.creditsManager.format(rewardCredits)} Credits, unscramble words=${unscrambleWords.size}.")
    }

    fun stop() {
        if (autoStartTaskId != -1) {
            plugin.server.scheduler.cancelTask(autoStartTaskId)
            autoStartTaskId = -1
        }
        current.cancel()
    }

    private fun loadUnscramble() {
        val cfg = plugin.config
        unscrambleEnabled = cfg.getBoolean("chat-games.unscramble.enabled", true)
        val entries: List<Any?> = if (cfg.isList("chat-games.unscramble.words")) {
            cfg.getList("chat-games.unscramble.words").orEmpty()
        } else {
            Unscramble.DEFAULT_WORDS
        }
        val pool = Unscramble.parsePool(entries) { ProfanityFilter.contains(it) }
        pool.problems.forEach { plugin.logger.warning("[ChatGames] Unscramble word $it.") }
        unscrambleWords = pool.words
        unscrambleStartMessage = cfg.getString("chat-games.unscramble.messages.start", "").orEmpty()
        unscrambleWinMessage = cfg.getString("chat-games.unscramble.messages.win", "").orEmpty()
        unscrambleTimeoutMessage = cfg.getString("chat-games.unscramble.messages.timeout", "").orEmpty()
        if (unscrambleEnabled && unscrambleWords.isEmpty()) {
            plugin.logger.warning("[ChatGames] Unscramble has no valid words; it won't run.")
        }
    }

    /** Whether [type] can run (Unscramble must be enabled and have valid words). */
    fun isAvailable(type: GameType): Boolean = when (type) {
        GameType.UNSCRAMBLE -> unscrambleEnabled && unscrambleWords.isNotEmpty()
        else -> true
    }

    // ── Game management ─────────────────────────────────────────────────

    /** Try to start a game. Returns true if one started, false if one is already running. */
    fun startRandomGame(): Boolean {
        if (current.active() != null) return false
        val type = ChatGameRotation.choose(
            GameType.entries.filter { isAvailable(it) },
            lastType,
            setOf(GameType.UNSCRAMBLE),
            ThreadLocalRandom.current(),
        ) ?: return false
        return startGame(type)
    }

    fun startGame(type: GameType): Boolean {
        if (!isAvailable(type)) return false
        if (current.active() != null) return false
        val (prompt, answer) = generate(type) ?: return false
        val game = ActiveGame(type, prompt, answer, System.currentTimeMillis())
        if (!current.tryStart(game)) return false
        lastType = type
        if (type == GameType.UNSCRAMBLE) lastUnscrambleWord = answer

        announceStart(game)

        // Auto-expire if no one solves it.
        val expireToken = game
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            // Only expire if the game we started is still the active one — a
            // winner may have already cleared the slot.
            if (current.end(expireToken)) {
                if (game.type == GameType.UNSCRAMBLE && unscrambleTimeoutMessage.isNotBlank()) {
                    Bukkit.broadcast(template(unscrambleTimeoutMessage, game, null, 0L))
                } else {
                    Bukkit.broadcast(
                        Component.text("⏱ ", ChatGameStartAnnouncement.ACCENT)
                            .append(Component.text("Chat game expired. ", ChatGameStartAnnouncement.TEXT))
                            .append(Component.text("Answer was: ", ChatGameStartAnnouncement.TEXT))
                            .append(Component.text(game.answer, ChatGameStartAnnouncement.CHALLENGE).decoration(TextDecoration.BOLD, true))
                    )
                }
            }
        }, solveWindowSeconds * 20L)
        return true
    }

    fun activeGame(): ActiveGame? = current.active()

    /**
     * Hook called from the chat listener. Returns true if the message was the
     * correct answer (the caller may want to suppress the message; we don't
     * cancel the chat event by default since social chat about wins is fine).
     */
    fun handleChat(player: Player, message: String): Boolean {
        if (!enabled) return false
        val game = current.active() ?: return false
        if (!Unscramble.matches(message, game.answer)) return false

        // Race to claim the win — only the first thread to clear the slot
        // gets to award the prize.
        if (!current.end(game)) return false
        val elapsedMillis = System.currentTimeMillis() - game.startedAt

        // Award + announce on the main thread so we touch the Credits /
        // player APIs from the right thread.
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (rewardCredits > 0.0) payReward(player, game)

            if (game.type == GameType.UNSCRAMBLE && unscrambleWinMessage.isNotBlank()) {
                Bukkit.broadcast(template(unscrambleWinMessage, game, player.name, elapsedMillis))
            } else {
                Bukkit.broadcast(
                    ChatGameWinnerAnnouncement.build(
                        player.name,
                        elapsedMillis,
                        "${plugin.creditsManager.format(rewardCredits)} Credits",
                    )
                )
            }
            for (online in Bukkit.getOnlinePlayers()) {
                online.playSound(online.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.15f, 1.4f)
            }
        })
        return true
    }

    /** Pays the win once; a failed deposit is logged and the winner told, never retried twice. */
    private fun payReward(player: Player, game: ActiveGame) {
        val paid = try {
            plugin.creditsManager.deposit(player.uniqueId, rewardCredits)
        } catch (e: Exception) {
            false // CreditsManager already logged the database error
        }
        if (paid) return
        plugin.logger.warning("[ChatGames] Could not pay ${player.name} (${player.uniqueId}) $rewardCredits Credits for a ${game.type.displayName} win.")
        if (player.isOnline) {
            plugin.commsManager.send(player, Component.text("Your chat game reward couldn't be paid. Please tell staff.", NamedTextColor.RED))
        }
    }

    // ── Announcement ────────────────────────────────────────────────────

    private fun announceStart(game: ActiveGame) {
        if (game.type == GameType.UNSCRAMBLE && unscrambleStartMessage.isNotBlank()) {
            Bukkit.broadcast(template(unscrambleStartMessage, game, null, 0L))
        } else {
            Bukkit.broadcast(
                ChatGameStartAnnouncement.build(
                    game.type,
                    game.prompt,
                    "${plugin.creditsManager.format(rewardCredits)} Credits",
                    solveWindowSeconds,
                )
            )
        }

        // Bell "ting" so players notice even if they're not looking at chat.
        for (online in Bukkit.getOnlinePlayers()) {
            online.playSound(online.location, Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.5f)
        }
    }

    /**
     * A custom Unscramble message from config. `&` codes are parsed first and
     * the placeholders are replaced afterwards with literal text, so a player
     * name can never carry formatting. A literal `\n` in the string starts a
     * new line.
     */
    private fun template(raw: String, game: ActiveGame, winner: String?, elapsedMillis: Long): Component {
        val values = mapOf(
            "{scrambled}" to game.prompt,
            "{answer}" to game.answer,
            "{player}" to (winner ?: ""),
            "{time}" to ChatGameWinnerAnnouncement.formatSeconds(elapsedMillis),
            "{reward}" to "${plugin.creditsManager.format(rewardCredits)} Credits",
            "{seconds}" to solveWindowSeconds.toString(),
        )
        var component = plugin.commsManager.parseLegacy(raw.replace("\\n", "\n"))
        for ((key, value) in values) {
            component = component.replaceText(
                TextReplacementConfig.builder().matchLiteral(key).replacement(value).build()
            )
        }
        return component
    }

    // ── Game generation ─────────────────────────────────────────────────

    /** Prompt + answer for [type], or null when no Unscramble round could be made. */
    private fun generate(type: GameType): Pair<String, String>? {
        val r = ThreadLocalRandom.current()
        return when (type) {
            GameType.MATH -> {
                val op = listOf("+", "-", "×").random()
                val (a, b) = when (op) {
                    "+" -> r.nextInt(10, 1000) to r.nextInt(10, 1000)
                    "-" -> {
                        // Prefer non-negative answers — pick b <= a.
                        val x = r.nextInt(10, 1000)
                        x to r.nextInt(10, x + 1)
                    }
                    else -> r.nextInt(12, 100) to r.nextInt(6, 25)
                }
                val answer = when (op) {
                    "+" -> a + b
                    "-" -> a - b
                    else -> a * b
                }
                "Solve: $a $op $b" to answer.toString()
            }
            GameType.TYPE -> {
                val prompt = TYPE_PROMPTS.filter { it != lastTypePrompt }.random()
                lastTypePrompt = prompt
                prompt to prompt
            }
            GameType.UNSCRAMBLE -> {
                val round = Unscramble.pick(unscrambleWords, lastUnscrambleWord, r) {
                    ProfanityFilter.contains(it)
                } ?: return null
                round.scrambled to round.answer
            }
        }
    }

    companion object {
        // FINAL approved Type-game prompt pool (issue #546) — exact strings
        // only, no normalization. Capitalization/spacing/numbers must be
        // preserved verbatim between the displayed prompt and expected answer.
        private val TYPE_PROMPTS = listOf(
            "JoshyMC is the BEST",
            "I Dropped a Log",
            "tbjoshy is GOATED",
            "BALRIGHT",
            "Mike Ox is Short",
            "Cookies Are Yummy",
            "Banana Man",
            "Shark doo doo doo",
            "12059",
            "CREEPER AW MAN",
            "THREE NETHERITE INGOTS",
            "LA PEACE",
            "DIAMONDS",
            "EMERALDS",
            "What the Skibid",
            "Six Sayven",
            "Blue 42",
        )
    }
}
