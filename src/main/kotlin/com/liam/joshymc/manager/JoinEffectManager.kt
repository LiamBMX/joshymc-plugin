package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.EntityType
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

class JoinEffectManager(private val plugin: Joshymc) : Listener {

    // ── Data classes ────────────────────────────────────────────────

    data class JoinEffect(
        val id: String,
        val displayName: String,
        val material: Material,
        val description: String,
        val runner: (Player) -> Unit
    )

    data class JoinMessage(
        val id: String,
        val format: String,
        val material: Material,
        val displayName: String
    )

    // ── State ───────────────────────────────────────────────────────

    private val effects = mutableListOf<JoinEffect>()
    private val messages = mutableListOf<JoinMessage>()

    /** uuid -> effect_id */
    private val equippedEffects = ConcurrentHashMap<UUID, String>()
    /** uuid -> message_id */
    private val equippedMessages = ConcurrentHashMap<UUID, String>()

    private val GUI_TITLE = Component.text("    ")
        .append(Component.text("J", TextColor.color(0xFF6600)))
        .append(Component.text("o", TextColor.color(0xFF7711)))
        .append(Component.text("i", TextColor.color(0xFF8822)))
        .append(Component.text("n", TextColor.color(0xFF9933)))
        .append(Component.text(" ", TextColor.color(0xFFAA44)))
        .append(Component.text("E", TextColor.color(0xFFBB55)))
        .append(Component.text("f", TextColor.color(0xFFCC66)))
        .append(Component.text("f", TextColor.color(0xFFDD77)))
        .append(Component.text("e", TextColor.color(0xFFEE88)))
        .append(Component.text("c", TextColor.color(0xFFFF99)))
        .append(Component.text("t", TextColor.color(0xFFFFAA)))
        .append(Component.text("s", TextColor.color(0xFFFFBB)))
        .decoration(TextDecoration.BOLD, true)
        .decoration(TextDecoration.ITALIC, false)

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    private val DIVIDER = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    // ── Lifecycle ───────────────────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_join_effects (
                uuid TEXT PRIMARY KEY,
                effect_id TEXT,
                message_id TEXT
            )
        """.trimIndent())

        registerEffects()
        registerMessages()

        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info("[JoinEffect] Registered ${effects.size} effects and ${messages.size} messages.")
    }

    // ── Event ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId

        // Load from DB if not cached
        if (!equippedEffects.containsKey(uuid) && !equippedMessages.containsKey(uuid)) {
            loadPlayer(uuid)
        }

        // Play effect
        val effectId = equippedEffects[uuid]
        if (effectId != null) {
            val effect = effects.find { it.id == effectId }
            if (effect != null && player.hasPermission("joshymc.joineffect.${effect.id}")) {
                effect.runner(player)
            }
        }

        // Override join message
        val messageId = equippedMessages[uuid]
        if (messageId != null) {
            val msg = messages.find { it.id == messageId }
            if (msg != null && player.hasPermission("joshymc.joinmsg.${msg.id}")) {
                val formatted = msg.format.replace("{player}", player.name)
                event.joinMessage(Component.text(formatted, NamedTextColor.YELLOW))
            }
        }
    }

    // ── GUI ─────────────────────────────────────────────────────────

    fun openGui(player: Player) {
        val gui = CustomGui(GUI_TITLE, 54)

        // Fill with filler
        for (i in 0 until 54) gui.inventory.setItem(i, FILLER.clone())

        // Divider row between effects and messages (row 3, slots 27-35)
        for (i in 27..35) gui.inventory.setItem(i, DIVIDER.clone())

        val playerEffectId = equippedEffects[player.uniqueId]
        val playerMessageId = equippedMessages[player.uniqueId]

        // ── Top half: effects (slots 0-26, rows 0-2) ───────────────
        // Use slots in rows 0-2, columns 1-7 (skip edges for aesthetics)
        val effectSlots = listOf(
            1, 2, 3, 4, 5, 6, 7,
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
        )

        for ((i, effect) in effects.withIndex()) {
            if (i >= effectSlots.size) break
            val slot = effectSlots[i]
            val hasPermission = player.hasPermission("joshymc.joineffect.${effect.id}")
            val equipped = effect.id == playerEffectId

            gui.setItem(slot, buildEffectItem(effect, hasPermission, equipped)) { p, event ->
                if (!p.hasPermission("joshymc.joineffect.${effect.id}")) {
                    p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                    return@setItem
                }

                if (equipped) {
                    // Unequip
                    equippedEffects.remove(p.uniqueId)
                    savePlayer(p.uniqueId)
                    p.sendMessage(Component.text("Join effect removed.", NamedTextColor.RED))
                    p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f)
                } else {
                    equippedEffects[p.uniqueId] = effect.id
                    savePlayer(p.uniqueId)
                    p.sendMessage(
                        Component.text("Equipped join effect: ", NamedTextColor.GREEN)
                            .append(Component.text(effect.displayName, NamedTextColor.GOLD))
                    )
                    p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 2.0f)
                }

                // Refresh GUI
                p.closeInventory()
                openGui(p)
            }
        }

        // ── Bottom half: messages (slots 36-53, rows 4-5) ───────────
        val messageSlots = listOf(
            37, 38, 39, 40, 41, 42, 43,
            46, 47, 48, 49, 50, 51, 52
        )

        // Clear button at slot 45
        val clearItem = ItemStack(Material.BARRIER).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("Remove All", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("Click to remove effect & message", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                ))
            }
        }
        gui.setItem(45, clearItem) { p, _ ->
            equippedEffects.remove(p.uniqueId)
            equippedMessages.remove(p.uniqueId)
            savePlayer(p.uniqueId)
            p.sendMessage(Component.text("Cleared join effect and message.", NamedTextColor.RED))
            p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f)
            p.closeInventory()
        }

        for ((i, msg) in messages.withIndex()) {
            if (i >= messageSlots.size) break
            val slot = messageSlots[i]
            val hasPermission = player.hasPermission("joshymc.joinmsg.${msg.id}")
            val equipped = msg.id == playerMessageId

            gui.setItem(slot, buildMessageItem(msg, hasPermission, equipped)) { p, event ->
                if (!p.hasPermission("joshymc.joinmsg.${msg.id}")) {
                    p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                    return@setItem
                }

                if (equipped) {
                    equippedMessages.remove(p.uniqueId)
                    savePlayer(p.uniqueId)
                    p.sendMessage(Component.text("Join message removed.", NamedTextColor.RED))
                    p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f)
                } else {
                    equippedMessages[p.uniqueId] = msg.id
                    savePlayer(p.uniqueId)
                    val preview = msg.format.replace("{player}", p.name)
                    p.sendMessage(
                        Component.text("Equipped join message: ", NamedTextColor.GREEN)
                            .append(Component.text(preview, NamedTextColor.YELLOW))
                    )
                    p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 2.0f)
                }

                p.closeInventory()
                openGui(p)
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // ── Item builders ───────────────────────────────────────────────

    private fun buildEffectItem(effect: JoinEffect, hasPermission: Boolean, equipped: Boolean): ItemStack {
        val mat = if (hasPermission) effect.material else Material.GRAY_DYE
        val item = ItemStack(mat)
        item.editMeta { meta ->
            val nameColor = when {
                equipped -> NamedTextColor.GREEN
                hasPermission -> NamedTextColor.GOLD
                else -> NamedTextColor.DARK_GRAY
            }
            meta.displayName(
                Component.text(effect.displayName, nameColor)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            val lore = mutableListOf(
                Component.empty(),
                Component.text(effect.description, NamedTextColor.GRAY)
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

    private fun buildMessageItem(msg: JoinMessage, hasPermission: Boolean, equipped: Boolean): ItemStack {
        val mat = if (hasPermission) msg.material else Material.GRAY_DYE
        val item = ItemStack(mat)
        item.editMeta { meta ->
            val nameColor = when {
                equipped -> NamedTextColor.GREEN
                hasPermission -> NamedTextColor.YELLOW
                else -> NamedTextColor.DARK_GRAY
            }
            meta.displayName(
                Component.text(msg.displayName, nameColor)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            val preview = msg.format.replace("{player}", "Player")
            val lore = mutableListOf(
                Component.empty(),
                Component.text(preview, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
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

    // ── Database ────────────────────────────────────────────────────

    private fun loadPlayer(uuid: UUID) {
        plugin.databaseManager.queryFirst(
            "SELECT effect_id, message_id FROM player_join_effects WHERE uuid = ?",
            uuid.toString()
        ) { rs ->
            rs.getString("effect_id")?.let { equippedEffects[uuid] = it }
            rs.getString("message_id")?.let { equippedMessages[uuid] = it }
        }
    }

    private fun savePlayer(uuid: UUID) {
        val effectId = equippedEffects[uuid]
        val messageId = equippedMessages[uuid]

        if (effectId == null && messageId == null) {
            plugin.databaseManager.execute(
                "DELETE FROM player_join_effects WHERE uuid = ?",
                uuid.toString()
            )
        } else {
            plugin.databaseManager.execute(
                "INSERT OR REPLACE INTO player_join_effects (uuid, effect_id, message_id) VALUES (?, ?, ?)",
                uuid.toString(), effectId, messageId
            )
        }
    }

    fun evictCache(uuid: UUID) {
        equippedEffects.remove(uuid)
        equippedMessages.remove(uuid)
    }

    // ── Effect registration ─────────────────────────────────────────

    private fun registerEffects() {
        effects.add(JoinEffect("firework_launch", "Firework Launch", Material.FIREWORK_ROCKET,
            "Launch a firework at your location") { p -> playFireworkLaunch(p) })
        effects.add(JoinEffect("lightning_entry", "Lightning Entry", Material.LIGHTNING_ROD,
            "Strike lightning where you stand") { p -> playLightningEntry(p) })
        effects.add(JoinEffect("ender_teleport", "Ender Teleport Swirl", Material.ENDER_PEARL,
            "Swirl of ender particles around you") { p -> playEnderTeleport(p) })
        effects.add(JoinEffect("wither_spawn", "Wither Spawn", Material.WITHER_SKELETON_SKULL,
            "Dark wither particles surround you") { p -> playWitherSpawn(p) })
        effects.add(JoinEffect("dragon_landing", "Dragon Landing", Material.DRAGON_EGG,
            "Dragon breath cloud at your feet") { p -> playDragonLanding(p) })
        effects.add(JoinEffect("nether_portal", "Nether Portal", Material.OBSIDIAN,
            "Portal particles swirl around you") { p -> playNetherPortal(p) })
        effects.add(JoinEffect("flame_burst", "Flame Burst", Material.BLAZE_POWDER,
            "Burst of flames around you") { p -> playFlameBurst(p) })
        effects.add(JoinEffect("snow_storm", "Snow Storm", Material.SNOWBALL,
            "Snow particles fall around you") { p -> playSnowStorm(p) })
        effects.add(JoinEffect("cherry_blossom", "Cherry Blossom Shower", Material.CHERRY_LEAVES,
            "Pink petals shower down") { p -> playCherryBlossom(p) })
        effects.add(JoinEffect("totem_burst", "Totem Burst", Material.TOTEM_OF_UNDYING,
            "Totem of undying effect") { p -> playTotemBurst(p) })
        effects.add(JoinEffect("beacon_beam", "Beacon Beam", Material.BEACON,
            "Bright beam particles upward") { p -> playBeaconBeam(p) })
        effects.add(JoinEffect("soul_ascend", "Soul Ascend", Material.SOUL_LANTERN,
            "Soul fire particles rise upward") { p -> playSoulAscend(p) })
        effects.add(JoinEffect("sculk_wave", "Sculk Wave", Material.SCULK,
            "Sculk particles spread outward") { p -> playSculkWave(p) })
        effects.add(JoinEffect("enchant_spiral", "Enchant Spiral", Material.ENCHANTING_TABLE,
            "Enchantment glyphs spiral around you") { p -> playEnchantSpiral(p) })
        effects.add(JoinEffect("thunder_crash", "Thunder Crash", Material.TRIDENT,
            "Thunder sound with electric particles") { p -> playThunderCrash(p) })
        effects.add(JoinEffect("phoenix_rise", "Phoenix Rise", Material.BLAZE_ROD,
            "Flames and gold particles rise upward") { p -> playPhoenixRise(p) })
        effects.add(JoinEffect("ice_freeze", "Ice Freeze", Material.BLUE_ICE,
            "Frozen particles expand outward") { p -> playIceFreeze(p) })
        effects.add(JoinEffect("lava_eruption", "Lava Eruption", Material.LAVA_BUCKET,
            "Lava particles erupt from below") { p -> playLavaEruption(p) })
        effects.add(JoinEffect("star_fall", "Star Fall", Material.NETHER_STAR,
            "Glowing stars rain down around you") { p -> playStarFall(p) })
        effects.add(JoinEffect("rainbow_burst", "Rainbow Burst", Material.PRISMARINE_SHARD,
            "Colorful dust particles burst outward") { p -> playRainbowBurst(p) })
    }

    private fun registerMessages() {
        messages.add(JoinMessage("lightning", "\u26A1 {player} has arrived \u26A1", Material.YELLOW_WOOL, "Lightning Arrival"))
        messages.add(JoinMessage("star", "\u2605 {player} joined the game \u2605", Material.GOLD_NUGGET, "Star Join"))
        messages.add(JoinMessage("fire", "\uD83D\uDD25 {player} is here \uD83D\uDD25", Material.FIRE_CHARGE, "Fire Entry"))
        messages.add(JoinMessage("crown", "\uD83D\uDC51 {player} has entered the realm \uD83D\uDC51", Material.GOLDEN_HELMET, "Royal Entry"))
        messages.add(JoinMessage("sword", "\u2694 {player} entered the battlefield \u2694", Material.IRON_SWORD, "Battlefield"))
        messages.add(JoinMessage("ice", "\u2744 {player} froze into existence \u2744", Material.ICE, "Frozen Entry"))
        messages.add(JoinMessage("skull", "\uD83D\uDC80 {player} rose from the dead \uD83D\uDC80", Material.SKELETON_SKULL, "Undead Rise"))
        messages.add(JoinMessage("blossom", "\uD83C\uDF38 {player} blossomed in \uD83C\uDF38", Material.PINK_PETALS, "Blossom"))
        messages.add(JoinMessage("moon", "\uD83C\uDF19 {player} emerged from the shadows \uD83C\uDF19", Material.ECHO_SHARD, "Shadow Entry"))
        messages.add(JoinMessage("diamond", "\uD83D\uDC8E {player} spawned in style \uD83D\uDC8E", Material.DIAMOND, "Diamond Style"))
        messages.add(JoinMessage("music", "\uD83C\uDFB5 {player} joined the party \uD83C\uDFB5", Material.JUKEBOX, "Party Join"))
        messages.add(JoinMessage("dragon", "\uD83D\uDC09 {player} descended from above \uD83D\uDC09", Material.DRAGON_HEAD, "Dragon Descent"))
        messages.add(JoinMessage("warp", "\u2B50 {player} warped in \u2B50", Material.END_PORTAL_FRAME, "Warp Entry"))
        messages.add(JoinMessage("ocean", "\uD83C\uDF0A {player} surfaced \uD83C\uDF0A", Material.HEART_OF_THE_SEA, "Ocean Surface"))
        messages.add(JoinMessage("crystal", "\uD83D\uDD2E {player} materialized \uD83D\uDD2E", Material.AMETHYST_SHARD, "Crystal Materialize"))
    }

    // ── Effect implementations ──────────────────────────────────────

    private fun playFireworkLaunch(player: Player) {
        val loc = player.location
        val firework = loc.world.spawn(loc, Firework::class.java)
        val meta = firework.fireworkMeta
        meta.addEffect(
            FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.AQUA, Color.PURPLE, Color.YELLOW)
                .withFade(Color.WHITE)
                .flicker(true)
                .trail(true)
                .build()
        )
        meta.power = 1
        firework.fireworkMeta = meta
    }

    private fun playLightningEntry(player: Player) {
        val loc = player.location
        loc.world.strikeLightningEffect(loc)
        loc.world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f)
    }

    private fun playEnderTeleport(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val angle = tick * 18.0 * Math.PI / 180.0
                val radius = 1.5
                for (i in 0..2) {
                    val a = angle + i * (2.0 * Math.PI / 3.0)
                    val x = cos(a) * radius
                    val z = sin(a) * radius
                    player.world.spawnParticle(Particle.PORTAL, loc.clone().add(x, 1.0, z), 5, 0.1, 0.3, 0.1, 0.0)
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playWitherSpawn(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.2f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.SMOKE, loc.clone().add(0.0, 1.0, 0.0), 15, 0.8, 1.0, 0.8, 0.02)
                player.world.spawnParticle(Particle.SOUL, loc.clone().add(0.0, 0.5, 0.0), 3, 0.5, 0.5, 0.5, 0.01)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playDragonLanding(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.DRAGON_BREATH, loc.clone().add(0.0, 0.2, 0.0), 10, 1.5, 0.1, 1.5, 0.01)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playNetherPortal(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.BLOCK_PORTAL_TRAVEL, 0.3f, 1.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val angle = tick * 18.0 * Math.PI / 180.0
                for (i in 0..3) {
                    val a = angle + i * (Math.PI / 2.0)
                    val x = cos(a) * 1.2
                    val z = sin(a) * 1.2
                    player.world.spawnParticle(Particle.PORTAL, loc.clone().add(x, 1.0, z), 5, 0.1, 0.5, 0.1, 0.0)
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playFlameBurst(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.FLAME, loc.clone().add(0.0, 1.0, 0.0), 10, 0.5, 0.5, 0.5, 0.05)
                if (tick % 5 == 0) {
                    player.world.spawnParticle(Particle.LAVA, loc.clone().add(0.0, 0.5, 0.0), 3, 0.3, 0.1, 0.3, 0.0)
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playSnowStorm(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.ENTITY_SNOW_GOLEM_AMBIENT, 0.8f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0.0, 3.0, 0.0), 15, 2.0, 0.5, 2.0, 0.02)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playCherryBlossom(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.BLOCK_CHERRY_LEAVES_BREAK, 1.0f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.CHERRY_LEAVES, loc.clone().add(0.0, 3.0, 0.0), 10, 2.0, 0.5, 2.0, 0.01)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playTotemBurst(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.ITEM_TOTEM_USE, 0.8f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.TOTEM_OF_UNDYING, loc.clone().add(0.0, 1.0, 0.0), 15, 0.5, 1.0, 0.5, 0.3)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playBeaconBeam(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val y = tick * 0.3
                player.world.spawnParticle(Particle.END_ROD, loc.clone().add(0.0, y, 0.0), 5, 0.1, 0.0, 0.1, 0.0)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playSoulAscend(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.PARTICLE_SOUL_ESCAPE, 1.0f, 0.8f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0.0, 0.5, 0.0), 8, 0.4, 0.2, 0.4, 0.02)
                player.world.spawnParticle(Particle.SOUL, loc.clone().add(0.0, 1.0, 0.0), 3, 0.3, 0.5, 0.3, 0.01)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playSculkWave(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.5f, 1.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val radius = tick * 0.2
                for (i in 0..7) {
                    val angle = i * (Math.PI / 4.0)
                    val x = cos(angle) * radius
                    val z = sin(angle) * radius
                    player.world.spawnParticle(Particle.SCULK_CHARGE_POP, loc.clone().add(x, 0.2, z), 2, 0.05, 0.05, 0.05, 0.0)
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playEnchantSpiral(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val angle = tick * 36.0 * Math.PI / 180.0
                val radius = 1.0
                val y = tick * 0.1
                val x = cos(angle) * radius
                val z = sin(angle) * radius
                player.world.spawnParticle(Particle.ENCHANT, loc.clone().add(x, y, z), 10, 0.1, 0.1, 0.1, 0.5)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playThunderCrash(player: Player) {
        val loc = player.location
        loc.world.strikeLightningEffect(loc)
        loc.world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 0.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.ELECTRIC_SPARK, loc.clone().add(0.0, 1.0, 0.0), 10, 1.0, 1.0, 1.0, 0.05)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playPhoenixRise(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.ENTITY_BLAZE_AMBIENT, 0.8f, 1.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val y = tick * 0.15
                player.world.spawnParticle(Particle.FLAME, loc.clone().add(0.0, y, 0.0), 8, 0.3, 0.1, 0.3, 0.02)
                player.world.spawnParticle(Particle.DUST, loc.clone().add(0.0, y + 0.5, 0.0), 5,
                    0.2, 0.1, 0.2, 0.0, Particle.DustOptions(Color.fromRGB(255, 200, 0), 1.0f))
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playIceFreeze(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val radius = tick * 0.15
                for (i in 0..5) {
                    val angle = i * (Math.PI / 3.0) + tick * 0.1
                    val x = cos(angle) * radius
                    val z = sin(angle) * radius
                    player.world.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(x, 0.5, z), 3, 0.05, 0.2, 0.05, 0.0)
                }
                player.world.spawnParticle(Particle.DUST, loc.clone().add(0.0, 1.0, 0.0), 3,
                    0.5, 0.5, 0.5, 0.0, Particle.DustOptions(Color.fromRGB(150, 220, 255), 1.2f))
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playLavaEruption(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.BLOCK_LAVA_POP, 1.0f, 0.8f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.LAVA, loc.clone().add(0.0, 0.2, 0.0), 5, 0.5, 0.0, 0.5, 0.0)
                player.world.spawnParticle(Particle.DUST, loc.clone().add(0.0, tick * 0.15, 0.0), 5,
                    0.3, 0.1, 0.3, 0.0, Particle.DustOptions(Color.fromRGB(255, 80, 0), 1.5f))
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playStarFall(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.END_ROD, loc.clone().add(0.0, 4.0, 0.0), 8, 2.0, 0.0, 2.0, 0.05)
                player.world.spawnParticle(Particle.FIREWORK, loc.clone().add(0.0, 2.0, 0.0), 5, 1.5, 1.0, 1.5, 0.01)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playRainbowBurst(player: Player) {
        val loc = player.location
        loc.world.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f)
        val colors = listOf(
            Color.RED, Color.fromRGB(255, 127, 0), Color.YELLOW,
            Color.GREEN, Color.AQUA, Color.BLUE, Color.PURPLE
        )
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val color = colors[tick % colors.size]
                val radius = 0.5 + tick * 0.1
                for (i in 0..11) {
                    val angle = i * (Math.PI / 6.0)
                    val x = cos(angle) * radius
                    val z = sin(angle) * radius
                    player.world.spawnParticle(Particle.DUST, loc.clone().add(x, 1.0, z), 1,
                        0.0, 0.0, 0.0, 0.0, Particle.DustOptions(color, 1.2f))
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }
}
