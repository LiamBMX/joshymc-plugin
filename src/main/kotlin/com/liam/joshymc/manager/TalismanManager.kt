package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.enchantments.Enchantment
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ══════════════════════════════════════════════════════════
//  DATA TYPES
// ══════════════════════════════════════════════════════════

enum class TalismanRarity(val displayName: String, val color: String) {
    TALISMAN("Talisman", "&e"),
    RELIC("Relic", "&6&l")
}

data class TalismanDef(
    val id: String,
    val name: String,
    val description: String,
    val rarity: TalismanRarity,
    val icon: Material,
    val effects: List<TalismanEffect>,
    val unique: Boolean = false,
    val permission: String? = null  // null = admin grant required
)

sealed class TalismanEffect {
    data class DamageBonus(val percent: Double, val mobsOnly: Boolean = false, val pvpOnly: Boolean = false) : TalismanEffect()
    data class DamageReduction(val percent: Double) : TalismanEffect()
    data class DropMultiplier(val percent: Double, val blockTypes: Set<Material>? = null) : TalismanEffect()
    data class SellBonus(val percent: Double) : TalismanEffect()
    data class XpBonus(val percent: Double) : TalismanEffect()
    data class SpeedBonus(val percent: Double) : TalismanEffect()
    data class ExtraHearts(val hearts: Int) : TalismanEffect()
    data class PotionBuff(val effectType: PotionEffectType, val amplifier: Int) : TalismanEffect()
    data class FallDamageReduction(val maxBlocks: Int) : TalismanEffect()
    data class TransactionTax(val percent: Double) : TalismanEffect()
    data class AutoSmelt(val dummy: Boolean = true) : TalismanEffect()
    data class AutoReplant(val dummy: Boolean = true) : TalismanEffect()
    data class FishingSpeed(val percent: Double) : TalismanEffect()
    data class PotionDuration(val percent: Double) : TalismanEffect()
    data class FlightInClaims(val dummy: Boolean = true) : TalismanEffect()
    data class MiningSpeed(val percent: Double) : TalismanEffect()
    data class DoubleDropChance(val percent: Double) : TalismanEffect()
}

// ══════════════════════════════════════════════════════════
//  MANAGER
// ══════════════════════════════════════════════════════════

class TalismanManager(private val plugin: Joshymc) : Listener {

    companion object {
        private val CROP_MATERIALS = setOf(
            Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.NETHER_WART, Material.COCOA,
            Material.SWEET_BERRY_BUSH, Material.MELON, Material.PUMPKIN,
            Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO
        )

        private val ORE_MATERIALS = setOf(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE,
            Material.ANCIENT_DEBRIS
        )

        private val WOOD_MATERIALS = setOf(
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG,
            Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG,
            Material.MANGROVE_LOG, Material.CHERRY_LOG,
            Material.CRIMSON_STEM, Material.WARPED_STEM
        )

        private val REPLANTABLE_CROPS = mapOf(
            Material.WHEAT to Material.WHEAT_SEEDS,
            Material.CARROTS to Material.CARROT,
            Material.POTATOES to Material.POTATO,
            Material.BEETROOTS to Material.BEETROOT_SEEDS,
            Material.NETHER_WART to Material.NETHER_WART
        )

        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }

        private val HEALTH_MODIFIER_KEY = NamespacedKey("joshymc", "talisman_health")
        private val TALISMAN_PDC_KEY = NamespacedKey("joshymc", "talisman_item")
        const val TALISMAN_SLOT = 8  // Rightmost hotbar slot (slot 9 visually)
    }

    private val registry = mutableMapOf<String, TalismanDef>()
    private val playerCache = ConcurrentHashMap<UUID, String>() // uuid -> talisman_id
    private var effectTask: BukkitTask? = null
    private val petEntities = ConcurrentHashMap<UUID, UUID>() // player UUID -> armor stand entity UUID
    private var petTask: BukkitTask? = null

    // ══════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_talismans (
                uuid TEXT PRIMARY KEY,
                talisman_id TEXT NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS talisman_grants (
                uuid TEXT NOT NULL,
                talisman_id TEXT NOT NULL,
                PRIMARY KEY (uuid, talisman_id)
            )
        """.trimIndent())

        registerAll()
        loadCache()

        Bukkit.getPluginManager().registerEvents(this, plugin)

        effectTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { applyPeriodicEffects() }, 20L, 600L)
        petTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { updatePets() }, 5L, 5L)

        plugin.logger.info("[Talisman] Loaded ${registry.size} talismans (${getRelics().size} relics). ${playerCache.size} equipped.")
    }

    fun stop() {
        effectTask?.cancel()
        effectTask = null
        petTask?.cancel()
        petTask = null

        // Despawn all pets
        for (player in Bukkit.getOnlinePlayers()) {
            despawnPet(player)
        }
        petEntities.clear()

        // Remove health modifiers and flight from all online players
        for (player in Bukkit.getOnlinePlayers()) {
            removeHealthModifier(player)
            removeFlight(player)
        }

        playerCache.clear()
    }

    // ══════════════════════════════════════════════════════════
    //  REGISTRATION
    // ══════════════════════════════════════════════════════════

    private fun register(def: TalismanDef) {
        registry[def.id] = def
    }

    private fun registerAll() {
        registry.clear()

        // ── Talismans ───────────────────────────────────────
        // ── TALISMANS (buffed significantly) ─────────────
        register(TalismanDef(
            "farmers_talisman", "Farmer's Talisman",
            "+50% crop drops, auto-replant, Speed I",
            TalismanRarity.TALISMAN, Material.WHEAT,
            listOf(
                TalismanEffect.DropMultiplier(50.0, CROP_MATERIALS),
                TalismanEffect.AutoReplant(),
                TalismanEffect.SpeedBonus(10.0)
            )
        ))
        register(TalismanDef(
            "miners_talisman", "Miner's Talisman",
            "+40% ore drops, Haste II, auto-smelt",
            TalismanRarity.TALISMAN, Material.DIAMOND_PICKAXE,
            listOf(
                TalismanEffect.DropMultiplier(40.0, ORE_MATERIALS),
                TalismanEffect.PotionBuff(PotionEffectType.HASTE, 1),
                TalismanEffect.AutoSmelt()
            )
        ))
        register(TalismanDef(
            "slayers_talisman", "Slayer's Talisman",
            "+25% mob damage, +30% mob drops, Strength I",
            TalismanRarity.TALISMAN, Material.IRON_SWORD,
            listOf(
                TalismanEffect.DamageBonus(25.0, mobsOnly = true),
                TalismanEffect.DropMultiplier(30.0),
                TalismanEffect.PotionBuff(PotionEffectType.STRENGTH, 0)
            )
        ))
        register(TalismanDef(
            "warriors_talisman", "Warrior's Talisman",
            "+20% PvP damage, -15% damage taken, +2 hearts",
            TalismanRarity.TALISMAN, Material.DIAMOND_SWORD,
            listOf(
                TalismanEffect.DamageBonus(20.0, pvpOnly = true),
                TalismanEffect.DamageReduction(15.0),
                TalismanEffect.ExtraHearts(2)
            )
        ))
        register(TalismanDef(
            "anglers_talisman", "Angler's Talisman",
            "+50% fishing speed, +20% rare fish chance",
            TalismanRarity.TALISMAN, Material.FISHING_ROD,
            listOf(
                TalismanEffect.FishingSpeed(50.0),
                TalismanEffect.DoubleDropChance(20.0)
            )
        ))
        register(TalismanDef(
            "merchants_talisman", "Merchant's Talisman",
            "+25% sell prices",
            TalismanRarity.TALISMAN, Material.GOLD_INGOT,
            listOf(TalismanEffect.SellBonus(25.0))
        ))
        register(TalismanDef(
            "explorers_talisman", "Explorer's Talisman",
            "Speed II, no fall damage under 15 blocks, Jump Boost",
            TalismanRarity.TALISMAN, Material.COMPASS,
            listOf(
                TalismanEffect.SpeedBonus(30.0),
                TalismanEffect.FallDamageReduction(15),
                TalismanEffect.PotionBuff(PotionEffectType.JUMP_BOOST, 0)
            )
        ))
        register(TalismanDef(
            "lumberjacks_talisman", "Lumberjack's Talisman",
            "+50% wood drops, Haste I",
            TalismanRarity.TALISMAN, Material.IRON_AXE,
            listOf(
                TalismanEffect.DropMultiplier(50.0, WOOD_MATERIALS),
                TalismanEffect.PotionBuff(PotionEffectType.HASTE, 0)
            )
        ))
        register(TalismanDef(
            "guardians_talisman", "Guardian's Talisman",
            "-25% all damage, +4 hearts, Resistance I",
            TalismanRarity.TALISMAN, Material.SHIELD,
            listOf(
                TalismanEffect.DamageReduction(25.0),
                TalismanEffect.ExtraHearts(4),
                TalismanEffect.PotionBuff(PotionEffectType.RESISTANCE, 0)
            )
        ))
        register(TalismanDef(
            "xp_talisman", "XP Talisman",
            "+50% XP from all sources",
            TalismanRarity.TALISMAN, Material.EXPERIENCE_BOTTLE,
            listOf(TalismanEffect.XpBonus(50.0))
        ))
        register(TalismanDef(
            "lucky_talisman", "Lucky Talisman",
            "25% chance to double any drop",
            TalismanRarity.TALISMAN, Material.EMERALD,
            listOf(TalismanEffect.DoubleDropChance(25.0))
        ))
        register(TalismanDef(
            "alchemists_talisman", "Alchemist's Talisman",
            "Potions last 50% longer, Regen I",
            TalismanRarity.TALISMAN, Material.BREWING_STAND,
            listOf(
                TalismanEffect.PotionDuration(50.0),
                TalismanEffect.PotionBuff(PotionEffectType.REGENERATION, 0)
            )
        ))

        // ── RELICS (unique, game-changing) ──────────────────
        register(TalismanDef(
            "relic_harvest_king", "Relic of the Harvest King",
            "+100% crop drops, auto-replant, Haste III, Speed II",
            TalismanRarity.RELIC, Material.GOLDEN_HOE,
            listOf(
                TalismanEffect.DropMultiplier(100.0, CROP_MATERIALS),
                TalismanEffect.AutoReplant(),
                TalismanEffect.PotionBuff(PotionEffectType.HASTE, 2),
                TalismanEffect.SpeedBonus(30.0)
            ),
            unique = true
        ))
        register(TalismanDef(
            "relic_abyss", "Relic of the Abyss",
            "+50% mob damage, Night Vision, Strength II, lifesteal",
            TalismanRarity.RELIC, Material.WITHER_SKELETON_SKULL,
            listOf(
                TalismanEffect.DamageBonus(50.0, mobsOnly = true),
                TalismanEffect.PotionBuff(PotionEffectType.NIGHT_VISION, 0),
                TalismanEffect.PotionBuff(PotionEffectType.STRENGTH, 1),
                TalismanEffect.DoubleDropChance(25.0)
            ),
            unique = true
        ))
        register(TalismanDef(
            "relic_titan", "Relic of the Titan",
            "-35% all damage, +6 hearts, Resistance II",
            TalismanRarity.RELIC, Material.NETHERITE_CHESTPLATE,
            listOf(
                TalismanEffect.DamageReduction(35.0),
                TalismanEffect.ExtraHearts(6),
                TalismanEffect.PotionBuff(PotionEffectType.RESISTANCE, 1)
            ),
            unique = true
        ))
        register(TalismanDef(
            "relic_merchant", "Relic of the Merchant",
            "+40% sell prices, 2.5% tax on all transactions",
            TalismanRarity.RELIC, Material.GOLD_BLOCK,
            listOf(
                TalismanEffect.SellBonus(40.0),
                TalismanEffect.TransactionTax(2.5)
            ),
            unique = true
        ))
        register(TalismanDef(
            "relic_void", "Relic of the Void",
            "Flight in claims, +75% mining speed, auto-smelt, Haste III",
            TalismanRarity.RELIC, Material.ENDER_EYE,
            listOf(
                TalismanEffect.FlightInClaims(),
                TalismanEffect.MiningSpeed(75.0),
                TalismanEffect.AutoSmelt(),
                TalismanEffect.PotionBuff(PotionEffectType.HASTE, 2)
            ),
            unique = true
        ))
        register(TalismanDef(
            "relic_fortune", "Relic of Fortune",
            "50% double drops, +75% XP, +30% all drops",
            TalismanRarity.RELIC, Material.NETHER_STAR,
            listOf(
                TalismanEffect.DoubleDropChance(50.0),
                TalismanEffect.XpBonus(75.0),
                TalismanEffect.DropMultiplier(30.0)
            ),
            unique = true
        ))
    }

    private fun loadCache() {
        playerCache.clear()
        val rows = plugin.databaseManager.query(
            "SELECT uuid, talisman_id FROM player_talismans"
        ) { rs -> rs.getString("uuid") to rs.getString("talisman_id") }
        for ((uuid, id) in rows) {
            if (registry.containsKey(id)) {
                playerCache[UUID.fromString(uuid)] = id
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════

    fun getTalisman(id: String): TalismanDef? = registry[id]

    fun getAllTalismans(): List<TalismanDef> = registry.values.toList()

    fun getRelics(): List<TalismanDef> = registry.values.filter { it.unique }

    fun getPlayerTalisman(uuid: UUID): TalismanDef? {
        val id = playerCache[uuid] ?: return null
        return registry[id]
    }

    fun equipTalisman(player: Player, talismanId: String): Boolean {
        val def = registry[talismanId] ?: return false
        if (!canEquip(player, def) && !hasAccess(player.uniqueId, talismanId)) return false

        // Remove old effects first
        val oldDef = getPlayerTalisman(player.uniqueId)
        if (oldDef != null) {
            removeEffects(player, oldDef)
            removeTalismanFromSlot(player)
        }

        playerCache[player.uniqueId] = talismanId
        plugin.databaseManager.execute(
            "INSERT INTO player_talismans (uuid, talisman_id) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET talisman_id = ?",
            player.uniqueId.toString(), talismanId, talismanId
        )

        // Place physical item in slot 8 and apply effects
        placeTalismanInSlot(player, def)
        applyEffectsForPlayer(player, def)

        // Spawn pet for relics
        if (def.unique) {
            spawnPet(player, def)
        }

        return true
    }

    fun unequipTalisman(player: Player) {
        val def = getPlayerTalisman(player.uniqueId) ?: return
        despawnPet(player)
        removeEffects(player, def)
        removeTalismanFromSlot(player)
        playerCache.remove(player.uniqueId)
        plugin.databaseManager.execute(
            "DELETE FROM player_talismans WHERE uuid = ?",
            player.uniqueId.toString()
        )
    }

    fun getRelicOwner(talismanId: String): UUID? {
        val def = registry[talismanId] ?: return null
        if (!def.unique) return null
        return playerCache.entries.firstOrNull { it.value == talismanId }?.key
    }

    fun isRelicAvailable(talismanId: String): Boolean {
        return getRelicOwner(talismanId) == null
    }

    fun canEquip(player: Player, talisman: TalismanDef): Boolean {
        // Check permission — must have joshymc.talisman.<id> granted by admin
        if (!player.hasPermission("joshymc.talisman.${talisman.id}") && !player.hasPermission("joshymc.talisman.admin")) {
            return false
        }
        // Relics: only one owner
        if (talisman.unique) {
            val owner = getRelicOwner(talisman.id)
            return owner == null || owner == player.uniqueId
        }
        return true
    }

    /**
     * Grant a player permission to use a talisman.
     * Uses the DB table talisman_grants.
     */
    fun grantAccess(targetUuid: UUID, talismanId: String) {
        plugin.databaseManager.execute(
            "INSERT OR IGNORE INTO talisman_grants (uuid, talisman_id) VALUES (?, ?)",
            targetUuid.toString(), talismanId
        )
    }

    fun revokeAccess(targetUuid: UUID, talismanId: String) {
        plugin.databaseManager.execute(
            "DELETE FROM talisman_grants WHERE uuid = ? AND talisman_id = ?",
            targetUuid.toString(), talismanId
        )
    }

    fun hasAccess(uuid: UUID, talismanId: String): Boolean {
        return plugin.databaseManager.queryFirst(
            "SELECT 1 FROM talisman_grants WHERE uuid = ? AND talisman_id = ?",
            uuid.toString(), talismanId
        ) { true } ?: false
    }

    // ── Physical Item in Hotbar Slot 8 ──────────────────────

    fun buildTalismanItem(def: TalismanDef): ItemStack {
        val item = ItemStack(def.icon)
        item.editMeta { meta ->
            val colorCode = def.rarity.color
            meta.displayName(plugin.commsManager.parseLegacy("$colorCode${def.name}")
                .decoration(TextDecoration.ITALIC, false))

            val lore = mutableListOf<Component>()
            lore.add(Component.empty())
            lore.add(plugin.commsManager.parseLegacy("&7${def.description}").decoration(TextDecoration.ITALIC, false))
            lore.add(Component.empty())
            lore.add(plugin.commsManager.parseLegacy("&7Type: ${def.rarity.color}${def.rarity.displayName}").decoration(TextDecoration.ITALIC, false))
            lore.add(Component.empty())
            for (effect in def.effects) {
                lore.add(plugin.commsManager.parseLegacy("&8- &7${describeEffect(effect)}").decoration(TextDecoration.ITALIC, false))
            }
            lore.add(Component.empty())
            lore.add(plugin.commsManager.parseLegacy("&8Locked to hotbar slot 9").decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)

            meta.setEnchantmentGlintOverride(true)
            meta.persistentDataContainer.set(TALISMAN_PDC_KEY, PersistentDataType.STRING, def.id)
        }
        return item
    }

    private fun describeEffect(effect: TalismanEffect): String {
        return when (effect) {
            is TalismanEffect.DamageBonus -> "+${effect.percent.toInt()}% damage${if (effect.mobsOnly) " (mobs)" else if (effect.pvpOnly) " (PvP)" else ""}"
            is TalismanEffect.DamageReduction -> "-${effect.percent.toInt()}% damage taken"
            is TalismanEffect.DropMultiplier -> "+${effect.percent.toInt()}% drops"
            is TalismanEffect.SellBonus -> "+${effect.percent.toInt()}% sell prices"
            is TalismanEffect.XpBonus -> "+${effect.percent.toInt()}% XP"
            is TalismanEffect.SpeedBonus -> "+${effect.percent.toInt()}% speed"
            is TalismanEffect.ExtraHearts -> "+${effect.hearts} hearts"
            is TalismanEffect.PotionBuff -> "${effect.effectType.key.key} ${effect.amplifier + 1}"
            is TalismanEffect.FallDamageReduction -> "No fall damage under ${effect.maxBlocks} blocks"
            is TalismanEffect.TransactionTax -> "${effect.percent}% of all server transactions"
            is TalismanEffect.AutoSmelt -> "Auto-smelt ores"
            is TalismanEffect.AutoReplant -> "Auto-replant crops"
            is TalismanEffect.FishingSpeed -> "+${effect.percent.toInt()}% fishing speed"
            is TalismanEffect.PotionDuration -> "+${effect.percent.toInt()}% potion duration"
            is TalismanEffect.FlightInClaims -> "Flight in your claims"
            is TalismanEffect.MiningSpeed -> "+${effect.percent.toInt()}% mining speed"
            is TalismanEffect.DoubleDropChance -> "+${effect.percent.toInt()}% double drop chance"
        }
    }

    fun placeTalismanInSlot(player: Player, def: TalismanDef) {
        // Clear old talisman item from slot 8
        removeTalismanFromSlot(player)
        // Place new one
        player.inventory.setItem(TALISMAN_SLOT, buildTalismanItem(def))
    }

    fun removeTalismanFromSlot(player: Player) {
        val item = player.inventory.getItem(TALISMAN_SLOT)
        if (item != null && isTalismanItem(item)) {
            player.inventory.setItem(TALISMAN_SLOT, null)
        }
    }

    fun isTalismanItem(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) return false
        return item.itemMeta?.persistentDataContainer?.has(TALISMAN_PDC_KEY, PersistentDataType.STRING) == true
    }

    /**
     * Returns the sell price multiplier for a player (1.0 = no bonus).
     * Called by sell commands / shop system.
     */
    fun getSellMultiplier(player: Player): Double {
        val def = getPlayerTalisman(player.uniqueId) ?: return 1.0
        var bonus = 0.0
        for (effect in def.effects) {
            if (effect is TalismanEffect.SellBonus) {
                bonus += effect.percent
            }
        }
        return 1.0 + (bonus / 100.0)
    }

    /**
     * Called by the economy system on every transaction.
     * Finds the TransactionTax relic owner and deposits the tax.
     */
    fun applyTransactionTax(amount: Double) {
        for ((uuid, id) in playerCache) {
            val def = registry[id] ?: continue
            for (effect in def.effects) {
                if (effect is TalismanEffect.TransactionTax) {
                    val tax = amount * (effect.percent / 100.0)
                    if (tax > 0.01) {
                        plugin.economyManager.deposit(uuid, tax)
                    }
                }
            }
        }
    }

    /**
     * Check if a player has the AutoSmelt effect active.
     */
    fun hasAutoSmelt(player: Player): Boolean {
        val def = getPlayerTalisman(player.uniqueId) ?: return false
        return def.effects.any { it is TalismanEffect.AutoSmelt }
    }

    // ══════════════════════════════════════════════════════════
    //  RELIC PET SYSTEM
    // ══════════════════════════════════════════════════════════

    fun spawnPet(player: Player, def: TalismanDef) {
        if (!def.unique) return
        despawnPet(player)

        val loc = player.location.clone().add(1.0, 0.5, 1.0)

        // Use an Allay as the pet — they float, look magical, and are small
        val allay = player.world.spawn(loc, org.bukkit.entity.Allay::class.java) { mob ->
            mob.setAI(false)
            mob.isSilent = true
            mob.setGravity(false)
            mob.isInvulnerable = true
            mob.isCustomNameVisible = true
            mob.customName(plugin.commsManager.parseLegacy("${def.rarity.color}${def.name}")
                .decoration(TextDecoration.ITALIC, false))
            mob.isGlowing = true
            mob.isPersistent = false
            mob.canPickupItems = false
            mob.addScoreboardTag("joshymc_relic_pet")
            // Give it the relic icon item to hold
            mob.equipment.setItemInMainHand(ItemStack(def.icon))
        }

        // Set glow color via scoreboard team on all players' scoreboards
        val teamName = "pet_${player.name}".take(16)
        for (online in Bukkit.getOnlinePlayers()) {
            val board = online.scoreboard
            val team = board.getTeam(teamName) ?: board.registerNewTeam(teamName)
            team.color(NamedTextColor.GOLD)
            team.addEntity(allay)
        }

        petEntities[player.uniqueId] = allay.uniqueId
    }

    fun despawnPet(player: Player) {
        val entityUuid = petEntities.remove(player.uniqueId) ?: return

        for (world in Bukkit.getWorlds()) {
            val entity = world.getEntity(entityUuid)
            if (entity != null) {
                // Clean up scoreboard teams
                val teamName = "pet_${player.name}".take(16)
                for (online in Bukkit.getOnlinePlayers()) {
                    online.scoreboard.getTeam(teamName)?.unregister()
                }
                entity.remove()
                return
            }
        }
    }

    private fun updatePets() {
        val iterator = petEntities.entries.iterator()
        while (iterator.hasNext()) {
            val (playerUuid, entityUuid) = iterator.next()
            val player = Bukkit.getPlayer(playerUuid)
            if (player == null || !player.isOnline) {
                iterator.remove()
                continue
            }

            var armorStand: ArmorStand? = null
            for (world in Bukkit.getWorlds()) {
                val entity = world.getEntity(entityUuid)
                if (entity is ArmorStand) {
                    armorStand = entity
                    break
                }
            }

            // If pet is dead/removed, respawn it
            if (armorStand == null || armorStand.isDead) {
                val def = getPlayerTalisman(playerUuid)
                if (def != null && def.unique) {
                    spawnPet(player, def)
                } else {
                    iterator.remove()
                }
                continue
            }

            // Calculate orbiting position
            val time = System.currentTimeMillis() / 2000.0
            val angle = (time * Math.PI * 2) % (Math.PI * 2)
            val offsetX = Math.cos(angle) * 2.0
            val offsetZ = Math.sin(angle) * 2.0
            val petLoc = player.location.add(offsetX, 1.5, offsetZ)
            petLoc.yaw = ((angle * 180 / Math.PI) + 180).toFloat()

            // If too far away (e.g. after teleport), teleport directly
            val previousLoc = armorStand.location.clone()
            if (armorStand.world != player.world || armorStand.location.distanceSquared(player.location) > 400) {
                armorStand.teleport(petLoc)
            } else {
                armorStand.teleport(petLoc)
            }

            // Spawn trailing gold dust particles at previous position
            val dustOptions = Particle.DustOptions(Color.fromRGB(255, 215, 0), 0.8f)
            player.world.spawnParticle(Particle.DUST, previousLoc, 2, 0.1, 0.1, 0.1, 0.0, dustOptions)
        }
    }

    // ══════════════════════════════════════════════════════════
    //  PERIODIC EFFECT APPLICATION
    // ══════════════════════════════════════════════════════════

    private fun applyPeriodicEffects() {
        for (player in Bukkit.getOnlinePlayers()) {
            val def = getPlayerTalisman(player.uniqueId) ?: continue
            applyEffectsForPlayer(player, def)
        }
    }

    private fun applyEffectsForPlayer(player: Player, def: TalismanDef) {
        for (effect in def.effects) {
            when (effect) {
                is TalismanEffect.PotionBuff -> {
                    player.addPotionEffect(PotionEffect(
                        effect.effectType, 700, effect.amplifier, true, false, true
                    ))
                }
                is TalismanEffect.ExtraHearts -> {
                    applyHealthModifier(player, effect.hearts)
                }
                is TalismanEffect.SpeedBonus -> {
                    val base = 0.2f
                    val bonus = base * (effect.percent / 100.0).toFloat()
                    player.walkSpeed = (base + bonus).coerceAtMost(1.0f)
                }
                is TalismanEffect.MiningSpeed -> {
                    // Apply Haste based on mining speed percent
                    val amplifier = when {
                        effect.percent >= 50.0 -> 1
                        else -> 0
                    }
                    player.addPotionEffect(PotionEffect(
                        PotionEffectType.HASTE, 700, amplifier, true, false, true
                    ))
                }
                is TalismanEffect.FlightInClaims -> {
                    val claim = plugin.claimManager.getClaimAt(player.location)
                    val inOwnClaim = claim != null && claim.ownerUuid == player.uniqueId
                    if (inOwnClaim) {
                        if (!player.allowFlight) {
                            player.allowFlight = true
                        }
                    } else {
                        if (player.allowFlight && player.gameMode != org.bukkit.GameMode.CREATIVE && player.gameMode != org.bukkit.GameMode.SPECTATOR) {
                            player.allowFlight = false
                            player.isFlying = false
                        }
                    }
                }
                else -> { /* handled by event listeners */ }
            }
        }
    }

    private fun removeEffects(player: Player, def: TalismanDef) {
        for (effect in def.effects) {
            when (effect) {
                is TalismanEffect.PotionBuff -> player.removePotionEffect(effect.effectType)
                is TalismanEffect.ExtraHearts -> removeHealthModifier(player)
                is TalismanEffect.SpeedBonus -> player.walkSpeed = 0.2f
                is TalismanEffect.MiningSpeed -> player.removePotionEffect(PotionEffectType.HASTE)
                is TalismanEffect.FlightInClaims -> removeFlight(player)
                else -> { /* no persistent state to remove */ }
            }
        }
    }

    private fun applyHealthModifier(player: Player, hearts: Int) {
        val attr = player.getAttribute(Attribute.MAX_HEALTH) ?: return
        // Remove old modifier first
        attr.modifiers.filter { it.key == HEALTH_MODIFIER_KEY }.forEach { attr.removeModifier(it) }
        val modifier = AttributeModifier(HEALTH_MODIFIER_KEY, hearts * 2.0, AttributeModifier.Operation.ADD_NUMBER)
        attr.addModifier(modifier)
    }

    private fun removeHealthModifier(player: Player) {
        val attr = player.getAttribute(Attribute.MAX_HEALTH) ?: return
        attr.modifiers.filter { it.key == HEALTH_MODIFIER_KEY }.forEach { attr.removeModifier(it) }
    }

    private fun removeFlight(player: Player) {
        if (player.gameMode != org.bukkit.GameMode.CREATIVE && player.gameMode != org.bukkit.GameMode.SPECTATOR) {
            player.allowFlight = false
            player.isFlying = false
        }
    }

    // ══════════════════════════════════════════════════════════
    //  EVENT LISTENERS
    // ══════════════════════════════════════════════════════════

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val def = getPlayerTalisman(player.uniqueId) ?: return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            placeTalismanInSlot(player, def)
            applyEffectsForPlayer(player, def)
            if (def.unique) {
                spawnPet(player, def)
            }
        }, 5L)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        despawnPet(player)
        removeTalismanFromSlot(player)
        val def = getPlayerTalisman(player.uniqueId) ?: return
        removeEffects(player, def)
    }

    // ── Slot Locking — prevent moving/dropping the talisman item ─

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        // Block clicking the talisman slot
        if (event.slot == TALISMAN_SLOT && event.clickedInventory == player.inventory) {
            if (isTalismanItem(event.currentItem)) {
                event.isCancelled = true
            }
        }
        // Also block shift-clicking talisman items
        if (event.isShiftClick && isTalismanItem(event.currentItem)) {
            event.isCancelled = true
        }
        // Block placing items into slot 8 if a talisman is there
        if (event.slot == TALISMAN_SLOT && event.clickedInventory == player.inventory && isTalismanItem(player.inventory.getItem(TALISMAN_SLOT))) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrop(event: PlayerDropItemEvent) {
        if (isTalismanItem(event.itemDrop.itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onSwapHand(event: PlayerSwapHandItemsEvent) {
        if (isTalismanItem(event.mainHandItem) || isTalismanItem(event.offHandItem)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        // Don't drop the talisman item on death
        event.drops.removeIf { isTalismanItem(it) }
    }

    // ── Damage Bonus / Reduction ────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        // Attacker bonus
        val attacker = event.damager as? Player
        if (attacker != null) {
            val def = getPlayerTalisman(attacker.uniqueId)
            if (def != null) {
                for (effect in def.effects) {
                    if (effect is TalismanEffect.DamageBonus) {
                        val isPlayer = event.entity is Player
                        val isMob = event.entity is LivingEntity && event.entity !is Player
                        val applies = when {
                            effect.pvpOnly && !isPlayer -> false
                            effect.mobsOnly && !isMob -> false
                            else -> true
                        }
                        if (applies) {
                            event.damage *= 1.0 + (effect.percent / 100.0)
                        }
                    }
                }

                // Lifesteal for Relic of the Abyss (5% of damage on kill handled in death event is simpler;
                // but spec says "heal on kill" — we heal 5% of damage dealt on each hit for the abyss relic)
                if (def.id == "relic_abyss" && event.entity is LivingEntity && event.entity !is Player) {
                    val heal = event.finalDamage * 0.05
                    attacker.health = (attacker.health + heal).coerceAtMost(
                        attacker.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                    )
                }
            }
        }

        // Victim reduction
        val victim = event.entity as? Player
        if (victim != null) {
            val def = getPlayerTalisman(victim.uniqueId)
            if (def != null) {
                for (effect in def.effects) {
                    if (effect is TalismanEffect.DamageReduction) {
                        event.damage *= 1.0 - (effect.percent / 100.0)
                    }
                }
            }
        }
    }

    // ── Fall Damage Reduction ───────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFallDamage(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.FALL) return
        val player = event.entity as? Player ?: return
        val def = getPlayerTalisman(player.uniqueId) ?: return

        for (effect in def.effects) {
            if (effect is TalismanEffect.FallDamageReduction) {
                if (player.fallDistance <= effect.maxBlocks) {
                    event.isCancelled = true
                    return
                }
            }
        }
    }

    // ── Block Break — Auto-Replant + Drop Logic ─────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val def = getPlayerTalisman(player.uniqueId) ?: return
        val blockType = event.block.type

        for (effect in def.effects) {
            // Auto-replant crops
            if (effect is TalismanEffect.AutoReplant) {
                val seed = REPLANTABLE_CROPS[blockType]
                if (seed != null) {
                    val block = event.block
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        if (block.type == Material.AIR) {
                            block.type = blockType
                        }
                    }, 1L)
                }
            }
        }
    }

    // ── Block Drop Items — DropMultiplier + DoubleDropChance ─

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockDropItem(event: BlockDropItemEvent) {
        val player = event.player
        val def = getPlayerTalisman(player.uniqueId) ?: return
        val blockType = event.blockState.type

        val extraItems = mutableListOf<ItemStack>()

        for (effect in def.effects) {
            when (effect) {
                is TalismanEffect.DropMultiplier -> {
                    val matches = effect.blockTypes == null || effect.blockTypes.contains(blockType)
                    if (matches) {
                        // Chance-based: percent chance to add an extra copy of each drop
                        if (Math.random() * 100.0 < effect.percent) {
                            for (item in event.items) {
                                extraItems.add(item.itemStack.clone())
                            }
                        }
                    }
                }
                is TalismanEffect.DoubleDropChance -> {
                    if (Math.random() * 100.0 < effect.percent) {
                        for (item in event.items) {
                            extraItems.add(item.itemStack.clone())
                        }
                    }
                }
                else -> { }
            }
        }

        // Drop extra items at block location
        if (extraItems.isNotEmpty()) {
            val loc = event.block.location.add(0.5, 0.5, 0.5)
            for (stack in extraItems) {
                event.block.world.dropItemNaturally(loc, stack)
            }
        }
    }

    // ── XP Bonus ────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onExpChange(event: PlayerExpChangeEvent) {
        val def = getPlayerTalisman(event.player.uniqueId) ?: return
        var multiplier = 1.0
        for (effect in def.effects) {
            if (effect is TalismanEffect.XpBonus) {
                multiplier += effect.percent / 100.0
            }
        }
        if (multiplier > 1.0) {
            event.amount = (event.amount * multiplier).toInt().coerceAtLeast(event.amount)
        }
    }

    // ── Fishing Speed ───────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.FISHING) return
        val def = getPlayerTalisman(event.player.uniqueId) ?: return

        for (effect in def.effects) {
            if (effect is TalismanEffect.FishingSpeed) {
                val hook = event.hook
                // Reduce the min/max wait time to simulate faster fishing
                val reduction = (effect.percent / 100.0)
                val newMin = ((1 - reduction) * hook.minWaitTime).toInt().coerceAtLeast(20)
                val newMax = ((1 - reduction) * hook.maxWaitTime).toInt().coerceAtLeast(40)
                hook.minWaitTime = newMin
                hook.maxWaitTime = newMax
            }
        }
    }

    // ── Relic Death Drop ────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.player
        val def = getPlayerTalisman(player.uniqueId) ?: return

        if (!def.unique) return // Only relics drop on death

        unequipTalisman(player)

        val message = plugin.commsManager.parseLegacy(
            "&c&l${player.name} has lost the ${def.rarity.color}${def.name}&c&l! It is now unclaimed!"
        )
        Bukkit.getOnlinePlayers().forEach { it.sendMessage(message) }
    }

    // ══════════════════════════════════════════════════════════
    //  GUI
    // ══════════════════════════════════════════════════════════

    fun openMainMenu(player: Player) {
        val gui = CustomGui(plugin.commsManager.parseLegacy("&6&lTalismans & Relics"), 27)
        gui.fill(FILLER)

        val talismanIcon = ItemStack(Material.AMETHYST_SHARD).apply {
            editMeta { meta ->
                meta.displayName(Component.text("Talismans", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
                meta.lore(listOf(Component.empty(), Component.text("  Browse equippable talismans", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)))
                meta.setEnchantmentGlintOverride(true)
            }
        }
        gui.setItem(11, talismanIcon) { p, _ -> openTalismanGui(p) }

        val relicIcon = ItemStack(Material.NETHER_STAR).apply {
            editMeta { meta ->
                meta.displayName(plugin.commsManager.parseLegacy("&6&lRelics").decoration(TextDecoration.ITALIC, false))
                meta.lore(listOf(Component.empty(), Component.text("  Browse legendary relics", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false), Component.text("  Only one player per relic!", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)))
                meta.setEnchantmentGlintOverride(true)
            }
        }
        gui.setItem(15, relicIcon) { p, _ -> openRelicGui(p) }

        // Unequip
        val unequip = ItemStack(Material.BARRIER).apply {
            editMeta { it.displayName(Component.text("Unequip", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)) }
        }
        gui.setItem(22, unequip) { p, _ ->
            if (getPlayerTalisman(p.uniqueId) != null) {
                unequipTalisman(p); p.playSound(p.location, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f)
                plugin.commsManager.send(p, Component.text("Talisman unequipped.", NamedTextColor.YELLOW))
            }
            p.closeInventory()
        }

        plugin.guiManager.open(player, gui)
    }

    fun openTalismanGui(player: Player) {
        val talismans = registry.values.filter { !it.unique }.toList()
        val equippedId = playerCache[player.uniqueId]
        val gui = CustomGui(plugin.commsManager.parseLegacy("&e&lTalismans"), 54)
        gui.fill(FILLER)

        val slots = mutableListOf<Int>()
        for (row in 1..4) for (col in 1..7) slots.add(row * 9 + col)

        for ((index, def) in talismans.withIndex()) {
            if (index >= slots.size) break
            val equipped = equippedId == def.id
            val hasAccess = hasAccess(player.uniqueId, def.id) || player.hasPermission("joshymc.talisman.${def.id}") || player.hasPermission("joshymc.talisman.admin")
            val item = buildGuiTalismanItem(def, equipped, hasAccess)
            gui.setItem(slots[index], item) { p, _ -> handleTalismanClick(p, def) }
        }

        val back = ItemStack(Material.ARROW).apply { editMeta { it.displayName(Component.text("Back", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)) } }
        gui.setItem(49, back) { p, _ -> openMainMenu(p) }
        plugin.guiManager.open(player, gui)
    }

    fun openRelicGui(player: Player) {
        val relics = registry.values.filter { it.unique }.toList()
        val equippedId = playerCache[player.uniqueId]
        val gui = CustomGui(plugin.commsManager.parseLegacy("&6&lRelics"), 27)
        gui.fill(FILLER)

        val slots = listOf(10, 11, 12, 13, 14, 15, 16)
        for ((index, def) in relics.withIndex()) {
            if (index >= slots.size) break
            val equipped = equippedId == def.id
            val item = buildRelicItem(def, equipped, player)
            gui.setItem(slots[index], item) { p, _ -> handleTalismanClick(p, def) }
        }

        val back = ItemStack(Material.ARROW).apply { editMeta { it.displayName(Component.text("Back", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)) } }
        gui.setItem(22, back) { p, _ -> openMainMenu(p) }
        plugin.guiManager.open(player, gui)
    }

    private fun buildGuiTalismanItem(def: TalismanDef, equipped: Boolean, hasAccess: Boolean): ItemStack {
        val item = if (hasAccess) ItemStack(def.icon) else ItemStack(Material.RED_STAINED_GLASS_PANE)
        item.editMeta { meta ->
            meta.displayName(Component.text(def.name, if (hasAccess) NamedTextColor.YELLOW else NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false))
            val lore = mutableListOf<Component>()
            lore.add(Component.empty())
            lore.add(Component.text(def.description, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            lore.add(Component.empty())
            for (effect in def.effects) {
                lore.add(Component.text(" - ${describeEffect(effect)}", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
            }
            lore.add(Component.empty())
            when {
                equipped -> lore.add(Component.text("EQUIPPED", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
                hasAccess -> lore.add(Component.text("Click to equip", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
                else -> lore.add(Component.text("Locked — ask an admin for access", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
            }
            meta.lore(lore)
            if (equipped) meta.setEnchantmentGlintOverride(true)
        }
        return item
    }

    // Keep legacy name for backward compat
    fun openTalismanMenu(player: Player) = openMainMenu(player)

    private fun buildRelicItem(def: TalismanDef, equipped: Boolean, viewer: Player): ItemStack {
        val item = ItemStack(def.icon)
        item.editMeta { meta ->
            meta.displayName(Component.text(def.name, NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true))

            val lore = mutableListOf<Component>()
            lore.add(Component.text(def.rarity.displayName, NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true))
            lore.add(Component.empty())
            lore.add(Component.text(def.description, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false))
            lore.add(Component.empty())

            // Effects list
            for (effect in def.effects) {
                lore.add(Component.text(" \u2022 ${describeEffect(effect)}", NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false))
            }

            lore.add(Component.empty())

            // Owner info
            val owner = getRelicOwner(def.id)
            if (owner != null) {
                val ownerName = Bukkit.getOfflinePlayer(owner).name ?: "Unknown"
                if (owner == viewer.uniqueId) {
                    lore.add(Component.text("Owner: ", NamedTextColor.GRAY)
                        .append(Component.text("You", NamedTextColor.GREEN))
                        .decoration(TextDecoration.ITALIC, false))
                } else {
                    lore.add(Component.text("Owner: ", NamedTextColor.GRAY)
                        .append(Component.text(ownerName, NamedTextColor.RED))
                        .decoration(TextDecoration.ITALIC, false))
                }
            } else {
                lore.add(Component.text("UNCLAIMED", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true))
            }

            lore.add(Component.text("Only one player can hold this.", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, true))
            lore.add(Component.empty())

            if (equipped) {
                lore.add(Component.text("EQUIPPED", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true))
            } else if (owner == null) {
                lore.add(Component.text("Click to equip", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false))
            } else if (owner != viewer.uniqueId) {
                lore.add(Component.text("Owned by another player", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false))
            }

            meta.lore(lore)

            if (equipped) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true)
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            }
        }
        return item
    }

    private fun handleTalismanClick(player: Player, def: TalismanDef) {
        if (playerCache[player.uniqueId] == def.id) {
            // Already equipped — do nothing
            return
        }

        if (!canEquip(player, def)) {
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            plugin.commsManager.send(player,
                Component.text("This relic is already claimed by another player.", NamedTextColor.RED))
            return
        }

        if (equipTalisman(player, def.id)) {
            player.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.2f)
            val nameColor = if (def.unique) NamedTextColor.GOLD else NamedTextColor.YELLOW
            plugin.commsManager.send(player,
                Component.text("Equipped ", NamedTextColor.GREEN)
                    .append(Component.text(def.name, nameColor)))

            // Broadcast relic claim
            if (def.unique) {
                val message = plugin.commsManager.parseLegacy(
                    "&6&l${player.name} has claimed the ${def.rarity.color}${def.name}&6&l!"
                )
                Bukkit.getOnlinePlayers().forEach { it.sendMessage(message) }
            }

            openTalismanMenu(player)
        }
    }

}
