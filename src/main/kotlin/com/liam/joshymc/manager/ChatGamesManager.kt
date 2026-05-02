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
 * first player whose chat message matches the answer wins money + XP. Also
 * exposes [startGame] / [startRandomGame] for the `/chatgame` admin command.
 *
 * Thread model: the active game is held in an [AtomicReference] because
 * [handleChat] runs on the AsyncChatEvent thread while the scheduler ticks
 * the auto-start + auto-expire on the main thread.
 */
class ChatGamesManager(private val plugin: Joshymc) {

    enum class GameType(val displayName: String) {
        MATH("Math"),
        UNSCRAMBLE("Unscramble"),
        TYPE("Type"),
        REVERSE("Reverse"),
    }

    data class ActiveGame(
        val type: GameType,
        val prompt: String,
        val answer: String,
        val startedAt: Long,
    )

    private val current = AtomicReference<ActiveGame?>(null)

    private var enabled = true
    private var intervalMinutes = 15
    private var minPlayers = 2
    private var solveWindowSeconds = 60L
    private var rewardMoney = 1000.0
    private var rewardXp = 25

    private var autoStartTaskId = -1

    // ── Lifecycle ───────────────────────────────────────────────────────

    fun start() {
        val cfg = plugin.config
        enabled = cfg.getBoolean("chat-games.enabled", true)
        if (!enabled) {
            plugin.logger.info("[ChatGames] Disabled in config.")
            return
        }

        intervalMinutes = cfg.getInt("chat-games.interval-minutes", 15).coerceAtLeast(1)
        minPlayers = cfg.getInt("chat-games.min-players", 2).coerceAtLeast(1)
        solveWindowSeconds = cfg.getLong("chat-games.solve-window-seconds", 60L).coerceAtLeast(10L)
        rewardMoney = cfg.getDouble("chat-games.reward.money", 1000.0)
        rewardXp = cfg.getInt("chat-games.reward.xp", 25)

        // Auto-start a game every interval, if no game is currently running and
        // there are enough players online to make it interesting.
        val period = intervalMinutes * 60 * 20L
        autoStartTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            if (current.get() != null) return@Runnable
            if (Bukkit.getOnlinePlayers().size < minPlayers) return@Runnable
            startRandomGame()
        }, period, period)

        plugin.logger.info("[ChatGames] Started — interval=${intervalMinutes}m, reward=\$${rewardMoney.toInt()} + ${rewardXp}XP.")
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

        // Award + announce on the main thread so we touch the economy /
        // player APIs from the right thread.
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (rewardMoney > 0.0) plugin.economyManager.deposit(player.uniqueId, rewardMoney)
            if (rewardXp > 0) player.giveExp(rewardXp)

            Bukkit.broadcast(
                Component.text("✦ ", TextColor.color(0xFFD700)).decoration(TextDecoration.BOLD, true)
                    .append(Component.text(player.name, NamedTextColor.GREEN))
                    .append(Component.text(" won the chat game! ", NamedTextColor.YELLOW))
                    .append(Component.text("(+\$${rewardMoney.toInt()}, +${rewardXp} XP)", NamedTextColor.GRAY))
            )
            for (online in Bukkit.getOnlinePlayers()) {
                online.playSound(online.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.4f)
            }
        })
        return true
    }

    // ── Announcement ────────────────────────────────────────────────────

    private fun announceStart(game: ActiveGame) {
        Bukkit.broadcast(
            Component.text("🎲 ", TextColor.color(0xFFD700)).decoration(TextDecoration.BOLD, true)
                .append(Component.text("Chat Game ", TextColor.color(0xFFD700)).decoration(TextDecoration.BOLD, true))
                .append(Component.text("(${game.type.displayName}): ", NamedTextColor.GRAY))
                .append(Component.text(game.prompt, NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true))
        )
        Bukkit.broadcast(
            Component.text("First to answer in chat wins ", NamedTextColor.GRAY)
                .append(Component.text("\$${rewardMoney.toInt()}", NamedTextColor.GOLD))
                .append(Component.text(" + ", NamedTextColor.GRAY))
                .append(Component.text("${rewardXp} XP", NamedTextColor.GREEN))
                .append(Component.text("!", NamedTextColor.GRAY))
        )
    }

    // ── Game generation ─────────────────────────────────────────────────

    private fun generate(type: GameType): Pair<String, String> {
        val r = ThreadLocalRandom.current()
        return when (type) {
            GameType.MATH -> {
                val a = r.nextInt(2, 100)
                val b = r.nextInt(2, 25)
                val op = listOf("+", "-", "×").random()
                val answer = when (op) {
                    "+" -> a + b
                    "-" -> a - b
                    "×" -> a * b
                    else -> 0
                }
                "Solve: $a $op $b" to answer.toString()
            }
            GameType.UNSCRAMBLE -> {
                val word = WORDS.random()
                val scrambled = word.toCharArray().toMutableList()
                    .also { it.shuffle() }.joinToString("")
                // Re-roll if shuffle didn't actually scramble it (single-letter
                // words / unlucky shuffle landed on the original).
                val finalScrambled = if (scrambled == word) {
                    word.reversed()
                } else scrambled
                "Unscramble \"$finalScrambled\"" to word
            }
            GameType.TYPE -> {
                val len = r.nextInt(8, 13)
                val token = (1..len)
                    .map { ALPHA_NUM[r.nextInt(ALPHA_NUM.length)] }
                    .joinToString("")
                "First to type \"$token\"" to token
            }
            GameType.REVERSE -> {
                val word = WORDS.random()
                "Type \"$word\" backwards" to word.reversed()
            }
        }
    }

    companion object {
        private const val ALPHA_NUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val WORDS = listOf(
            "diamond", "creeper", "redstone", "minecraft", "village",
            "nether", "endstone", "obsidian", "trident", "anvil",
            "potion", "armor", "elytra", "shulker", "warden",
            "phantom", "guardian", "blaze", "magma", "wither",
            "skeleton", "spider", "zombie", "enderman", "ghast",
            "stronghold", "fortress", "monument", "outpost", "mansion",
            "beacon", "conduit", "lantern", "torch", "campfire",
            "pumpkin", "carrot", "potato", "beetroot", "wheat",
            "salmon", "cod", "tropical", "puffer", "axolotl",
            "frog", "tadpole", "allay", "vex", "evoker",
        )
    }
}
