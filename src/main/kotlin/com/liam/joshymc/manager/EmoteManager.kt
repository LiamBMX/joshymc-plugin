package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

class EmoteManager(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    data class Emote(
        val id: String,
        val displayName: String,
        val description: String,
        val runner: (Player) -> Unit
    )

    private val emotes = mutableListOf<Emote>()
    private val cooldowns = ConcurrentHashMap<UUID, Long>()

    companion object {
        private const val COOLDOWN_MS = 5000L
    }

    fun start() {
        registerEmotes()

        plugin.getCommand("emote")?.let {
            it.setExecutor(this)
            it.tabCompleter = this
        }

        plugin.logger.info("[Emote] Registered ${emotes.size} emotes.")
    }

    // ── Command ─────────────────────────────────────────────────────

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Only players can use emotes.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty() || args[0].equals("list", ignoreCase = true)) {
            val available = emotes.filter { sender.hasPermission("joshymc.emote.${it.id}") }
            val locked = emotes.filter { !sender.hasPermission("joshymc.emote.${it.id}") }
            sender.sendMessage(Component.text("--- Emotes ---", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
            if (available.isNotEmpty()) {
                sender.sendMessage(Component.text("Available:", NamedTextColor.GREEN))
                for (emote in available) {
                    sender.sendMessage(
                        Component.text("  /emote ${emote.id}", NamedTextColor.YELLOW)
                            .append(Component.text(" — ${emote.description}", NamedTextColor.GRAY))
                    )
                }
            }
            if (locked.isNotEmpty()) {
                sender.sendMessage(Component.text("Locked (${locked.size}):", NamedTextColor.RED))
                for (emote in locked) {
                    sender.sendMessage(
                        Component.text("  ${emote.id}", NamedTextColor.DARK_GRAY)
                            .append(Component.text(" — ${emote.description}", NamedTextColor.DARK_GRAY))
                    )
                }
            }
            return true
        }

        val emoteId = args[0].lowercase()
        val emote = emotes.find { it.id == emoteId }

        if (emote == null) {
            sender.sendMessage(
                Component.text("Unknown emote: ", NamedTextColor.RED)
                    .append(Component.text(emoteId, NamedTextColor.YELLOW))
            )
            return true
        }

        if (!sender.hasPermission("joshymc.emote.${emote.id}")) {
            sender.sendMessage(Component.text("You don't have permission to use this emote.", NamedTextColor.RED))
            return true
        }

        // Cooldown check
        val now = System.currentTimeMillis()
        val lastUse = cooldowns[sender.uniqueId] ?: 0L
        val remaining = COOLDOWN_MS - (now - lastUse)
        if (remaining > 0) {
            val seconds = (remaining / 1000.0).let { "%.1f".format(it) }
            sender.sendMessage(
                Component.text("Emote on cooldown! ", NamedTextColor.RED)
                    .append(Component.text("${seconds}s remaining", NamedTextColor.GRAY))
            )
            return true
        }

        cooldowns[sender.uniqueId] = now
        emote.runner(sender)
        sender.sendMessage(
            Component.text("Playing emote: ", NamedTextColor.GREEN)
                .append(Component.text(emote.displayName, NamedTextColor.GOLD))
        )
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        val prefix = args[0].lowercase()
        val ids = emotes
            .filter { sender.hasPermission("joshymc.emote.${it.id}") }
            .map { it.id } + "list"
        return ids.filter { it.startsWith(prefix) }
    }

    // ── Emote registration ──────────────────────────────────────────

    private fun registerEmotes() {
        emotes.add(Emote("wave", "Wave", "Particle hand wave above head") { p -> playWave(p) })
        emotes.add(Emote("gg", "GG", "GG spelled in particles") { p -> playGG(p) })
        emotes.add(Emote("heart", "Heart", "Heart shape above player") { p -> playHeart(p) })
        emotes.add(Emote("thumbsup", "Thumbs Up", "Thumbs up in particles") { p -> playThumbsUp(p) })
        emotes.add(Emote("clap", "Clap", "Rapid small bursts") { p -> playClap(p) })
        emotes.add(Emote("shrug", "Shrug", "Particles spreading outward") { p -> playShrug(p) })
        emotes.add(Emote("flex", "Flex", "Expanding ring") { p -> playFlex(p) })
        emotes.add(Emote("rage", "Rage", "Flame burst upward") { p -> playRage(p) })
        emotes.add(Emote("cry", "Cry", "Water drip particles falling") { p -> playCry(p) })
        emotes.add(Emote("laugh", "Laugh", "Note particles bouncing") { p -> playLaugh(p) })
        emotes.add(Emote("dance", "Dance", "Spinning particle ring") { p -> playDance(p) })
        emotes.add(Emote("firework", "Firework", "Firework explosion above") { p -> playFirework(p) })
        emotes.add(Emote("lightning", "Lightning", "Bolt from sky") { p -> playLightning(p) })
        emotes.add(Emote("tornado", "Tornado", "Spinning particle column") { p -> playTornado(p) })
        emotes.add(Emote("explosion", "Explosion", "Expanding sphere") { p -> playExplosion(p) })
        emotes.add(Emote("confetti", "Confetti", "Multicolored dust particles falling") { p -> playConfetti(p) })
        emotes.add(Emote("snow", "Snow", "Snowflakes falling around") { p -> playSnow(p) })
        emotes.add(Emote("fire_ring", "Fire Ring", "Ring of flame at feet") { p -> playFireRing(p) })
        emotes.add(Emote("bubble", "Bubble", "Rising bubbles") { p -> playBubble(p) })
        emotes.add(Emote("sparkle", "Sparkle", "Enchantment particles everywhere") { p -> playSparkle(p) })
        emotes.add(Emote("dragon", "Dragon", "Dragon breath cloud") { p -> playDragon(p) })
        emotes.add(Emote("rainbow", "Rainbow", "Arcing rainbow particles") { p -> playRainbow(p) })
        emotes.add(Emote("skull", "Skull", "Skull shaped smoke") { p -> playSkull(p) })
        emotes.add(Emote("crown", "Crown", "Gold particles in crown shape above head") { p -> playCrown(p) })
        emotes.add(Emote("wings", "Wings", "Wing-shaped particles behind player") { p -> playWings(p) })
    }

    // ── Emote implementations ───────────────────────────────────────

    private fun playWave(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val xOffset = (tick - 10) * 0.1
                val yOffset = 2.5 + sin(tick * 0.5) * 0.3
                player.world.spawnParticle(Particle.END_ROD, loc.clone().add(xOffset, yOffset, 0.0), 2, 0.05, 0.05, 0.05, 0.0)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playGG(player: Player) {
        val loc = player.location.clone().add(0.0, 2.5, 0.0)
        player.world.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f)
        // Spell out "GG" with particles in a single burst
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val dir = player.location.direction.setY(0).normalize()
                val right = dir.clone().crossProduct(org.bukkit.util.Vector(0, 1, 0)).normalize()
                val up = org.bukkit.util.Vector(0, 1, 0)

                // Simple G shape points (left G)
                val gPoints = listOf(
                    0.3 to 0.0, 0.2 to 0.0, 0.1 to 0.0, 0.0 to 0.0,
                    0.0 to 0.1, 0.0 to 0.2, 0.0 to 0.3,
                    0.1 to 0.3, 0.2 to 0.3, 0.3 to 0.3,
                    0.3 to 0.2, 0.3 to 0.15, 0.2 to 0.15
                )

                for ((rx, uy) in gPoints) {
                    // First G (offset left)
                    val p1 = loc.clone().add(right.clone().multiply(rx - 0.4)).add(up.clone().multiply(uy))
                    player.world.spawnParticle(Particle.DUST, p1, 1, 0.0, 0.0, 0.0, 0.0,
                        Particle.DustOptions(Color.YELLOW, 0.8f))
                    // Second G (offset right)
                    val p2 = loc.clone().add(right.clone().multiply(rx + 0.1)).add(up.clone().multiply(uy))
                    player.world.spawnParticle(Particle.DUST, p2, 1, 0.0, 0.0, 0.0, 0.0,
                        Particle.DustOptions(Color.YELLOW, 0.8f))
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    private fun playHeart(player: Player) {
        val loc = player.location.clone().add(0.0, 2.5, 0.0)
        player.world.playSound(player.location, Sound.ENTITY_CAT_PURREOW, 0.8f, 1.2f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 30 || !player.isOnline) { cancel(); return }
                // Heart parametric curve
                for (i in 0..15) {
                    val t = i * (2.0 * Math.PI / 16.0)
                    val x = 0.3 * (16.0 * sin(t) * sin(t) * sin(t)) / 16.0
                    val y = 0.3 * (13.0 * cos(t) - 5.0 * cos(2 * t) - 2.0 * cos(3 * t) - cos(4 * t)) / 16.0
                    player.world.spawnParticle(Particle.DUST, loc.clone().add(x, y, 0.0), 1,
                        0.0, 0.0, 0.0, 0.0, Particle.DustOptions(Color.RED, 1.0f))
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playThumbsUp(player: Player) {
        val loc = player.location.clone().add(0.0, 2.5, 0.0)
        player.world.playSound(player.location, Sound.ENTITY_VILLAGER_YES, 0.8f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 25 || !player.isOnline) { cancel(); return }
                // Thumb (vertical line) and base (horizontal)
                for (i in 0..4) {
                    player.world.spawnParticle(Particle.DUST, loc.clone().add(0.0, i * 0.12, 0.0), 1,
                        0.0, 0.0, 0.0, 0.0, Particle.DustOptions(Color.YELLOW, 1.0f))
                }
                for (i in -2..2) {
                    player.world.spawnParticle(Particle.DUST, loc.clone().add(i * 0.1, -0.1, 0.0), 1,
                        0.0, 0.0, 0.0, 0.0, Particle.DustOptions(Color.YELLOW, 1.0f))
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playClap(player: Player) {
        val loc = player.location
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                if (tick % 4 == 0) {
                    player.world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.6f, 1.5f)
                    player.world.spawnParticle(Particle.CRIT, loc.clone().add(0.0, 1.8, 0.0), 15, 0.3, 0.2, 0.3, 0.1)
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playShrug(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_VILLAGER_AMBIENT, 0.8f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val spread = tick * 0.15
                player.world.spawnParticle(Particle.CLOUD, loc.clone().add(spread, 1.8, 0.0), 2, 0.05, 0.05, 0.05, 0.0)
                player.world.spawnParticle(Particle.CLOUD, loc.clone().add(-spread, 1.8, 0.0), 2, 0.05, 0.05, 0.05, 0.0)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playFlex(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_IRON_GOLEM_REPAIR, 0.8f, 1.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val radius = tick * 0.15
                for (i in 0..11) {
                    val angle = i * (Math.PI / 6.0)
                    val x = cos(angle) * radius
                    val z = sin(angle) * radius
                    player.world.spawnParticle(Particle.DUST, loc.clone().add(x, 1.0, z), 1,
                        0.0, 0.0, 0.0, 0.0, Particle.DustOptions(Color.ORANGE, 1.2f))
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playRage(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.3f, 2.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 25 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.FLAME, loc.clone().add(0.0, 2.0, 0.0), 10, 0.3, 0.3, 0.3, 0.08)
                player.world.spawnParticle(Particle.LAVA, loc.clone().add(0.0, 2.2, 0.0), 2, 0.2, 0.1, 0.2, 0.0)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playCry(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_GHAST_HURT, 0.3f, 1.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 30 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.DRIPPING_WATER, loc.clone().add(0.15, 2.0, 0.0), 1, 0.0, 0.0, 0.0, 0.0)
                player.world.spawnParticle(Particle.DRIPPING_WATER, loc.clone().add(-0.15, 2.0, 0.0), 1, 0.0, 0.0, 0.0, 0.0)
                player.world.spawnParticle(Particle.FALLING_WATER, loc.clone().add(0.15, 1.8, 0.0), 1, 0.05, 0.1, 0.05, 0.0)
                player.world.spawnParticle(Particle.FALLING_WATER, loc.clone().add(-0.15, 1.8, 0.0), 1, 0.05, 0.1, 0.05, 0.0)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playLaugh(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_VILLAGER_CELEBRATE, 0.8f, 1.2f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 25 || !player.isOnline) { cancel(); return }
                val y = 2.2 + sin(tick * 0.5) * 0.3
                player.world.spawnParticle(Particle.NOTE, loc.clone().add(0.0, y, 0.0), 2, 0.3, 0.1, 0.3, 0.5)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playDance(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 40 || !player.isOnline) { cancel(); return }
                val angle = tick * 18.0 * Math.PI / 180.0
                val radius = 1.2
                val x = cos(angle) * radius
                val z = sin(angle) * radius
                val color = Color.fromRGB(
                    (127 + 127 * sin(tick * 0.3)).toInt().coerceIn(0, 255),
                    (127 + 127 * sin(tick * 0.3 + 2.0)).toInt().coerceIn(0, 255),
                    (127 + 127 * sin(tick * 0.3 + 4.0)).toInt().coerceIn(0, 255)
                )
                player.world.spawnParticle(Particle.DUST, loc.clone().add(x, 0.5, z), 1,
                    0.0, 0.0, 0.0, 0.0, Particle.DustOptions(color, 1.0f))
                player.world.spawnParticle(Particle.NOTE, loc.clone().add(0.0, 2.2, 0.0), 1, 0.3, 0.1, 0.3, 0.5)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playFirework(player: Player) {
        val loc = player.location.clone().add(0.0, 3.0, 0.0)
        player.world.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val radius = tick * 0.15
                for (i in 0..15) {
                    val angle = i * (Math.PI / 8.0)
                    val x = cos(angle) * radius
                    val z = sin(angle) * radius
                    player.world.spawnParticle(Particle.FIREWORK, loc.clone().add(x, 0.0, z), 1, 0.0, 0.0, 0.0, 0.0)
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playLightning(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 1.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val y = 5.0 - tick * 0.25
                player.world.spawnParticle(Particle.ELECTRIC_SPARK, loc.clone().add(0.0, y, 0.0), 5, 0.1, 0.0, 0.1, 0.0)
                if (tick > 15) {
                    player.world.spawnParticle(Particle.END_ROD, loc.clone().add(0.0, 1.0, 0.0), 10, 0.5, 0.5, 0.5, 0.1)
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playTornado(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_PHANTOM_FLAP, 0.8f, 0.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 40 || !player.isOnline) { cancel(); return }
                for (y in 0..3) {
                    val radius = 0.3 + y * 0.3
                    val angle = tick * 20.0 * Math.PI / 180.0 + y * 0.5
                    val x = cos(angle) * radius
                    val z = sin(angle) * radius
                    player.world.spawnParticle(Particle.CLOUD, loc.clone().add(x, y * 0.5, z), 2, 0.05, 0.05, 0.05, 0.0)
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playExplosion(player: Player) {
        val loc = player.location.clone().add(0.0, 1.0, 0.0)
        player.world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.2f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 20 || !player.isOnline) { cancel(); return }
                val radius = tick * 0.2
                for (i in 0..15) {
                    val phi = Math.random() * Math.PI * 2
                    val theta = Math.random() * Math.PI
                    val x = cos(phi) * sin(theta) * radius
                    val y = cos(theta) * radius
                    val z = sin(phi) * sin(theta) * radius
                    player.world.spawnParticle(Particle.SMOKE, loc.clone().add(x, y, z), 1, 0.0, 0.0, 0.0, 0.0)
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playConfetti(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 2.0f)
        val colors = listOf(Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA, Color.BLUE, Color.PURPLE, Color.FUCHSIA)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 30 || !player.isOnline) { cancel(); return }
                for (i in 0..5) {
                    val color = colors.random()
                    player.world.spawnParticle(Particle.DUST, loc.clone().add(0.0, 3.5, 0.0), 1,
                        1.5, 0.5, 1.5, 0.0, Particle.DustOptions(color, 1.0f))
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playSnow(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_SNOW_GOLEM_AMBIENT, 0.8f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 30 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0.0, 3.0, 0.0), 10, 2.0, 0.3, 2.0, 0.01)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playFireRing(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ITEM_FIRECHARGE_USE, 0.8f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 30 || !player.isOnline) { cancel(); return }
                for (i in 0..11) {
                    val angle = (i * 30.0 + tick * 10.0) * Math.PI / 180.0
                    val x = cos(angle) * 1.5
                    val z = sin(angle) * 1.5
                    player.world.spawnParticle(Particle.FLAME, loc.clone().add(x, 0.1, z), 1, 0.0, 0.05, 0.0, 0.0)
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playBubble(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.8f, 1.2f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 30 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.BUBBLE_POP, loc.clone().add(0.0, 0.5, 0.0), 5, 0.5, 0.3, 0.5, 0.05)
                player.world.spawnParticle(Particle.DUST, loc.clone().add(0.0, 1.0 + tick * 0.05, 0.0), 3,
                    0.3, 0.1, 0.3, 0.0, Particle.DustOptions(Color.fromRGB(100, 200, 255), 0.8f))
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playSparkle(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 25 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.ENCHANT, loc.clone().add(0.0, 1.0, 0.0), 20, 1.0, 1.0, 1.0, 1.0)
                player.world.spawnParticle(Particle.END_ROD, loc.clone().add(0.0, 1.5, 0.0), 3, 0.5, 0.5, 0.5, 0.02)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playDragon(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.3f, 1.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 25 || !player.isOnline) { cancel(); return }
                player.world.spawnParticle(Particle.DRAGON_BREATH, loc.clone().add(0.0, 1.0, 0.0), 10, 1.0, 0.5, 1.0, 0.02)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playRainbow(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f)
        val colors = listOf(Color.RED, Color.fromRGB(255, 127, 0), Color.YELLOW, Color.GREEN, Color.AQUA, Color.BLUE, Color.PURPLE)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 30 || !player.isOnline) { cancel(); return }
                for ((ci, color) in colors.withIndex()) {
                    val t = tick * 0.05 + ci * 0.15
                    val x = (ci - 3) * 0.4
                    val y = 2.0 + sin(t * Math.PI) * 1.5 - ci * 0.05
                    player.world.spawnParticle(Particle.DUST, loc.clone().add(x, y, 0.0), 1,
                        0.0, 0.0, 0.0, 0.0, Particle.DustOptions(color, 1.0f))
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playSkull(player: Player) {
        val loc = player.location.clone().add(0.0, 2.5, 0.0)
        player.world.playSound(player.location, Sound.ENTITY_WITHER_AMBIENT, 0.3f, 1.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 25 || !player.isOnline) { cancel(); return }
                // Skull outline: circle for head + jaw points
                for (i in 0..11) {
                    val angle = i * (Math.PI / 6.0)
                    val x = cos(angle) * 0.4
                    val y = sin(angle) * 0.5
                    player.world.spawnParticle(Particle.SMOKE, loc.clone().add(x, y, 0.0), 1, 0.02, 0.02, 0.02, 0.0)
                }
                // Eyes
                player.world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(-0.15, 0.15, 0.0), 1, 0.02, 0.02, 0.02, 0.0)
                player.world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0.15, 0.15, 0.0), 1, 0.02, 0.02, 0.02, 0.0)
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playCrown(player: Player) {
        val loc = player.location.clone().add(0.0, 2.5, 0.0)
        player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.0f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 30 || !player.isOnline) { cancel(); return }
                // Crown: base ring + 5 points
                for (i in 0..11) {
                    val angle = i * (Math.PI / 6.0)
                    val x = cos(angle) * 0.4
                    val z = sin(angle) * 0.4
                    player.world.spawnParticle(Particle.DUST, loc.clone().add(x, 0.0, z), 1,
                        0.0, 0.0, 0.0, 0.0, Particle.DustOptions(Color.fromRGB(255, 215, 0), 0.8f))
                }
                // Points
                for (i in 0..4) {
                    val angle = i * (2.0 * Math.PI / 5.0)
                    val x = cos(angle) * 0.35
                    val z = sin(angle) * 0.35
                    player.world.spawnParticle(Particle.DUST, loc.clone().add(x, 0.25, z), 1,
                        0.0, 0.0, 0.0, 0.0, Particle.DustOptions(Color.fromRGB(255, 215, 0), 1.0f))
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playWings(player: Player) {
        val loc = player.location
        player.world.playSound(loc, Sound.ENTITY_PHANTOM_FLAP, 0.8f, 1.5f)
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                if (tick >= 30 || !player.isOnline) { cancel(); return }
                val dir = player.location.direction.setY(0).normalize()
                val right = dir.clone().crossProduct(org.bukkit.util.Vector(0, 1, 0)).normalize()
                val behind = dir.clone().multiply(-0.3)

                val flapOffset = sin(tick * 0.3) * 0.2

                // Wing points — each side
                for (i in 1..5) {
                    val spread = i * 0.25
                    val height = 1.3 + (0.3 - (i * 0.05)) + flapOffset * i * 0.1
                    val back = behind.clone().multiply(i * 0.3)

                    // Right wing
                    val rp = loc.clone().add(right.clone().multiply(spread)).add(back).add(0.0, height, 0.0)
                    player.world.spawnParticle(Particle.DUST, rp, 1, 0.0, 0.0, 0.0, 0.0,
                        Particle.DustOptions(Color.WHITE, 1.0f))

                    // Left wing
                    val lp = loc.clone().add(right.clone().multiply(-spread)).add(back).add(0.0, height, 0.0)
                    player.world.spawnParticle(Particle.DUST, lp, 1, 0.0, 0.0, 0.0, 0.0,
                        Particle.DustOptions(Color.WHITE, 1.0f))
                }
                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }
}
