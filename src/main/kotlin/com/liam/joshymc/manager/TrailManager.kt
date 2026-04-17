package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Particle.DustOptions
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TrailManager(private val plugin: Joshymc) : Listener {

    data class Trail(
        val id: String,
        val name: String,
        val permission: String,
        val icon: Material,
        val color: String,
        val category: String,
        val particle: Particle,
        val dustOptions: DustOptions? = null,
        val count: Int = 3
    )

    private val trails = mutableListOf<Trail>()
    private val playerTrails = ConcurrentHashMap<UUID, String>()
    private val lastLocations = ConcurrentHashMap<UUID, Location>()
    private var task: BukkitTask? = null

    private val TITLE = Component.text("         ")
        .append(Component.text("T", TextColor.color(0xFF6B6B)))
        .append(Component.text("r", TextColor.color(0xFF8B5E)))
        .append(Component.text("a", TextColor.color(0xFFAB51)))
        .append(Component.text("i", TextColor.color(0xFFCB44)))
        .append(Component.text("l", TextColor.color(0xFFEB37)))
        .append(Component.text("s", TextColor.color(0xDFFF2A)))
        .decoration(TextDecoration.BOLD, true)
        .decoration(TextDecoration.ITALIC, false)

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    private val categoryTextColors = mapOf(
        "Fire" to TextColor.color(0xFF6B00),
        "Ice" to TextColor.color(0x55CCFF),
        "Nature" to TextColor.color(0x55FF55),
        "Dark" to TextColor.color(0x888888),
        "Light" to TextColor.color(0xFFFF55),
        "Love" to TextColor.color(0xFF55AA),
        "Music" to TextColor.color(0xAA55FF),
        "Water" to TextColor.color(0x5555FF),
        "Electric" to TextColor.color(0x55FFFF),
        "Mythic" to TextColor.color(0xFF55FF)
    )

    fun start() {
        registerTrails()

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_trails (
                uuid TEXT PRIMARY KEY,
                trail_id TEXT NOT NULL
            )
        """.trimIndent())

        // Load all saved trails
        plugin.databaseManager.query(
            "SELECT uuid, trail_id FROM player_trails",
            mapper = { rs -> rs.getString("uuid") to rs.getString("trail_id") }
        ).forEach { (uuid, trailId) ->
            if (trails.any { it.id == trailId }) {
                playerTrails[UUID.fromString(uuid)] = trailId
            }
        }

        // Particle task — every 2 ticks
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                val trailId = playerTrails[player.uniqueId] ?: continue
                val trail = trails.find { it.id == trailId } ?: continue

                // Vanished players should not emit trails — the particles would
                // give away their position even though the player entity is hidden.
                if (plugin.vanishCommand.isVanished(player)) continue

                val loc = player.location
                val last = lastLocations[player.uniqueId]

                // Only spawn if player has moved
                if (last != null && loc.world == last.world &&
                    loc.x == last.x && loc.y == last.y && loc.z == last.z
                ) continue

                spawnTrailParticles(player, trail)
            }
        }, 2L, 2L)

        Bukkit.getPluginManager().registerEvents(this, plugin)
        plugin.logger.info("[Trails] Registered ${trails.size} trails.")
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    // -- Public API --

    fun getTrail(uuid: UUID): String? = playerTrails[uuid]

    fun setTrail(uuid: UUID, trailId: String) {
        playerTrails[uuid] = trailId
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO player_trails (uuid, trail_id) VALUES (?, ?)",
            uuid.toString(), trailId
        )
    }

    fun removeTrail(uuid: UUID) {
        playerTrails.remove(uuid)
        plugin.databaseManager.execute("DELETE FROM player_trails WHERE uuid = ?", uuid.toString())
    }

    fun canUse(player: Player, trail: Trail): Boolean {
        return player.hasPermission("joshymc.trail.*") || player.hasPermission(trail.permission)
    }

    fun getTrailById(id: String): Trail? = trails.find { it.id == id }

    // -- Events --

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastLocations.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        lastLocations[event.player.uniqueId] = event.from.clone()
    }

    // -- Particle spawning --

    private fun spawnTrailParticles(player: Player, trail: Trail) {
        val loc = player.location
        val world = loc.world ?: return

        repeat(trail.count) {
            val offsetX = (Math.random() - 0.5) * 0.6
            val offsetZ = (Math.random() - 0.5) * 0.6
            val offsetY = Math.random() * 0.3
            val spawnLoc = loc.clone().add(offsetX, offsetY, offsetZ)

            if (trail.dustOptions != null) {
                world.spawnParticle(trail.particle, spawnLoc, 1, 0.0, 0.0, 0.0, 0.0, trail.dustOptions)
            } else {
                world.spawnParticle(trail.particle, spawnLoc, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    // Category icons for the category selection screen
    private val categoryIcons = mapOf(
        "Fire" to Material.BLAZE_POWDER,
        "Ice" to Material.SNOWBALL,
        "Nature" to Material.OAK_LEAVES,
        "Dark" to Material.WITHER_SKELETON_SKULL,
        "Light" to Material.GLOWSTONE_DUST,
        "Love" to Material.RED_DYE,
        "Music" to Material.NOTE_BLOCK,
        "Water" to Material.WATER_BUCKET,
        "Electric" to Material.LIGHTNING_ROD,
        "Mythic" to Material.DRAGON_HEAD
    )

    private val categoryOrder = listOf("Fire", "Ice", "Nature", "Dark", "Light", "Love", "Music", "Water", "Electric", "Mythic")

    // -- GUI --

    fun openTrailMenu(player: Player, fromHub: Boolean = false) {
        val gui = CustomGui(TITLE, 27)

        // Fill all slots with filler
        for (i in 0 until 27) {
            gui.setItem(i, FILLER)
        }

        val categories = trails.groupBy { it.category }

        // Place category icons in slots 0-9 mapped to row 1 (slots 9-17) centered
        // 10 categories in row 0 cols 0-8 and row 1 col 0 → place in slots 2-6 (row 0) and 11-15 (row 1)
        // Better layout: two rows of 5, centered
        val topRowSlots = intArrayOf(2, 3, 4, 5, 6)
        val bottomRowSlots = intArrayOf(11, 12, 13, 14, 15)

        for ((index, cat) in categoryOrder.withIndex()) {
            val slot = if (index < 5) topRowSlots[index] else bottomRowSlots[index - 5]
            val iconMat = categoryIcons[cat] ?: Material.STONE
            val textColor = categoryTextColors[cat] ?: NamedTextColor.WHITE
            val trailCount = categories[cat]?.size ?: 0

            val item = ItemStack(iconMat).apply {
                editMeta { meta ->
                    meta.displayName(
                        Component.text(cat, textColor)
                            .decoration(TextDecoration.BOLD, true)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                    meta.lore(listOf(
                        Component.empty(),
                        Component.text("$trailCount trails", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.text("Click to browse", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false)
                    ))
                }
            }
            gui.setItem(slot, item) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
                openCategoryPage(p, cat, 0, fromHub)
            }
        }

        // Back to cosmetics hub button (slot 22, bottom-right area)
        if (fromHub) {
            val backItem = ItemStack(Material.ARROW).apply {
                editMeta { meta ->
                    meta.displayName(
                        Component.text("Back to Cosmetics", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                }
            }
            gui.setItem(22, backItem) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
                p.closeInventory()
                p.performCommand("cosmetics")
            }
        }

        plugin.guiManager.open(player, gui)
    }

    fun openCategoryPage(player: Player, category: String, page: Int = 0, fromHub: Boolean = false) {
        val textColor = categoryTextColors[category] ?: NamedTextColor.WHITE
        val categoryTitle = Component.text("         ")
            .append(Component.text(category, textColor)
                .decoration(TextDecoration.BOLD, true))
            .append(Component.text(" Trails", NamedTextColor.GRAY)
                .decoration(TextDecoration.BOLD, true))
            .decoration(TextDecoration.ITALIC, false)

        val gui = CustomGui(categoryTitle, 54)

        val equippedTrailId = playerTrails[player.uniqueId]
        val catTrails = trails.filter { it.category == category }

        // 28 item slots: rows 1-4 (slots 10-16, 19-25, 28-34, 37-43), cols 1-7
        val itemSlots = mutableListOf<Int>()
        for (row in 1..4) {
            for (col in 1..7) {
                itemSlots.add(row * 9 + col)
            }
        }
        val slotsPerPage = itemSlots.size // 28
        val totalPages = if (catTrails.isEmpty()) 1 else ((catTrails.size - 1) / slotsPerPage) + 1
        val currentPage = page.coerceIn(0, totalPages - 1)
        val startIndex = currentPage * slotsPerPage
        val endIndex = (startIndex + slotsPerPage).coerceAtMost(catTrails.size)

        // Fill all slots with filler first
        for (i in 0 until 54) {
            gui.setItem(i, FILLER)
        }

        // Place trail items
        for (i in startIndex until endIndex) {
            val slotIndex = i - startIndex
            val slot = itemSlots[slotIndex]
            val trail = catTrails[i]
            val hasPermission = canUse(player, trail)
            val isEquipped = equippedTrailId == trail.id

            if (!hasPermission) {
                val locked = ItemStack(Material.RED_STAINED_GLASS_PANE).apply {
                    editMeta { meta ->
                        meta.displayName(
                            Component.text(trail.name, NamedTextColor.RED)
                                .decoration(TextDecoration.ITALIC, false)
                        )
                        meta.lore(listOf(
                            Component.empty(),
                            Component.text("Locked", NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false),
                            Component.text("Missing permission", NamedTextColor.DARK_GRAY)
                                .decoration(TextDecoration.ITALIC, false)
                        ))
                    }
                }
                gui.setItem(slot, locked)
            } else {
                val colorCode = trail.color
                val trailTextColor = parseColorCode(colorCode)
                val item = ItemStack(trail.icon).apply {
                    editMeta { meta ->
                        meta.displayName(
                            Component.text(trail.name, trailTextColor)
                                .decoration(TextDecoration.BOLD, isEquipped)
                                .decoration(TextDecoration.ITALIC, false)
                        )
                        val lore = mutableListOf<Component>()
                        lore.add(Component.empty())
                        if (isEquipped) {
                            lore.add(
                                Component.text("Equipped", NamedTextColor.GREEN)
                                    .decoration(TextDecoration.ITALIC, false)
                            )
                        } else {
                            lore.add(
                                Component.text("Click to equip", NamedTextColor.YELLOW)
                                    .decoration(TextDecoration.ITALIC, false)
                            )
                        }
                        meta.lore(lore)
                        if (isEquipped) {
                            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true)
                            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
                        }
                    }
                }
                gui.setItem(slot, item) { p, _ ->
                    setTrail(p.uniqueId, trail.id)
                    p.playSound(p.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f)
                    openCategoryPage(p, category, currentPage, fromHub)
                }
            }
        }

        // Bottom row: navigation (row 5, slots 45-53) — already filled with filler

        // Remove trail button at slot 45
        val unequipItem = ItemStack(Material.BARRIER).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("Remove Trail", NamedTextColor.RED)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("Click to remove your active trail", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                ))
            }
        }
        gui.setItem(45, unequipItem) { p, _ ->
            removeTrail(p.uniqueId)
            p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f)
            openCategoryPage(p, category, currentPage, fromHub)
        }

        // Back button at slot 49 — goes to category selection
        val backItem = ItemStack(Material.ARROW).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("Back", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                )
            }
        }
        gui.setItem(49, backItem) { p, _ ->
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
            openTrailMenu(p, fromHub)
        }

        // Previous page
        if (currentPage > 0) {
            val prev = ItemStack(Material.ARROW).apply {
                editMeta { meta ->
                    meta.displayName(
                        Component.text("Previous Page", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                }
            }
            gui.setItem(48, prev) { p, _ -> openCategoryPage(p, category, currentPage - 1, fromHub) }
        }

        // Next page
        if (currentPage < totalPages - 1) {
            val next = ItemStack(Material.ARROW).apply {
                editMeta { meta ->
                    meta.displayName(
                        Component.text("Next Page", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                }
            }
            gui.setItem(50, next) { p, _ -> openCategoryPage(p, category, currentPage + 1, fromHub) }
        }

        plugin.guiManager.open(player, gui)
    }

    // -- Trail registration --

    private fun registerTrails() {
        // Fire (6)
        trail("flame", "Flame", "Fire", Material.BLAZE_POWDER, "&6", Particle.FLAME)
        trail("fire_trail", "Fire Trail", "Fire", Material.FIRE_CHARGE, "&c", Particle.FLAME, count = 4)
        trail("blaze", "Blaze", "Fire", Material.BLAZE_ROD, "&e", Particle.SMALL_FLAME)
        trail("inferno", "Inferno", "Fire", Material.MAGMA_CREAM, "&4", Particle.LAVA)
        trail("ember_glow", "Ember Glow", "Fire", Material.COAL, "&6", Particle.DUST, dust(Color.fromRGB(255, 140, 0), 1.2f))
        trail("magma_walk", "Magma Walk", "Fire", Material.MAGMA_BLOCK, "&c", Particle.DUST, dust(Color.fromRGB(200, 50, 0), 1.5f))

        // Ice (5)
        trail("snowflake", "Snowflake", "Ice", Material.SNOWBALL, "&b", Particle.SNOWFLAKE)
        trail("frost", "Frost", "Ice", Material.ICE, "&f", Particle.DUST, dust(Color.fromRGB(200, 230, 255), 1.0f))
        trail("blizzard_trail", "Blizzard Trail", "Ice", Material.PACKED_ICE, "&b", Particle.SNOWFLAKE, count = 4)
        trail("ice_crystals", "Ice Crystals", "Ice", Material.BLUE_ICE, "&3", Particle.DUST, dust(Color.fromRGB(100, 180, 255), 1.3f))
        trail("frozen_path", "Frozen Path", "Ice", Material.SNOW_BLOCK, "&f", Particle.DUST, dust(Color.WHITE, 1.0f))

        // Nature (6)
        trail("cherry_blossoms", "Cherry Blossoms", "Nature", Material.CHERRY_LEAVES, "&d", Particle.CHERRY_LEAVES)
        trail("leaves", "Leaves", "Nature", Material.OAK_LEAVES, "&2", Particle.DUST, dust(Color.fromRGB(34, 139, 34), 1.2f))
        trail("flower_petals", "Flower Petals", "Nature", Material.PINK_PETALS, "&d", Particle.DUST, dust(Color.fromRGB(255, 150, 200), 1.0f))
        trail("spore_blossom", "Spore Blossom", "Nature", Material.SPORE_BLOSSOM, "&a", Particle.FALLING_SPORE_BLOSSOM)
        trail("moss_trail", "Moss Trail", "Nature", Material.MOSS_BLOCK, "&a", Particle.DUST, dust(Color.fromRGB(80, 160, 60), 1.2f))
        trail("vine_trail", "Vine Trail", "Nature", Material.VINE, "&2", Particle.DUST, dust(Color.fromRGB(0, 128, 0), 1.0f))

        // Dark (6)
        trail("soul_fire", "Soul Fire", "Dark", Material.SOUL_LANTERN, "&3", Particle.SOUL_FIRE_FLAME)
        trail("wither", "Wither", "Dark", Material.WITHER_SKELETON_SKULL, "&8", Particle.SMOKE)
        trail("end_rod", "End Rod", "Dark", Material.END_ROD, "&f", Particle.END_ROD)
        trail("void", "Void", "Dark", Material.OBSIDIAN, "&0", Particle.DUST, dust(Color.fromRGB(20, 0, 30), 1.5f))
        trail("shadow", "Shadow", "Dark", Material.BLACK_WOOL, "&8", Particle.DUST, dust(Color.fromRGB(30, 30, 30), 1.3f))
        trail("sculk_pulse", "Sculk Pulse", "Dark", Material.SCULK, "&1", Particle.SCULK_CHARGE_POP)

        // Light (5)
        trail("enchant_glint", "Enchant Glint", "Light", Material.ENCHANTING_TABLE, "&b", Particle.ENCHANT)
        trail("star_sparkle", "Star Sparkle", "Light", Material.NETHER_STAR, "&e", Particle.END_ROD, count = 2)
        trail("beacon_beam", "Beacon Beam", "Light", Material.BEACON, "&f", Particle.DUST, dust(Color.WHITE, 1.5f), count = 4)
        trail("prism", "Prism", "Light", Material.PRISMARINE_SHARD, "&b", Particle.DUST, dust(Color.fromRGB(100, 200, 255), 1.0f))
        trail("holy_light", "Holy Light", "Light", Material.GLOWSTONE, "&e", Particle.DUST, dust(Color.fromRGB(255, 255, 180), 1.4f))

        // Love (4)
        trail("hearts", "Hearts", "Love", Material.RED_DYE, "&c", Particle.HEART, count = 2)
        trail("cupids_trail", "Cupid's Trail", "Love", Material.ARROW, "&d", Particle.HEART, count = 3)
        trail("valentine", "Valentine", "Love", Material.PINK_DYE, "&d", Particle.DUST, dust(Color.fromRGB(255, 105, 180), 1.2f))
        trail("rose_petals", "Rose Petals", "Love", Material.POPPY, "&4", Particle.DUST, dust(Color.fromRGB(180, 30, 50), 1.0f))

        // Music (4)
        trail("note_trail", "Note Trail", "Music", Material.NOTE_BLOCK, "&a", Particle.NOTE, count = 2)
        trail("jukebox", "Jukebox", "Music", Material.JUKEBOX, "&6", Particle.NOTE, count = 3)
        trail("melody", "Melody", "Music", Material.MUSIC_DISC_CAT, "&d", Particle.NOTE, count = 2)
        trail("bass_drop", "Bass Drop", "Music", Material.MUSIC_DISC_STRAD, "&5", Particle.DUST, dust(Color.fromRGB(128, 0, 128), 1.5f))

        // Water (5)
        trail("bubble", "Bubble", "Water", Material.PUFFERFISH, "&9", Particle.BUBBLE_POP)
        trail("drip", "Drip", "Water", Material.WATER_BUCKET, "&1", Particle.DRIPPING_WATER)
        trail("rain", "Rain", "Water", Material.LILY_PAD, "&9", Particle.RAIN)
        trail("ocean_spray", "Ocean Spray", "Water", Material.NAUTILUS_SHELL, "&b", Particle.DUST, dust(Color.fromRGB(50, 130, 200), 1.0f))
        trail("waterfall", "Waterfall", "Water", Material.HEART_OF_THE_SEA, "&3", Particle.FALLING_WATER)

        // Electric (4)
        trail("lightning", "Lightning", "Electric", Material.LIGHTNING_ROD, "&e", Particle.ELECTRIC_SPARK)
        trail("spark", "Spark", "Electric", Material.REDSTONE, "&c", Particle.ELECTRIC_SPARK, count = 2)
        trail("tesla", "Tesla", "Electric", Material.COPPER_BLOCK, "&6", Particle.DUST, dust(Color.fromRGB(100, 200, 255), 1.3f))
        trail("static", "Static", "Electric", Material.IRON_NUGGET, "&7", Particle.DUST, dust(Color.fromRGB(180, 220, 255), 0.8f))

        // Mythic (5)
        trail("dragon_breath", "Dragon Breath", "Mythic", Material.DRAGON_BREATH, "&5", Particle.DRAGON_BREATH)
        trail("phoenix", "Phoenix", "Mythic", Material.FEATHER, "&6", Particle.DUST, dust(Color.fromRGB(255, 100, 0), 1.4f))
        trail("ender", "Ender", "Mythic", Material.ENDER_PEARL, "&5", Particle.PORTAL, count = 4)
        trail("nether_portal", "Nether Portal", "Mythic", Material.CRYING_OBSIDIAN, "&5", Particle.PORTAL)
        trail("totem_sparkle", "Totem Sparkle", "Mythic", Material.TOTEM_OF_UNDYING, "&a", Particle.TOTEM_OF_UNDYING, count = 2)
    }

    private fun trail(
        id: String,
        name: String,
        category: String,
        icon: Material,
        color: String,
        particle: Particle,
        dustOptions: DustOptions? = null,
        count: Int = 3
    ) {
        trails.add(Trail(
            id = id,
            name = name,
            permission = "joshymc.trail.$id",
            icon = icon,
            color = color,
            category = category,
            particle = particle,
            dustOptions = dustOptions,
            count = count
        ))
    }

    private fun dust(color: Color, size: Float): DustOptions = DustOptions(color, size)

    private fun parseColorCode(code: String): TextColor = when (code) {
        "&0" -> NamedTextColor.BLACK
        "&1" -> NamedTextColor.DARK_BLUE
        "&2" -> NamedTextColor.DARK_GREEN
        "&3" -> NamedTextColor.DARK_AQUA
        "&4" -> NamedTextColor.DARK_RED
        "&5" -> NamedTextColor.DARK_PURPLE
        "&6" -> NamedTextColor.GOLD
        "&7" -> NamedTextColor.GRAY
        "&8" -> NamedTextColor.DARK_GRAY
        "&9" -> NamedTextColor.BLUE
        "&a" -> NamedTextColor.GREEN
        "&b" -> NamedTextColor.AQUA
        "&c" -> NamedTextColor.RED
        "&d" -> NamedTextColor.LIGHT_PURPLE
        "&e" -> NamedTextColor.YELLOW
        "&f" -> NamedTextColor.WHITE
        else -> NamedTextColor.WHITE
    }
}
