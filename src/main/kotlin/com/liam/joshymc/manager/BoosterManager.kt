package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

class BoosterManager(private val plugin: Joshymc) {

    enum class BoosterType(val displayName: String) {
        SELL("Sell"),
        SKILL("Skill XP"),
        QUEST("Quest")
    }

    enum class SellCategory(val displayName: String, val shopCategoryIds: List<String>) {
        CROPS("Crops", listOf("farming", "food")),
        ORES("Ores", listOf("ores")),
        ANIMAL("Animal", listOf("animal_products")),
        MOBS("Mobs", listOf("mob_drops"))
    }

    data class ActiveBooster(
        val type: BoosterType,
        val multiplier: Double,
        val endTimeMs: Long,
        val sellCategory: SellCategory? = null
    ) {
        fun isExpired() = System.currentTimeMillis() > endTimeMs
        fun remainingMs() = (endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private val boosters = ConcurrentHashMap<BoosterType, ActiveBooster>()

    fun activate(type: BoosterType, multiplier: Double, durationMs: Long, sellCategory: SellCategory? = null) {
        val endTime = System.currentTimeMillis() + durationMs
        val booster = ActiveBooster(type, multiplier, endTime, sellCategory)
        boosters[type] = booster

        val categoryText = if (type == BoosterType.SELL && sellCategory != null) " (${sellCategory.displayName})" else ""
        val durationText = formatDuration(durationMs)

        Bukkit.broadcast(
            plugin.commsManager.parseLegacy(
                "&6[Booster] &e${type.displayName}$categoryText &6${multiplier}x &7booster activated for &e$durationText&7!"
            )
        )
        Bukkit.getOnlinePlayers().forEach {
            it.playSound(it.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f)
        }

        val ticks = (durationMs / 50).coerceAtLeast(1L)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val current = boosters[type]
            if (current === booster) {
                boosters.remove(type)
                Bukkit.broadcast(
                    plugin.commsManager.parseLegacy(
                        "&6[Booster] &7The &e${type.displayName}$categoryText &7booster has expired."
                    )
                )
            }
        }, ticks)
    }

    fun getActiveBoosters(): List<ActiveBooster> = boosters.values.filter { !it.isExpired() }

    fun getSellMultiplier(material: Material): Double {
        val booster = boosters[BoosterType.SELL] ?: return 1.0
        if (booster.isExpired()) { boosters.remove(BoosterType.SELL); return 1.0 }
        val category = booster.sellCategory ?: return booster.multiplier
        val shopCategoryId = plugin.serverShopManager.getCategoryIdForMaterial(material) ?: return 1.0
        return if (shopCategoryId in category.shopCategoryIds) booster.multiplier else 1.0
    }

    fun getSkillMultiplier(): Double {
        val booster = boosters[BoosterType.SKILL] ?: return 1.0
        if (booster.isExpired()) { boosters.remove(BoosterType.SKILL); return 1.0 }
        return booster.multiplier
    }

    fun getQuestMultiplier(): Double {
        val booster = boosters[BoosterType.QUEST] ?: return 1.0
        if (booster.isExpired()) { boosters.remove(BoosterType.QUEST); return 1.0 }
        return booster.multiplier
    }

    /** Applies the quest booster to an effective quest amount, rounding up. */
    fun applyQuestBooster(effectiveAmount: Int): Int {
        val mult = getQuestMultiplier()
        if (mult <= 1.0) return effectiveAmount
        return ceil(effectiveAmount / mult).toInt().coerceAtLeast(1)
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return buildString {
            if (hours > 0) append("${hours}h")
            if (minutes > 0) append("${minutes}m")
            if (seconds > 0 || (hours == 0L && minutes == 0L)) append("${seconds}s")
        }
    }
}
