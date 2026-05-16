package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.pow

class ResurgeManager(private val plugin: Joshymc) : Listener {

    companion object {
        private const val RESURGE_REWARD_MONEY = 10_000_000.0
        private const val SKILL_LEVEL_INCREMENT = 5
        private const val DIFFICULTY_MULTIPLIER = 1.2
        private val REQUIRED_QUEST_CATEGORIES = listOf(
            QuestCategory.MINING,
            QuestCategory.FISHING,
            QuestCategory.FARMING
        )
    }

    val resurgeKeyKey = NamespacedKey(plugin, "resurge_key")
    private val cache = ConcurrentHashMap<UUID, Int>()

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_resurge (
                uuid TEXT PRIMARY KEY,
                count INTEGER DEFAULT 0
            )
        """.trimIndent())

        Bukkit.getOnlinePlayers().forEach { loadPlayer(it.uniqueId) }
        plugin.logger.info("[Resurge] Resurge manager started.")
    }

    // ── Cache management ────────────────────────────────────────────────

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = loadPlayer(event.player.uniqueId)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = cache.remove(event.player.uniqueId)

    private fun loadPlayer(uuid: UUID) {
        cache[uuid] = loadCount(uuid)
    }

    private fun loadCount(uuid: UUID): Int =
        plugin.databaseManager.queryFirst(
            "SELECT count FROM player_resurge WHERE uuid = ?",
            uuid.toString()
        ) { rs -> rs.getInt("count") } ?: 0

    // ── Data access ─────────────────────────────────────────────────────

    fun getCount(uuid: UUID): Int = cache.getOrElse(uuid) { loadCount(uuid) }

    /** Returns the difficulty multiplier: 1.2^resurgeCount */
    fun getMultiplier(uuid: UUID): Double = DIFFICULTY_MULTIPLIER.pow(getCount(uuid).toDouble())

    /** Scales a base quest amount by the player's resurge multiplier. */
    fun getEffectiveAmount(uuid: UUID, baseAmount: Int): Int =
        ceil(baseAmount * getMultiplier(uuid)).toInt()

    /** The minimum skill level required for the player's next resurge. */
    fun getRequiredSkillLevel(uuid: UUID): Int = (getCount(uuid) + 1) * SKILL_LEVEL_INCREMENT

    // ── Eligibility check ───────────────────────────────────────────────

    fun canResurge(uuid: UUID): Boolean {
        val requiredSkillLevel = getRequiredSkillLevel(uuid)
        for (skill in SkillManager.Skill.entries) {
            if (plugin.skillManager.getLevel(uuid, skill) < requiredSkillLevel) return false
        }
        for (category in REQUIRED_QUEST_CATEGORIES) {
            val categoryQuests = plugin.questManager.getQuestsByCategory(category)
            if (categoryQuests.isEmpty()) continue
            if (categoryQuests.any { !plugin.questManager.isCompleted(uuid, it.id) }) return false
        }
        return true
    }

    /** Returns a list of human-readable strings describing unmet requirements. */
    fun getMissingRequirements(uuid: UUID): List<String> {
        val missing = mutableListOf<String>()
        val requiredSkillLevel = getRequiredSkillLevel(uuid)

        for (skill in SkillManager.Skill.entries) {
            val level = plugin.skillManager.getLevel(uuid, skill)
            if (level < requiredSkillLevel) {
                missing.add("${skill.displayName} skill: level $level / $requiredSkillLevel")
            }
        }

        for (category in REQUIRED_QUEST_CATEGORIES) {
            val categoryQuests = plugin.questManager.getQuestsByCategory(category)
            val incomplete = categoryQuests.count { !plugin.questManager.isCompleted(uuid, it.id) }
            if (incomplete > 0) {
                missing.add("${category.displayName} quests: $incomplete remaining")
            }
        }

        return missing
    }

    // ── Perform resurge ─────────────────────────────────────────────────

    fun resurge(player: Player): Boolean {
        val uuid = player.uniqueId
        if (!canResurge(uuid)) return false

        val newCount = getCount(uuid) + 1

        // Reset quests and skills
        plugin.questManager.resetAllProgress(uuid)
        plugin.skillManager.resetSkills(uuid)

        // Persist new count
        cache[uuid] = newCount
        plugin.databaseManager.execute(
            "INSERT INTO player_resurge (uuid, count) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET count = ?",
            uuid.toString(), newCount, newCount
        )

        // Give $10M
        plugin.economyManager.deposit(uuid, RESURGE_REWARD_MONEY)

        // Give Resurge Key(s) — 2 on milestone resurges (5, 10, 15, ...)
        val keyCount = if (newCount % 5 == 0) 2 else 1
        val keyStack = createResurgeKey(keyCount)
        val overflow = player.inventory.addItem(keyStack)
        overflow.values.forEach { stack -> player.world.dropItemNaturally(player.location, stack) }

        // Notify
        val title = Title.title(
            Component.text("RESURGE ${newCount}!", TextColor.color(0xFFAA00))
                .decoration(TextDecoration.BOLD, true),
            Component.text("Quests and skills have been reset.", NamedTextColor.GRAY),
            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(1000))
        )
        player.showTitle(title)
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)

        plugin.commsManager.send(
            player,
            Component.text("You have Resurged! (Resurge $newCount) ", TextColor.color(0xFFAA00))
                .append(Component.text("+${formatMoney(RESURGE_REWARD_MONEY)}", NamedTextColor.GOLD))
                .append(Component.text(" & ${keyCount}x Resurge Key${if (keyCount > 1) "s" else ""}!", NamedTextColor.YELLOW))
        )

        // Broadcast to server
        Bukkit.broadcast(
            plugin.commsManager.parseLegacy(
                "&6[Resurge] &e${player.name} &7has reached &6Resurge $newCount&7!"
            )
        )

        return true
    }

    // ── Item creation ───────────────────────────────────────────────────

    fun createResurgeKey(amount: Int = 1): ItemStack {
        val item = ItemStack(Material.TRIPWIRE_HOOK, amount.coerceAtLeast(1))
        item.editMeta { meta ->
            meta.displayName(
                Component.text("Resurge Key", TextColor.color(0xFFAA00))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Use at a Resurge Crate!", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ))
            meta.persistentDataContainer.set(resurgeKeyKey, PersistentDataType.STRING, "resurge")
        }
        return item
    }

    fun isResurgeKey(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) return false
        return item.itemMeta?.persistentDataContainer?.has(resurgeKeyKey, PersistentDataType.STRING) == true
    }

    // ── Leaderboard ─────────────────────────────────────────────────────

    fun getTopPlayers(limit: Int = 10): List<Pair<String, Int>> {
        return plugin.databaseManager.query(
            "SELECT uuid, count FROM player_resurge WHERE count > 0 ORDER BY count DESC LIMIT ?",
            limit
        ) { rs ->
            val name = Bukkit.getOfflinePlayer(UUID.fromString(rs.getString("uuid"))).name
                ?: rs.getString("uuid").take(8)
            Pair(name, rs.getInt("count"))
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────

    private fun formatMoney(amount: Double): String {
        return when {
            amount >= 1_000_000 -> "$${(amount / 1_000_000).toInt()}M"
            amount >= 1_000 -> "$${(amount / 1_000).toInt()}K"
            else -> "$${amount.toLong()}"
        }
    }
}
