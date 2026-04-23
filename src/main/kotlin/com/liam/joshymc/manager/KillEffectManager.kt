package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.*
import org.bukkit.entity.EntityType
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class KillEffectManager(private val plugin: Joshymc) : Listener {

    data class KillEffect(
        val id: String,
        val name: String,
        val permission: String,
        val category: String,
        val icon: Material,
        val color: String
    )

    fun canUse(player: Player, effect: KillEffect): Boolean {
        if (player.hasPermission("joshymc.killeffect.*")) return true
        if (player.hasPermission("joshymc.killeffect.category.${effect.category.lowercase()}")) return true
        return player.hasPermission(effect.permission)
    }

    private val effects = mutableListOf<KillEffect>()
    private val equipped = ConcurrentHashMap<UUID, String>()

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }
    private val BORDER = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }
    private val LOCKED_PANE = ItemStack(Material.RED_STAINED_GLASS_PANE).apply {
        editMeta { meta ->
            meta.displayName(
                Component.text("Locked", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.text("You don't have permission", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ))
        }
    }

    private val GUI_TITLE = Component.text("      ")
        .append(Component.text("K", TextColor.color(0xFF5555)))
        .append(Component.text("i", TextColor.color(0xFF7755)))
        .append(Component.text("l", TextColor.color(0xFF9955)))
        .append(Component.text("l", TextColor.color(0xFFBB55)))
        .append(Component.text(" ", TextColor.color(0xFFDD55)))
        .append(Component.text("E", TextColor.color(0xFFFF55)))
        .append(Component.text("f", TextColor.color(0xDDFF55)))
        .append(Component.text("f", TextColor.color(0xBBFF55)))
        .append(Component.text("e", TextColor.color(0x99FF55)))
        .append(Component.text("c", TextColor.color(0x77FF55)))
        .append(Component.text("t", TextColor.color(0x55FF55)))
        .append(Component.text("s", TextColor.color(0x55FF77)))
        .decoration(TextDecoration.BOLD, true)
        .decoration(TextDecoration.ITALIC, false)

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_kill_effects (
                uuid TEXT PRIMARY KEY,
                effect_id TEXT NOT NULL
            )
        """.trimIndent())

        registerEffects()

        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun registerEffects() {
        // Explosive (5)
        effects += KillEffect("blood_burst", "Blood Burst", "joshymc.killeffect.blood_burst", "explosive", Material.REDSTONE, "#FF0000")
        effects += KillEffect("firework_pop", "Firework Pop", "joshymc.killeffect.firework_pop", "explosive", Material.FIREWORK_ROCKET, "#FF55FF")
        effects += KillEffect("tnt_blast", "TNT Blast", "joshymc.killeffect.tnt_blast", "explosive", Material.TNT, "#FF3300")
        effects += KillEffect("creeper_explosion", "Creeper Explosion", "joshymc.killeffect.creeper_explosion", "explosive", Material.CREEPER_HEAD, "#00FF00")
        effects += KillEffect("wither_skull", "Wither Skull", "joshymc.killeffect.wither_skull", "explosive", Material.WITHER_SKELETON_SKULL, "#333333")

        // Elemental (5)
        effects += KillEffect("lightning_strike", "Lightning Strike", "joshymc.killeffect.lightning_strike", "elemental", Material.LIGHTNING_ROD, "#FFFF55")
        effects += KillEffect("flame_pillar", "Flame Pillar", "joshymc.killeffect.flame_pillar", "elemental", Material.BLAZE_POWDER, "#FF6600")
        effects += KillEffect("ice_shatter", "Ice Shatter", "joshymc.killeffect.ice_shatter", "elemental", Material.ICE, "#AAFFFF")
        effects += KillEffect("thunder_clap", "Thunder Clap", "joshymc.killeffect.thunder_clap", "elemental", Material.BELL, "#FFDD55")
        effects += KillEffect("lava_burst", "Lava Burst", "joshymc.killeffect.lava_burst", "elemental", Material.LAVA_BUCKET, "#FF4400")

        // Soul (5)
        effects += KillEffect("soul_release", "Soul Release", "joshymc.killeffect.soul_release", "soul", Material.SOUL_LANTERN, "#55FFFF")
        effects += KillEffect("ghost_ascend", "Ghost Ascend", "joshymc.killeffect.ghost_ascend", "soul", Material.GHAST_TEAR, "#FFFFFF")
        effects += KillEffect("ender_teleport", "Ender Teleport", "joshymc.killeffect.ender_teleport", "soul", Material.ENDER_PEARL, "#AA00FF")
        effects += KillEffect("totem_pop", "Totem Pop", "joshymc.killeffect.totem_pop", "soul", Material.TOTEM_OF_UNDYING, "#FFD700")
        effects += KillEffect("void_collapse", "Void Collapse", "joshymc.killeffect.void_collapse", "soul", Material.OBSIDIAN, "#110022")

        // Nature (5)
        effects += KillEffect("flower_burst", "Flower Burst", "joshymc.killeffect.flower_burst", "nature", Material.POPPY, "#FF5577")
        effects += KillEffect("leaf_storm", "Leaf Storm", "joshymc.killeffect.leaf_storm", "nature", Material.OAK_LEAVES, "#33AA33")
        effects += KillEffect("spore_cloud", "Spore Cloud", "joshymc.killeffect.spore_cloud", "nature", Material.BROWN_MUSHROOM, "#886644")
        effects += KillEffect("cherry_rain", "Cherry Rain", "joshymc.killeffect.cherry_rain", "nature", Material.CHERRY_LEAVES, "#FFB7C5")
        effects += KillEffect("bee_swarm", "Bee Swarm", "joshymc.killeffect.bee_swarm", "nature", Material.HONEYCOMB, "#FFCC00")

        // Dark (5)
        effects += KillEffect("smoke_screen", "Smoke Screen", "joshymc.killeffect.smoke_screen", "dark", Material.CAMPFIRE, "#555555")
        effects += KillEffect("wither_cloud", "Wither Cloud", "joshymc.killeffect.wither_cloud", "dark", Material.WITHER_ROSE, "#222222")
        effects += KillEffect("sculk_shriek", "Sculk Shriek", "joshymc.killeffect.sculk_shriek", "dark", Material.SCULK_SHRIEKER, "#003344")
        effects += KillEffect("shadow_burst", "Shadow Burst", "joshymc.killeffect.shadow_burst", "dark", Material.BLACK_WOOL, "#111111")
        effects += KillEffect("obsidian_shatter", "Obsidian Shatter", "joshymc.killeffect.obsidian_shatter", "dark", Material.CRYING_OBSIDIAN, "#6600AA")

        // Epic (5)
        effects += KillEffect("dragon_breath_burst", "Dragon Breath Burst", "joshymc.killeffect.dragon_breath_burst", "epic", Material.DRAGON_BREATH, "#FF00FF")
        effects += KillEffect("beacon_blast", "Beacon Blast", "joshymc.killeffect.beacon_blast", "epic", Material.BEACON, "#55FFFF")
        effects += KillEffect("enchant_swirl", "Enchant Swirl", "joshymc.killeffect.enchant_swirl", "epic", Material.ENCHANTING_TABLE, "#AA77FF")
        effects += KillEffect("prism_shatter", "Prism Shatter", "joshymc.killeffect.prism_shatter", "epic", Material.PRISMARINE_SHARD, "#77DDDD")
        effects += KillEffect("nether_portal_burst", "Nether Portal Burst", "joshymc.killeffect.nether_portal_burst", "epic", Material.OBSIDIAN, "#7700CC")
    }

    // ── Database ──────────────────────────────────────────

    fun getEquippedEffect(uuid: UUID): String? {
        return equipped.getOrPut(uuid) {
            plugin.databaseManager.queryFirst(
                "SELECT effect_id FROM player_kill_effects WHERE uuid = ?",
                uuid.toString()
            ) { it.getString("effect_id") } ?: return null
        }
    }

    fun setEquippedEffect(uuid: UUID, effectId: String) {
        equipped[uuid] = effectId
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO player_kill_effects (uuid, effect_id) VALUES (?, ?)",
            uuid.toString(), effectId
        )
    }

    fun clearEquippedEffect(uuid: UUID) {
        equipped.remove(uuid)
        plugin.databaseManager.execute(
            "DELETE FROM player_kill_effects WHERE uuid = ?",
            uuid.toString()
        )
    }

    fun evictCache(uuid: UUID) {
        equipped.remove(uuid)
    }

    // ── Kill Listener ─────────────────────────────────────

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        val effectId = getEquippedEffect(killer.uniqueId) ?: return
        val effect = effects.find { it.id == effectId } ?: return

        if (!canUse(killer, effect)) return

        playEffect(effectId, event.entity.location)
    }

    // ── GUI ───────────────────────────────────────────────

    fun openEffectMenu(player: Player) {
        // 6 rows = 54 slots. 30 effects fit in rows 1-4 (columns 1-7 = 28) + overflow to row 5
        val gui = CustomGui(GUI_TITLE, 54)

        // Fill + border
        for (i in 0 until 54) gui.inventory.setItem(i, FILLER.clone())
        gui.border(BORDER.clone())

        val currentEffect = getEquippedEffect(player.uniqueId)

        // Place effects in the interior (rows 1-4, cols 1-7)
        val slots = mutableListOf<Int>()
        for (row in 1..4) {
            for (col in 1..7) {
                slots.add(row * 9 + col)
            }
        }
        // Row 4 has 7 slots but we only need 2 more (28 slots for rows 1-3 = 21, need 30 total)
        // Actually: rows 1-4 cols 1-7 = 28 slots, need 2 more from row 4 already included
        // 4 rows * 7 cols = 28; we need 30, so also use 2 slots from a 5th position
        // Let's add 2 more from row 5 center
        slots.add(5 * 9 + 3)
        slots.add(5 * 9 + 5)

        // Unequip button at bottom center
        val unequipSlot = 5 * 9 + 4
        val unequipItem = ItemStack(Material.BARRIER).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("Remove Effect", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                meta.lore(listOf(
                    Component.text("Click to unequip your kill effect", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                ))
            }
        }
        gui.setItem(unequipSlot, unequipItem) { p, _ ->
            clearEquippedEffect(p.uniqueId)
            p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f)
            plugin.commsManager.send(p, Component.text("Kill effect removed.", NamedTextColor.RED))
            p.closeInventory()
        }

        for ((index, effect) in effects.withIndex()) {
            if (index >= slots.size) break
            val slot = slots[index]
            val hasPermission = canUse(player, effect)
            val isEquipped = currentEffect == effect.id

            if (!hasPermission) {
                gui.setItem(slot, LOCKED_PANE.clone()) { p, _ ->
                    p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                }
                continue
            }

            val item = buildEffectItem(effect, isEquipped)
            gui.setItem(slot, item) { p, _ ->
                setEquippedEffect(p.uniqueId, effect.id)
                p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 2.0f)
                plugin.commsManager.send(
                    p,
                    Component.text("Kill effect set to ", NamedTextColor.GREEN)
                        .append(Component.text(effect.name, TextColor.color(parseHexColor(effect.color)))
                            .decoration(TextDecoration.BOLD, true))
                )
                p.closeInventory()
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun buildEffectItem(effect: KillEffect, equipped: Boolean): ItemStack {
        val item = ItemStack(effect.icon)
        val meta = item.itemMeta!!
        val color = TextColor.color(parseHexColor(effect.color))

        meta.displayName(
            Component.text(effect.name, color)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true)
        )

        val status = if (equipped) {
            Component.text("  EQUIPPED", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true)
        } else {
            Component.text("  Click to equip", NamedTextColor.GRAY)
        }

        meta.lore(listOf(
            Component.empty(),
            status.decoration(TextDecoration.ITALIC, false),
        ))

        if (equipped) {
            meta.setEnchantmentGlintOverride(true)
        }

        item.itemMeta = meta
        return item
    }

    private fun parseHexColor(hex: String): Int {
        return Integer.parseInt(hex.removePrefix("#"), 16)
    }

    // ── Effect Playback ───────────────────────────────────

    private fun playEffect(effectId: String, loc: Location) {
        val world = loc.world ?: return
        val center = loc.clone().add(0.0, 0.5, 0.0)

        when (effectId) {
            "blood_burst" -> scheduleParticles(center, Particle.DUST, 50, 0.5, 0.5, 0.5,
                data = Particle.DustOptions(Color.RED, 1.5f), ticks = 5)

            "firework_pop" -> {
                val fw = world.spawnEntity(center, EntityType.FIREWORK_ROCKET) as Firework
                val fwMeta = fw.fireworkMeta
                fwMeta.addEffect(
                    FireworkEffect.builder()
                        .with(FireworkEffect.Type.BALL_LARGE)
                        .withColor(Color.RED, Color.ORANGE, Color.YELLOW)
                        .withFade(Color.WHITE)
                        .flicker(true)
                        .build()
                )
                fwMeta.power = 0
                fw.fireworkMeta = fwMeta
                fw.detonate()
            }

            "tnt_blast" -> {
                world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f)
                scheduleParticles(center, Particle.EXPLOSION, 5, 0.3, 0.3, 0.3, ticks = 3)
                scheduleParticles(center, Particle.SMOKE, 30, 0.5, 0.5, 0.5, ticks = 5)
            }

            "creeper_explosion" -> {
                world.playSound(center, Sound.ENTITY_CREEPER_DEATH, 1.0f, 1.0f)
                scheduleParticles(center, Particle.EXPLOSION, 3, 0.2, 0.2, 0.2, ticks = 3)
                scheduleParticles(center, Particle.HAPPY_VILLAGER, 30, 0.6, 0.6, 0.6, ticks = 6)
            }

            "wither_skull" -> {
                world.playSound(center, Sound.ENTITY_WITHER_SHOOT, 0.8f, 0.8f)
                scheduleParticles(center, Particle.SMOKE, 40, 0.4, 0.4, 0.4, ticks = 6)
                scheduleParticles(center, Particle.ASH, 30, 0.5, 0.8, 0.5, ticks = 8)
            }

            "lightning_strike" -> {
                world.strikeLightningEffect(center)
            }

            "flame_pillar" -> {
                for (tick in 0..9) {
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        val y = tick * 0.3
                        val pillarLoc = center.clone().add(0.0, y, 0.0)
                        world.spawnParticle(Particle.FLAME, pillarLoc, 10, 0.15, 0.05, 0.15, 0.02)
                    }, tick.toLong())
                }
            }

            "ice_shatter" -> {
                world.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.5f)
                scheduleParticles(center, Particle.SNOWFLAKE, 40, 0.5, 0.5, 0.5, ticks = 6)
                scheduleParticles(center, Particle.DUST, 20, 0.4, 0.4, 0.4,
                    data = Particle.DustOptions(Color.fromRGB(170, 255, 255), 1.0f), ticks = 8)
            }

            "thunder_clap" -> {
                world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.2f)
                scheduleParticles(center, Particle.FLASH, 2, 0.0, 0.0, 0.0, ticks = 2)
                scheduleParticles(center, Particle.ELECTRIC_SPARK, 30, 0.5, 0.5, 0.5, ticks = 6)
            }

            "lava_burst" -> {
                world.playSound(center, Sound.BLOCK_LAVA_POP, 1.0f, 0.8f)
                scheduleParticles(center, Particle.LAVA, 30, 0.4, 0.3, 0.4, ticks = 7)
                scheduleParticles(center, Particle.DUST, 20, 0.3, 0.5, 0.3,
                    data = Particle.DustOptions(Color.ORANGE, 1.2f), ticks = 5)
            }

            "soul_release" -> {
                for (tick in 0..7) {
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        val riseLoc = center.clone().add(0.0, tick * 0.4, 0.0)
                        world.spawnParticle(Particle.SOUL, riseLoc, 4, 0.2, 0.1, 0.2, 0.01)
                    }, tick.toLong())
                }
            }

            "ghost_ascend" -> {
                world.playSound(center, Sound.ENTITY_VEX_AMBIENT, 0.8f, 0.5f)
                for (tick in 0..9) {
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        val riseLoc = center.clone().add(0.0, tick * 0.3, 0.0)
                        world.spawnParticle(Particle.END_ROD, riseLoc, 5, 0.2, 0.1, 0.2, 0.01)
                    }, tick.toLong())
                }
            }

            "ender_teleport" -> {
                world.playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f)
                scheduleParticles(center, Particle.PORTAL, 60, 0.5, 0.8, 0.5, ticks = 6)
            }

            "totem_pop" -> {
                world.playSound(center, Sound.ITEM_TOTEM_USE, 0.8f, 1.0f)
                scheduleParticles(center, Particle.TOTEM_OF_UNDYING, 50, 0.5, 0.8, 0.5, ticks = 8)
            }

            "void_collapse" -> {
                world.playSound(center, Sound.BLOCK_PORTAL_TRAVEL, 0.3f, 2.0f)
                scheduleParticles(center, Particle.REVERSE_PORTAL, 50, 0.6, 0.6, 0.6, ticks = 8)
                scheduleParticles(center, Particle.DUST, 20, 0.3, 0.3, 0.3,
                    data = Particle.DustOptions(Color.fromRGB(17, 0, 34), 2.0f), ticks = 6)
            }

            "flower_burst" -> {
                world.playSound(center, Sound.BLOCK_AZALEA_LEAVES_BREAK, 1.0f, 1.5f)
                scheduleParticles(center, Particle.CHERRY_LEAVES, 40, 0.6, 0.6, 0.6, ticks = 8)
                scheduleParticles(center, Particle.HAPPY_VILLAGER, 20, 0.5, 0.5, 0.5, ticks = 6)
            }

            "leaf_storm" -> {
                for (tick in 0..7) {
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        val angle = tick * 0.8
                        val offset = center.clone().add(
                            Math.cos(angle) * 0.8, tick * 0.15, Math.sin(angle) * 0.8
                        )
                        world.spawnParticle(Particle.COMPOSTER, offset, 8, 0.2, 0.1, 0.2, 0.02)
                    }, tick.toLong())
                }
            }

            "spore_cloud" -> {
                world.playSound(center, Sound.BLOCK_FUNGUS_BREAK, 1.0f, 0.8f)
                scheduleParticles(center, Particle.SPORE_BLOSSOM_AIR, 40, 0.6, 0.4, 0.6, ticks = 8)
                scheduleParticles(center, Particle.DUST, 15, 0.4, 0.3, 0.4,
                    data = Particle.DustOptions(Color.fromRGB(136, 102, 68), 1.0f), ticks = 6)
            }

            "cherry_rain" -> {
                for (tick in 0..9) {
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        val rainLoc = center.clone().add(0.0, 3.0, 0.0)
                        world.spawnParticle(Particle.CHERRY_LEAVES, rainLoc, 8, 1.0, 0.2, 1.0, 0.01)
                    }, tick.toLong())
                }
            }

            "bee_swarm" -> {
                world.playSound(center, Sound.ENTITY_BEE_LOOP, 0.8f, 1.2f)
                for (tick in 0..9) {
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        val angle = tick * 0.6
                        val swirlLoc = center.clone().add(
                            Math.cos(angle) * 0.7, 0.5 + Math.sin(tick * 0.5) * 0.3, Math.sin(angle) * 0.7
                        )
                        world.spawnParticle(Particle.WAX_ON, swirlLoc, 5, 0.15, 0.15, 0.15, 0.01)
                    }, tick.toLong())
                }
            }

            "smoke_screen" -> {
                world.playSound(center, Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 0.8f)
                scheduleParticles(center, Particle.CAMPFIRE_COSY_SMOKE, 20, 0.4, 0.2, 0.4, ticks = 8)
                scheduleParticles(center, Particle.LARGE_SMOKE, 30, 0.6, 0.4, 0.6, ticks = 6)
            }

            "wither_cloud" -> {
                world.playSound(center, Sound.ENTITY_WITHER_AMBIENT, 0.5f, 0.5f)
                scheduleParticles(center, Particle.SMOKE, 40, 0.5, 0.4, 0.5, ticks = 8)
                scheduleParticles(center, Particle.ASH, 30, 0.6, 0.5, 0.6, ticks = 6)
            }

            "sculk_shriek" -> {
                world.playSound(center, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 1.0f, 1.0f)
                scheduleParticles(center, Particle.SCULK_CHARGE_POP, 30, 0.5, 0.5, 0.5, ticks = 8)
                scheduleParticles(center, Particle.SONIC_BOOM, 1, 0.0, 0.0, 0.0, ticks = 3)
            }

            "shadow_burst" -> {
                world.playSound(center, Sound.ENTITY_PHANTOM_DEATH, 0.8f, 0.6f)
                scheduleParticles(center, Particle.SQUID_INK, 30, 0.5, 0.5, 0.5, ticks = 6)
                scheduleParticles(center, Particle.DUST, 25, 0.6, 0.6, 0.6,
                    data = Particle.DustOptions(Color.fromRGB(17, 17, 17), 1.5f), ticks = 8)
            }

            "obsidian_shatter" -> {
                world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.8f, 0.5f)
                scheduleParticles(center, Particle.DUST, 40, 0.5, 0.5, 0.5,
                    data = Particle.DustOptions(Color.fromRGB(102, 0, 170), 1.5f), ticks = 7)
                scheduleParticles(center, Particle.REVERSE_PORTAL, 20, 0.3, 0.3, 0.3, ticks = 5)
            }

            "dragon_breath_burst" -> {
                world.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.5f)
                for (tick in 0..9) {
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        val angle = tick * 0.63
                        val radius = tick * 0.15
                        val sphereLoc = center.clone().add(
                            Math.cos(angle) * radius, Math.sin(tick * 0.5) * 0.5, Math.sin(angle) * radius
                        )
                        world.spawnParticle(Particle.DRAGON_BREATH, sphereLoc, 6, 0.1, 0.1, 0.1, 0.01)
                    }, tick.toLong())
                }
            }

            "beacon_blast" -> {
                world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 2.0f)
                for (tick in 0..9) {
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        val beamLoc = center.clone().add(0.0, tick * 0.5, 0.0)
                        world.spawnParticle(Particle.END_ROD, beamLoc, 8, 0.1, 0.05, 0.1, 0.01)
                    }, tick.toLong())
                }
            }

            "enchant_swirl" -> {
                world.playSound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.0f)
                for (tick in 0..9) {
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        val angle = tick * 0.7
                        val radius = 0.8
                        val swirlLoc = center.clone().add(
                            Math.cos(angle) * radius, tick * 0.1, Math.sin(angle) * radius
                        )
                        world.spawnParticle(Particle.ENCHANT, swirlLoc, 10, 0.1, 0.1, 0.1, 0.5)
                    }, tick.toLong())
                }
            }

            "prism_shatter" -> {
                world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 1.0f)
                scheduleParticles(center, Particle.DUST, 20, 0.5, 0.5, 0.5,
                    data = Particle.DustOptions(Color.AQUA, 1.2f), ticks = 5)
                scheduleParticles(center, Particle.DUST, 20, 0.5, 0.5, 0.5,
                    data = Particle.DustOptions(Color.TEAL, 1.0f), ticks = 7)
                scheduleParticles(center, Particle.ELECTRIC_SPARK, 15, 0.4, 0.4, 0.4, ticks = 6)
            }

            "nether_portal_burst" -> {
                world.playSound(center, Sound.BLOCK_PORTAL_TRIGGER, 0.5f, 1.5f)
                scheduleParticles(center, Particle.PORTAL, 50, 0.5, 0.8, 0.5, ticks = 8)
                scheduleParticles(center, Particle.DUST, 25, 0.4, 0.6, 0.4,
                    data = Particle.DustOptions(Color.fromRGB(119, 0, 204), 1.5f), ticks = 7)
            }
        }
    }

    /**
     * Spreads particle spawning across multiple ticks for an animated feel.
     */
    private fun scheduleParticles(
        loc: Location,
        particle: Particle,
        totalCount: Int,
        dx: Double, dy: Double, dz: Double,
        speed: Double = 0.02,
        data: Any? = null,
        ticks: Int = 5
    ) {
        val world = loc.world ?: return
        val perTick = (totalCount / ticks).coerceAtLeast(1)

        for (tick in 0 until ticks) {
            val count = if (tick == ticks - 1) totalCount - (perTick * tick) else perTick
            if (count <= 0) continue

            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (data != null) {
                    world.spawnParticle(particle, loc, count, dx, dy, dz, speed, data)
                } else {
                    world.spawnParticle(particle, loc, count, dx, dy, dz, speed)
                }
            }, tick.toLong())
        }
    }
}
