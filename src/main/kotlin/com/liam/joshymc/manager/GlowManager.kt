package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Team
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GlowManager(private val plugin: Joshymc) : Listener {

    data class GlowColor(
        val id: String,
        val displayName: String,
        val namedTextColor: NamedTextColor,
        val woolMaterial: Material
    )

    private val colors = mutableListOf<GlowColor>()
    private val equippedColors = ConcurrentHashMap<UUID, String>()
    private var refreshTask: BukkitTask? = null

    private val TEAM_PREFIX = "joshyglow_"

    fun canUse(player: Player, color: GlowColor): Boolean {
        if (player.hasPermission("joshymc.glow.*")) return true
        return player.hasPermission("joshymc.glow.${color.id}")
    }

    private val GUI_TITLE = Component.text("       ")
        .append(Component.text("G", TextColor.color(0xFF5555)))
        .append(Component.text("l", TextColor.color(0xFF8855)))
        .append(Component.text("o", TextColor.color(0xFFFF55)))
        .append(Component.text("w", TextColor.color(0x55FF55)))
        .append(Component.text(" ", NamedTextColor.WHITE))
        .append(Component.text("C", TextColor.color(0x55FFFF)))
        .append(Component.text("o", TextColor.color(0x5555FF)))
        .append(Component.text("l", TextColor.color(0xAA55FF)))
        .append(Component.text("o", TextColor.color(0xFF55FF)))
        .append(Component.text("r", TextColor.color(0xFF5555)))
        .append(Component.text("s", TextColor.color(0xFF8855)))
        .decoration(TextDecoration.BOLD, true)
        .decoration(TextDecoration.ITALIC, false)

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    private val BORDER = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    // ── Lifecycle ───────────────────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_glow (
                uuid TEXT PRIMARY KEY,
                color_id TEXT NOT NULL
            )
        """.trimIndent())

        registerColors()

        plugin.server.pluginManager.registerEvents(this, plugin)

        // Re-apply glow for any online players (in case of reload)
        for (player in Bukkit.getOnlinePlayers()) {
            loadAndApply(player)
        }

        // Periodically re-apply the glowing potion effect to keep it from
        // expiring or being stripped by other systems (milk, /effect clear, etc.)
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                val colorId = equippedColors[player.uniqueId] ?: continue
                val color = colors.find { it.id == colorId } ?: continue
                if (!canUse(player, color)) continue
                player.addPotionEffect(
                    PotionEffect(PotionEffectType.GLOWING, 400, 0, false, false, false)
                )
            }
        }, 100L, 100L) // every 5 seconds, with a 20-second effect duration

        plugin.logger.info("[Glow] Registered ${colors.size} glow colors.")
    }

    fun stop() {
        refreshTask?.cancel()
        refreshTask = null
        // Clean up all teams on shutdown
        val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        for (color in colors) {
            val teamName = TEAM_PREFIX + color.id
            scoreboard.getTeam(teamName)?.unregister()
        }
    }

    // ── Events ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    fun onJoin(event: PlayerJoinEvent) {
        loadAndApply(event.player)
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        removeGlowTeam(player)
        equippedColors.remove(player.uniqueId)
    }

    // ── GUI ─────────────────────────────────────────────────────────

    fun openGui(player: Player) {
        val gui = CustomGui(GUI_TITLE, 27)

        // Fill and border
        for (i in 0 until 27) gui.inventory.setItem(i, FILLER.clone())
        for (i in 0..8) {
            gui.inventory.setItem(i, BORDER.clone())
            gui.inventory.setItem(18 + i, BORDER.clone())
        }
        gui.inventory.setItem(9, BORDER.clone())
        gui.inventory.setItem(17, BORDER.clone())

        val currentColorId = equippedColors[player.uniqueId]

        // Place color items in middle row (slots 10-16 = 7 slots) and wrap to edges if needed
        // 15 colors: slots 10-16 (row 1 inner), plus we use all 3 rows inner area
        // Row 0: slots 1-7, Row 1: slots 10-16, Row 2: slots 19-25
        // But we have border on row 0 and 2, so use row 1 (7 slots) + row 0 inner (7) + row 2 (1 for barrier)
        // Better layout: use all 27 slots more creatively
        // Simplest: 27 slot, top row = first 7 colors (1-7), middle row = next 7 (10-16), bottom = last 1 + barrier
        val colorSlots = listOf(1, 2, 3, 4, 5, 6, 7, 10, 11, 12, 13, 14, 15, 16, 19)

        for ((i, color) in colors.withIndex()) {
            if (i >= colorSlots.size) break
            val slot = colorSlots[i]
            val hasPermission = canUse(player, color)
            val equipped = color.id == currentColorId

            gui.setItem(slot, buildColorItem(color, hasPermission, equipped)) { p, _ ->
                if (!canUse(p, color)) {
                    p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                    return@setItem
                }

                if (equipped) {
                    // Unequip
                    removeGlowTeam(p)
                    equippedColors.remove(p.uniqueId)
                    plugin.databaseManager.execute(
                        "DELETE FROM player_glow WHERE uuid = ?", p.uniqueId.toString()
                    )
                    p.removePotionEffect(PotionEffectType.GLOWING)
                    p.sendMessage(Component.text("Glow removed.", NamedTextColor.RED))
                    p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f)
                } else {
                    // Remove old team first
                    removeGlowTeam(p)
                    equippedColors[p.uniqueId] = color.id
                    applyGlow(p, color)
                    plugin.databaseManager.execute(
                        "INSERT OR REPLACE INTO player_glow (uuid, color_id) VALUES (?, ?)",
                        p.uniqueId.toString(), color.id
                    )
                    p.sendMessage(
                        Component.text("Glow set to: ", NamedTextColor.GREEN)
                            .append(Component.text(color.displayName, color.namedTextColor))
                    )
                    p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 2.0f)
                }

                p.closeInventory()
                openGui(p)
            }
        }

        // Remove button at slot 22
        val removeItem = ItemStack(Material.BARRIER).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("Remove Glow", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("Click to remove your glow", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                ))
            }
        }
        gui.setItem(22, removeItem) { p, _ ->
            removeGlowTeam(p)
            equippedColors.remove(p.uniqueId)
            plugin.databaseManager.execute(
                "DELETE FROM player_glow WHERE uuid = ?", p.uniqueId.toString()
            )
            p.removePotionEffect(PotionEffectType.GLOWING)
            p.sendMessage(Component.text("Glow removed.", NamedTextColor.RED))
            p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f)
            p.closeInventory()
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // ── Item builder ────────────────────────────────────────────────

    private fun buildColorItem(color: GlowColor, hasPermission: Boolean, equipped: Boolean): ItemStack {
        val mat = if (hasPermission) color.woolMaterial else Material.GRAY_DYE
        val item = ItemStack(mat)
        item.editMeta { meta ->
            val nameColor = when {
                equipped -> NamedTextColor.GREEN
                hasPermission -> color.namedTextColor
                else -> NamedTextColor.DARK_GRAY
            }
            meta.displayName(
                Component.text(color.displayName, nameColor)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            val lore = mutableListOf<Component>(
                Component.empty(),
                Component.text("Colored glowing outline", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            )
            if (!hasPermission) {
                lore += Component.empty()
                lore += Component.text("  Locked", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
            } else if (equipped) {
                lore += Component.empty()
                lore += Component.text("  Equipped (click to remove)", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
            } else {
                lore += Component.empty()
                lore += Component.text("  Click to equip", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            }
            meta.lore(lore)
            if (equipped) meta.setEnchantmentGlintOverride(true)
        }
        return item
    }

    // ── Glow logic ──────────────────────────────────────────────────

    private fun loadAndApply(player: Player) {
        val uuid = player.uniqueId

        // Load from DB
        val colorId = plugin.databaseManager.queryFirst(
            "SELECT color_id FROM player_glow WHERE uuid = ?",
            uuid.toString()
        ) { rs -> rs.getString("color_id") }

        if (colorId != null) {
            equippedColors[uuid] = colorId
            val color = colors.find { it.id == colorId }
            if (color != null && canUse(player, color)) {
                applyGlow(player, color)
            }
        }
    }

    private fun applyGlow(player: Player, color: GlowColor) {
        // Remove from any existing glow teams first
        removeGlowTeam(player)

        // Apply glow on the PLAYER'S scoreboard (not main — players use custom scoreboards)
        val scoreboard = player.scoreboard
        val teamName = TEAM_PREFIX + color.id

        var team = scoreboard.getTeam(teamName)
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName)
        }
        team.color(color.namedTextColor)
        team.addEntry(player.name)

        // Also apply on ALL other online players' scoreboards so they see the glow color
        for (other in Bukkit.getOnlinePlayers()) {
            if (other == player) continue
            val otherBoard = other.scoreboard
            var otherTeam = otherBoard.getTeam(teamName)
            if (otherTeam == null) {
                otherTeam = otherBoard.registerNewTeam(teamName)
            }
            otherTeam.color(color.namedTextColor)
            otherTeam.addEntry(player.name)
        }

        // Apply glowing effect (refreshed periodically by the refresh task)
        player.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, 400, 0, false, false, false))
    }

    private fun removeGlowTeam(player: Player) {
        // Remove from all online players' scoreboards
        for (online in Bukkit.getOnlinePlayers()) {
            val scoreboard = online.scoreboard
            for (color in colors) {
                val teamName = TEAM_PREFIX + color.id
                val team = scoreboard.getTeam(teamName) ?: continue
                if (team.hasEntry(player.name)) {
                    team.removeEntry(player.name)
                }
            }
        }
    }

    fun evictCache(uuid: UUID) {
        equippedColors.remove(uuid)
    }

    // ── Color registration ──────────────────────────────────────────

    private fun registerColors() {
        colors.add(GlowColor("red", "Red", NamedTextColor.RED, Material.RED_WOOL))
        colors.add(GlowColor("blue", "Blue", NamedTextColor.BLUE, Material.BLUE_WOOL))
        colors.add(GlowColor("green", "Green", NamedTextColor.GREEN, Material.LIME_WOOL))
        colors.add(GlowColor("gold", "Gold", NamedTextColor.GOLD, Material.ORANGE_WOOL))
        colors.add(GlowColor("aqua", "Aqua", NamedTextColor.AQUA, Material.LIGHT_BLUE_WOOL))
        colors.add(GlowColor("purple", "Purple", NamedTextColor.LIGHT_PURPLE, Material.PURPLE_WOOL))
        colors.add(GlowColor("white", "White", NamedTextColor.WHITE, Material.WHITE_WOOL))
        colors.add(GlowColor("black", "Black", NamedTextColor.BLACK, Material.BLACK_WOOL))
        colors.add(GlowColor("yellow", "Yellow", NamedTextColor.YELLOW, Material.YELLOW_WOOL))
        colors.add(GlowColor("pink", "Pink", NamedTextColor.LIGHT_PURPLE, Material.PINK_WOOL))
        colors.add(GlowColor("dark_red", "Dark Red", NamedTextColor.DARK_RED, Material.CRIMSON_HYPHAE))
        colors.add(GlowColor("dark_blue", "Dark Blue", NamedTextColor.DARK_BLUE, Material.CYAN_WOOL))
        colors.add(GlowColor("dark_green", "Dark Green", NamedTextColor.DARK_GREEN, Material.GREEN_WOOL))
        colors.add(GlowColor("dark_aqua", "Dark Aqua", NamedTextColor.DARK_AQUA, Material.CYAN_TERRACOTTA))
        colors.add(GlowColor("dark_purple", "Dark Purple", NamedTextColor.DARK_PURPLE, Material.MAGENTA_WOOL))
    }
}
