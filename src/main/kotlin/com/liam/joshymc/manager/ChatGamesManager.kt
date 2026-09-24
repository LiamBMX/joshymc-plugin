package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Server-wide chat games. Posts a random challenge to chat on a timer; the
 * first player whose chat message matches the answer wins Credits. Also
 * exposes [startGame] / [startRandomGame] for the `/chatgame` admin command.
 *
 * Thread model: the active game is held in an [AtomicReference] because
 * [handleChat] runs on the AsyncChatEvent thread while the scheduler ticks
 * the auto-start + auto-expire on the main thread.
 */
class ChatGamesManager(private val plugin: Joshymc) {

    enum class GameType(val displayName: String) {
        MATH("Math"),
        TYPE("Type"),
    }

    data class ActiveGame(
        val type: GameType,
        val prompt: String,
        val answer: String,
        val startedAt: Long,
    )

    private val current = AtomicReference<ActiveGame?>(null)

    private var enabled = true
    private var intervalSeconds = 900L
    private var minPlayers = 2
    private var solveWindowSeconds = 60L
    private var rewardCredits = 3.0

    private var lastTypePrompt: String? = null

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

        // Auto-start a game every interval, if no game is currently running and
        // there are enough players online to make it interesting.
        val periodTicks = intervalSeconds * 20L
        autoStartTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            if (current.get() != null) return@Runnable
            if (Bukkit.getOnlinePlayers().size < minPlayers) return@Runnable
            startRandomGame()
        }, periodTicks, periodTicks)

        plugin.logger.info("[ChatGames] Started — interval=${intervalSeconds}s, reward=${plugin.creditsManager.format(rewardCredits)} Credits.")
    }

    fun stop() {
        if (autoStartTaskId != -1) {
            plugin.server.scheduler.cancelTask(autoStartTaskId)
            autoStartTaskId = -1
        }
        current.set(null)
    }

    // ── Game management ─────────────────────────────────────────────────

    /** Try to start a game. Returns true if one started, false if one is already running. */
    fun startRandomGame(): Boolean = startGame(GameType.entries.random())

    fun startGame(type: GameType): Boolean {
        val (prompt, answer) = generate(type)
        val game = ActiveGame(type, prompt, answer, System.currentTimeMillis())
        if (!current.compareAndSet(null, game)) return false

        announceStart(game)

        // Auto-expire if no one solves it.
        val expireToken = game
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            // Only expire if the game we started is still the active one — a
            // winner may have already cleared the slot.
            if (current.compareAndSet(expireToken, null)) {
                Bukkit.broadcast(
                    Component.text("⏱ ", NamedTextColor.YELLOW)
                        .append(Component.text("Chat game expired. ", NamedTextColor.GRAY))
                        .append(Component.text("Answer was: ", NamedTextColor.GRAY))
                        .append(Component.text(game.answer, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
                )
            }
        }, solveWindowSeconds * 20L)
        return true
    }

    fun activeGame(): ActiveGame? = current.get()

    /**
     * Hook called from the chat listener. Returns true if the message was the
     * correct answer (the caller may want to suppress the message; we don't
     * cancel the chat event by default since social chat about wins is fine).
     */
    fun handleChat(player: Player, message: String): Boolean {
        if (!enabled) return false
        val game = current.get() ?: return false
        if (!message.trim().equals(game.answer, ignoreCase = true)) return false

        // Race to claim the win — only the first thread to clear the slot
        // gets to award the prize.
        if (!current.compareAndSet(game, null)) return false

        // Award + announce on the main thread so we touch the Credits /
        // player APIs from the right thread.
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (rewardCredits > 0.0) plugin.creditsManager.deposit(player.uniqueId, rewardCredits)

            Bukkit.broadcast(
                Component.text("✦ ", TextColor.color(0xFFD700)).decoration(TextDecoration.BOLD, true)
                    .append(Component.text(player.name, NamedTextColor.GREEN))
                    .append(Component.text(" won the chat game and earned ", NamedTextColor.YELLOW))
                    .append(Component.text("${plugin.creditsManager.format(rewardCredits)} Credits", NamedTextColor.GOLD))
                    .append(Component.text("!", NamedTextColor.YELLOW))
            )
            for (online in Bukkit.getOnlinePlayers()) {
                online.playSound(online.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.4f)
            }
        })
        return true
    }

    // ── Announcement ────────────────────────────────────────────────────

    private fun announceStart(game: ActiveGame) {
        // Empty separator line above the announcement so it visually breaks
        // away from preceding chat. Same below.
        val rule = Component.text("━━━━━━━━━━━━━━━ ", TextColor.color(0xFFD700))
            .append(Component.text("✦ CHAT GAME ✦", TextColor.color(0xFFD700)).decoration(TextDecoration.BOLD, true))
            .append(Component.text(" ━━━━━━━━━━━━━━━", TextColor.color(0xFFD700)))
        val ruleBottom = Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", TextColor.color(0xFFD700))

        val typeLine = Component.text("  ", NamedTextColor.GRAY)
            .append(Component.text("[${game.type.displayName}]  ", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true))
            .append(Component.text(game.prompt, NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true))

        val rewardLine = Component.text("  First in chat wins ", NamedTextColor.GRAY)
            .append(Component.text("${plugin.creditsManager.format(rewardCredits)} Credits", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
            .append(Component.text("  ·  Time: ", NamedTextColor.DARK_GRAY))
            .append(Component.text("${solveWindowSeconds}s", NamedTextColor.GRAY))

        Bukkit.broadcast(Component.empty())
        Bukkit.broadcast(rule)
        Bukkit.broadcast(typeLine)
        Bukkit.broadcast(rewardLine)
        Bukkit.broadcast(ruleBottom)
        Bukkit.broadcast(Component.empty())

        // Bell "ting" so players notice even if they're not looking at chat.
        for (online in Bukkit.getOnlinePlayers()) {
            online.playSound(online.location, Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.5f)
        }
    }

    // ── Game generation ─────────────────────────────────────────────────

    private fun generate(type: GameType): Pair<String, String> {
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
