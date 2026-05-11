package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ══════════════════════════════════════════════════════════
//  /back — teleport to last known position
// ══════════════════════════════════════════════════════════

class BackCommand(private val plugin: Joshymc) : CommandExecutor {

    companion object {
        val lastLocations = ConcurrentHashMap<UUID, Location>()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.back")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val loc = lastLocations[sender.uniqueId]
        if (loc == null) {
            plugin.commsManager.send(sender, Component.text("No previous location found.", NamedTextColor.RED))
            return true
        }

        if (TeleportChecks.checkAndApply(sender, plugin)) return true

        TeleportChecks.teleportWithWarmup(sender, loc, plugin)
        return true
    }
}

/** Listener that tracks last location on teleport and death */
class BackLocationListener(private val plugin: Joshymc) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        BackCommand.lastLocations[event.player.uniqueId] = event.from
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        BackCommand.lastLocations[event.player.uniqueId] = event.player.location
    }
}

// ══════════════════════════════════════════════════════════
//  /gamemode, /gmc, /gms, /gma, /gmsp
// ══════════════════════════════════════════════════════════

class GamemodeCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.gamemode")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        // Shorthand commands: /gmc, /gms, /gma, /gmsp
        val mode = when (label.lowercase()) {
            "gmc" -> GameMode.CREATIVE
            "gms" -> GameMode.SURVIVAL
            "gma" -> GameMode.ADVENTURE
            "gmsp" -> GameMode.SPECTATOR
            else -> {
                // /gamemode <mode> [player]
                val modeArg = args.getOrNull(0)?.lowercase()
                parseGameMode(modeArg) ?: run {
                    sender.sendMessage(Component.text("Usage: /gamemode <c|s|a|sp> [player]", NamedTextColor.RED))
                    return true
                }
            }
        }

        // Target player
        val targetName = when (label.lowercase()) {
            "gmc", "gms", "gma", "gmsp" -> args.getOrNull(0)
            else -> args.getOrNull(1)
        }

        val target: Player = if (targetName != null) {
            if (!sender.hasPermission("joshymc.gamemode.others")) {
                sender.sendMessage(Component.text("No permission to change others' gamemode.", NamedTextColor.RED))
                return true
            }
            Bukkit.getPlayer(targetName) ?: run {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                return true
            }
        } else if (sender is Player) {
            sender
        } else {
            sender.sendMessage(Component.text("Specify a player.", NamedTextColor.RED))
            return true
        }

        target.gameMode = mode
        val modeName = mode.name.lowercase().replaceFirstChar { it.uppercase() }

        if (target == sender) {
            plugin.commsManager.send(target, Component.text("Gamemode set to $modeName.", NamedTextColor.GREEN))
        } else {
            plugin.commsManager.send(target, Component.text("Your gamemode was set to $modeName.", NamedTextColor.GREEN))
            if (sender is Player) {
                plugin.commsManager.send(sender, Component.text("Set ${target.name}'s gamemode to $modeName.", NamedTextColor.GREEN))
            }
        }
        return true
    }

    private fun parseGameMode(input: String?): GameMode? {
        return when (input) {
            "c", "creative", "1" -> GameMode.CREATIVE
            "s", "survival", "0" -> GameMode.SURVIVAL
            "a", "adventure", "2" -> GameMode.ADVENTURE
            "sp", "spectator", "3" -> GameMode.SPECTATOR
            else -> null
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val lowerAlias = alias.lowercase()
        return when {
            lowerAlias in setOf("gmc", "gms", "gma", "gmsp") && args.size == 1 ->
                Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
            lowerAlias == "gamemode" && args.size == 1 ->
                listOf("creative", "survival", "adventure", "spectator", "c", "s", "a", "sp")
                    .filter { it.startsWith(args[0], ignoreCase = true) }
            lowerAlias == "gamemode" && args.size == 2 ->
                Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
            else -> emptyList()
        }
    }
}

// ══════════════════════════════════════════════════════════
//  /fly
// ══════════════════════════════════════════════════════════

class FlyCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.fly")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val target = if (args.isNotEmpty()) {
            Bukkit.getPlayer(args[0]) ?: run {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                return true
            }
        } else if (sender is Player) sender else {
            sender.sendMessage(Component.text("Specify a player.", NamedTextColor.RED))
            return true
        }

        // Block combat-tagged players (or staff trying to enable fly on a
        // tagged target) from using fly to escape PvP.
        if (plugin.combatManager.isTagged(target)) {
            plugin.commsManager.send(
                if (sender is Player) sender else target,
                Component.text("Can't toggle flight while in combat.", NamedTextColor.RED)
            )
            return true
        }

        target.allowFlight = !target.allowFlight
        target.isFlying = target.allowFlight

        val state = if (target.allowFlight) "enabled" else "disabled"
        val color = if (target.allowFlight) NamedTextColor.GREEN else NamedTextColor.RED
        plugin.commsManager.send(target, Component.text("Flight $state.", color))
        if (target != sender && sender is Player) {
            plugin.commsManager.send(sender, Component.text("Flight $state for ${target.name}.", color))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /heal
// ══════════════════════════════════════════════════════════

class HealCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        // Per-player cooldown for self-heal. Staff who use /heal <other> are
        // not rate-limited, and `joshymc.heal.bypass` skips the cooldown.
        val cooldowns = ConcurrentHashMap<UUID, Long>()
        const val COOLDOWN_MS = 3 * 60 * 1000L
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.heal")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val target = resolveTarget(sender, args) ?: return true

        // Block /heal on combat-tagged players to remove the heal-mid-fight
        // exploit. Staff still need to wait until the combat tag clears.
        if (plugin.combatManager.isTagged(target)) {
            plugin.commsManager.send(
                if (sender is Player) sender else target,
                Component.text("Can't heal a player while they're in combat.", NamedTextColor.RED)
            )
            return true
        }

        // Cooldown — only when player heals themselves.
        if (sender is Player && sender == target && !sender.hasPermission("joshymc.heal.bypass")) {
            val now = System.currentTimeMillis()
            val last = cooldowns[sender.uniqueId] ?: 0L
            val remaining = (last + COOLDOWN_MS) - now
            if (remaining > 0) {
                plugin.commsManager.send(
                    sender,
                    Component.text("/heal is on cooldown — ${formatRemaining(remaining)} left.", NamedTextColor.RED)
                )
                return true
            }
            cooldowns[sender.uniqueId] = now
        }

        val maxHealth = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
        target.health = maxHealth
        target.foodLevel = 20
        target.saturation = 20f
        target.fireTicks = 0
        // Remove negative effects
        target.activePotionEffects
            .filter { isNegativeEffect(it.type) }
            .forEach { target.removePotionEffect(it.type) }

        plugin.commsManager.send(target, Component.text("You have been healed.", NamedTextColor.GREEN))
        if (target != sender && sender is Player) {
            plugin.commsManager.send(sender, Component.text("Healed ${target.name}.", NamedTextColor.GREEN))
        }
        return true
    }

    private fun isNegativeEffect(type: PotionEffectType): Boolean {
        return type == PotionEffectType.POISON || type == PotionEffectType.WITHER
                || type == PotionEffectType.BLINDNESS || type == PotionEffectType.NAUSEA
                || type == PotionEffectType.HUNGER || type == PotionEffectType.WEAKNESS
                || type == PotionEffectType.SLOWNESS || type == PotionEffectType.MINING_FATIGUE
                || type == PotionEffectType.LEVITATION || type == PotionEffectType.DARKNESS
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /feed
// ══════════════════════════════════════════════════════════

class FeedCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        val cooldowns = ConcurrentHashMap<UUID, Long>()
        const val COOLDOWN_MS = 3 * 60 * 1000L
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.feed")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val target = resolveTarget(sender, args) ?: return true

        if (sender is Player && sender == target && !sender.hasPermission("joshymc.feed.bypass")) {
            val now = System.currentTimeMillis()
            val last = cooldowns[sender.uniqueId] ?: 0L
            val remaining = (last + COOLDOWN_MS) - now
            if (remaining > 0) {
                plugin.commsManager.send(
                    sender,
                    Component.text("/feed is on cooldown — ${formatRemaining(remaining)} left.", NamedTextColor.RED)
                )
                return true
            }
            cooldowns[sender.uniqueId] = now
        }

        target.foodLevel = 20
        target.saturation = 20f

        plugin.commsManager.send(target, Component.text("You have been fed.", NamedTextColor.GREEN))
        if (target != sender && sender is Player) {
            plugin.commsManager.send(sender, Component.text("Fed ${target.name}.", NamedTextColor.GREEN))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /god — toggle invincibility
// ══════════════════════════════════════════════════════════

class GodCommand(private val plugin: Joshymc) : CommandExecutor, Listener, TabCompleter {

    val godPlayers = ConcurrentHashMap.newKeySet<UUID>()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.god")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val target = resolveTarget(sender, args) ?: return true

        val toggled = if (godPlayers.contains(target.uniqueId)) {
            godPlayers.remove(target.uniqueId)
            false
        } else {
            godPlayers.add(target.uniqueId)
            true
        }

        val state = if (toggled) "enabled" else "disabled"
        val color = if (toggled) NamedTextColor.GREEN else NamedTextColor.RED
        plugin.commsManager.send(target, Component.text("God mode $state.", color))
        if (target != sender && sender is Player) {
            plugin.commsManager.send(sender, Component.text("God mode $state for ${target.name}.", color))
        }
        return true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDamage(event: org.bukkit.event.entity.EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (godPlayers.contains(player.uniqueId)) {
            event.isCancelled = true
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /speed — set walk or fly speed
// ══════════════════════════════════════════════════════════

class SpeedCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.speed")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val speedVal = args.getOrNull(0)?.toFloatOrNull()
        if (speedVal == null || speedVal < 0 || speedVal > 10) {
            plugin.commsManager.send(sender, Component.text("Usage: /speed <0-10> [fly|walk]", NamedTextColor.RED))
            return true
        }

        val type = args.getOrNull(1)?.lowercase()
        val normalized = (speedVal / 10f).coerceIn(0f, 1f)

        when (type) {
            "fly", "f" -> {
                sender.flySpeed = normalized
                plugin.commsManager.send(sender, Component.text("Fly speed set to $speedVal.", NamedTextColor.GREEN))
            }
            "walk", "w" -> {
                sender.walkSpeed = normalized
                plugin.commsManager.send(sender, Component.text("Walk speed set to $speedVal.", NamedTextColor.GREEN))
            }
            else -> {
                if (sender.isFlying) {
                    sender.flySpeed = normalized
                    plugin.commsManager.send(sender, Component.text("Fly speed set to $speedVal.", NamedTextColor.GREEN))
                } else {
                    sender.walkSpeed = normalized
                    plugin.commsManager.send(sender, Component.text("Walk speed set to $speedVal.", NamedTextColor.GREEN))
                }
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("1", "2", "3", "5", "10").filter { it.startsWith(args[0]) }
            2 -> listOf("fly", "walk").filter { it.startsWith(args[1], ignoreCase = true) }
            else -> emptyList()
        }
    }
}

// ══════════════════════════════════════════════════════════
//  /tp — teleport to player or coords
// ══════════════════════════════════════════════════════════

class TpCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.tp")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        when (args.size) {
            1 -> {
                // /tp <player>
                val target = Bukkit.getPlayer(args[0]) ?: run {
                    plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
                    return true
                }
                BackCommand.lastLocations[sender.uniqueId] = sender.location
                sender.teleport(target)
                plugin.commsManager.send(sender, Component.text("Teleported to ${target.name}.", NamedTextColor.GREEN))
            }
            2 -> {
                // /tp <player1> <player2>
                if (!sender.hasPermission("joshymc.tp.others")) {
                    plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
                    return true
                }
                val p1 = Bukkit.getPlayer(args[0])
                val p2 = Bukkit.getPlayer(args[1])
                if (p1 == null || p2 == null) {
                    plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
                    return true
                }
                BackCommand.lastLocations[p1.uniqueId] = p1.location
                p1.teleport(p2)
                plugin.commsManager.send(sender, Component.text("Teleported ${p1.name} to ${p2.name}.", NamedTextColor.GREEN))
            }
            3 -> {
                // /tp <x> <y> <z>
                val x = args[0].toDoubleOrNull()
                val y = args[1].toDoubleOrNull()
                val z = args[2].toDoubleOrNull()
                if (x == null || y == null || z == null) {
                    plugin.commsManager.send(sender, Component.text("Invalid coordinates.", NamedTextColor.RED))
                    return true
                }
                BackCommand.lastLocations[sender.uniqueId] = sender.location
                sender.teleport(Location(sender.world, x, y, z, sender.location.yaw, sender.location.pitch))
                plugin.commsManager.send(sender, Component.text("Teleported to ${"%.0f".format(x)}, ${"%.0f".format(y)}, ${"%.0f".format(z)}.", NamedTextColor.GREEN))
            }
            else -> {
                plugin.commsManager.send(sender, Component.text("Usage: /tp <player> | /tp <x> <y> <z>", NamedTextColor.RED))
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1, 2 -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args.last(), ignoreCase = true) }
            else -> emptyList()
        }
    }
}

// ══════════════════════════════════════════════════════════
//  /tphere — teleport player to you
// ══════════════════════════════════════════════════════════

class TpHereCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.tphere")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("Usage: /tphere <player>", NamedTextColor.RED))
            return true
        }
        val target = Bukkit.getPlayer(args[0]) ?: run {
            plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
            return true
        }
        BackCommand.lastLocations[target.uniqueId] = target.location
        target.teleport(sender)
        plugin.commsManager.send(sender, Component.text("Teleported ${target.name} to you.", NamedTextColor.GREEN))
        plugin.commsManager.send(target, Component.text("You were teleported to ${sender.name}.", NamedTextColor.GREEN))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /invsee — view another player's inventory
// ══════════════════════════════════════════════════════════

class InvseeCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.invsee")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("Usage: /invsee <player>", NamedTextColor.RED))
            return true
        }
        val target = Bukkit.getPlayer(args[0]) ?: run {
            plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
            return true
        }
        sender.openInventory(target.inventory)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /enderchest — open your or another player's ender chest
// ══════════════════════════════════════════════════════════

class EnderchestCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.enderchest")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        val target = if (args.isNotEmpty() && sender.hasPermission("joshymc.enderchest.others")) {
            Bukkit.getPlayer(args[0]) ?: run {
                plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
                return true
            }
        } else sender

        sender.openInventory(target.enderChest)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /hat — put held item on your head
// ══════════════════════════════════════════════════════════

class HatCommand(private val plugin: Joshymc) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.hat")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        val item = sender.inventory.itemInMainHand
        if (item.type == Material.AIR) {
            plugin.commsManager.send(sender, Component.text("Hold an item to wear as a hat.", NamedTextColor.RED))
            return true
        }
        val currentHelmet = sender.inventory.helmet
        sender.inventory.helmet = item.clone()
        sender.inventory.setItemInMainHand(currentHelmet ?: ItemStack(Material.AIR))
        plugin.commsManager.send(sender, Component.text("Hat equipped!", NamedTextColor.GREEN))
        return true
    }
}

// ══════════════════════════════════════════════════════════
//  /craft — open crafting table anywhere
// ══════════════════════════════════════════════════════════

class CraftCommand(private val plugin: Joshymc) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.craft")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        sender.openWorkbench(null, true)
        return true
    }
}

// ══════════════════════════════════════════════════════════
//  /anvil — open anvil anywhere
// ══════════════════════════════════════════════════════════

class AnvilCommand(private val plugin: Joshymc) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.anvil")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        sender.openAnvil(null, true)
        return true
    }
}

// ══════════════════════════════════════════════════════════
//  /smithing — open a smithing table anywhere
// ══════════════════════════════════════════════════════════

class SmithingCommand(private val plugin: Joshymc) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.smithing")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        // Bukkit doesn't expose openSmithingTable directly; use the inventory
        // type. The portable=true flag matches /craft and /anvil behaviour.
        @Suppress("DEPRECATION")
        val view = sender.openInventory(
            org.bukkit.Bukkit.createInventory(sender, org.bukkit.event.inventory.InventoryType.SMITHING)
        )
        return true
    }
}

// ══════════════════════════════════════════════════════════
//  /repair — repair the held item, or all worn equipment with `all`
// ══════════════════════════════════════════════════════════

class RepairCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.repair")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        // Combat-tagged players can't use /repair to fix gear mid-fight.
        if (plugin.combatManager.isTagged(sender)) {
            plugin.commsManager.send(sender, Component.text("Can't repair while in combat.", NamedTextColor.RED))
            return true
        }

        val mode = args.getOrNull(0)?.lowercase() ?: "hand"
        var repaired = 0
        when (mode) {
            "hand" -> if (repairItem(sender.inventory.itemInMainHand)) repaired++
            "all" -> {
                for (item in sender.inventory.contents) {
                    if (repairItem(item)) repaired++
                }
                for (armor in sender.inventory.armorContents) {
                    if (armor != null && repairItem(armor)) repaired++
                }
                if (repairItem(sender.inventory.itemInOffHand)) repaired++
            }
            else -> {
                plugin.commsManager.send(sender, Component.text("Usage: /repair [hand|all]", NamedTextColor.RED))
                return true
            }
        }

        if (repaired == 0) {
            plugin.commsManager.send(sender, Component.text("Nothing to repair.", NamedTextColor.GRAY))
        } else {
            plugin.commsManager.send(sender, Component.text("Repaired $repaired item${if (repaired != 1) "s" else ""}.", NamedTextColor.GREEN))
            sender.playSound(sender.location, org.bukkit.Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f)
        }
        return true
    }

    /** Reset durability on a Damageable item. Returns true if it changed. */
    private fun repairItem(item: org.bukkit.inventory.ItemStack?): Boolean {
        if (item == null || item.type.isAir) return false
        val meta = item.itemMeta ?: return false
        if (meta !is org.bukkit.inventory.meta.Damageable) return false
        if (meta.damage <= 0) return false
        meta.damage = 0
        item.itemMeta = meta
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return listOf("hand", "all").filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /smite — strike lightning at target player
// ══════════════════════════════════════════════════════════

class SmiteCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.smite")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty()) {
            // Strike at sender's crosshair
            if (sender !is Player) { sender.sendMessage("Specify a player."); return true }
            val target = sender.getTargetBlockExact(100)
            if (target != null) sender.world.strikeLightning(target.location)
            return true
        }
        val target = Bukkit.getPlayer(args[0]) ?: run {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
            return true
        }
        target.world.strikeLightning(target.location)
        if (sender is Player) plugin.commsManager.send(sender, Component.text("Struck ${target.name} with lightning.", NamedTextColor.GREEN))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /top — teleport to highest block
// ══════════════════════════════════════════════════════════

class TopCommand(private val plugin: Joshymc) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.top")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (TeleportChecks.checkAndApply(sender, plugin)) return true

        val highest = sender.world.getHighestBlockAt(sender.location)
        val dest = highest.location.add(0.5, 1.0, 0.5).apply { yaw = sender.location.yaw; pitch = sender.location.pitch }
        TeleportChecks.teleportWithWarmup(sender, dest, plugin)
        return true
    }
}

// ══════════════════════════════════════════════════════════
//  /sudo — force a player to run a command
// ══════════════════════════════════════════════════════════

class SudoCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.sudo")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /sudo <player> <command...>", NamedTextColor.RED))
            return true
        }
        val target = Bukkit.getPlayer(args[0]) ?: run {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
            return true
        }
        val cmd = args.drop(1).joinToString(" ")
        target.performCommand(cmd)
        if (sender is Player) plugin.commsManager.send(sender, Component.text("Forced ${target.name} to run: /$cmd", NamedTextColor.GREEN))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /msg + /reply — private messaging
// ══════════════════════════════════════════════════════════

class MsgCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        val lastMessaged = ConcurrentHashMap<UUID, UUID>() // sender -> target for /r
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (args.size < 2) {
            plugin.commsManager.send(sender, Component.text("Usage: /msg <player> <message>", NamedTextColor.RED))
            return true
        }
        val target = Bukkit.getPlayer(args[0]) ?: run {
            plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
            return true
        }
        val message = args.drop(1).joinToString(" ")
        sender.sendMessage(Component.text("[me → ${target.name}] ", NamedTextColor.GRAY).append(Component.text(message, NamedTextColor.WHITE)))
        target.sendMessage(Component.text("[${sender.name} → me] ", NamedTextColor.GRAY).append(Component.text(message, NamedTextColor.WHITE)))
        lastMessaged[sender.uniqueId] = target.uniqueId
        lastMessaged[target.uniqueId] = sender.uniqueId
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

class ReplyCommand(private val plugin: Joshymc) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (args.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("Usage: /r <message>", NamedTextColor.RED))
            return true
        }
        val targetUuid = MsgCommand.lastMessaged[sender.uniqueId]
        if (targetUuid == null) {
            plugin.commsManager.send(sender, Component.text("No one to reply to.", NamedTextColor.RED))
            return true
        }
        val target = Bukkit.getPlayer(targetUuid) ?: run {
            plugin.commsManager.send(sender, Component.text("That player is offline.", NamedTextColor.RED))
            return true
        }
        val message = args.joinToString(" ")
        sender.sendMessage(Component.text("[me → ${target.name}] ", NamedTextColor.GRAY).append(Component.text(message, NamedTextColor.WHITE)))
        target.sendMessage(Component.text("[${sender.name} → me] ", NamedTextColor.GRAY).append(Component.text(message, NamedTextColor.WHITE)))
        MsgCommand.lastMessaged[sender.uniqueId] = target.uniqueId
        MsgCommand.lastMessaged[target.uniqueId] = sender.uniqueId
        return true
    }
}

// ══════════════════════════════════════════════════════════
//  Utility — resolve target player from args
// ══════════════════════════════════════════════════════════

// ══════════════════════════════════════════════════════════
//  Teleport cooldown + combat check utilities
// ══════════════════════════════════════════════════════════

object TeleportChecks {
    private val teleportCooldowns = ConcurrentHashMap<UUID, Long>()
    private val pendingTeleports = ConcurrentHashMap<UUID, Int>() // player -> task ID
    private const val TP_COOLDOWN_MS = 3000L
    private const val WARMUP_TICKS = 60L // 3 seconds

    /**
     * Returns true if the player should be blocked from teleporting (combat or cooldown).
     */
    fun checkAndApply(player: Player, plugin: com.liam.joshymc.Joshymc): Boolean {
        // Frozen check
        if (plugin.adminManager.isFrozen(player.uniqueId)) {
            plugin.commsManager.send(player, Component.text("You are frozen and cannot teleport!", NamedTextColor.RED))
            return true
        }

        // Combat check
        if (plugin.combatManager.isTagged(player)) {
            plugin.commsManager.send(player, Component.text("You cannot teleport while in combat!", NamedTextColor.RED))
            return true
        }

        if (!player.hasPermission("joshymc.tp.nocooldown")) {
            val now = System.currentTimeMillis()
            val lastTp = teleportCooldowns.getOrDefault(player.uniqueId, 0L)
            val remaining = TP_COOLDOWN_MS - (now - lastTp)
            if (remaining > 0) {
                plugin.commsManager.send(player, Component.text("Teleport cooldown: ${(remaining / 1000) + 1}s remaining.", NamedTextColor.RED))
                return true
            }
        }

        return false
    }

    /**
     * Schedule a teleport with a 3-second warmup. Player must stand still.
     * If they move, teleport is cancelled.
     * Bypass warmup with joshymc.tp.nocooldown permission.
     */
    fun teleportWithWarmup(player: Player, destination: Location, plugin: com.liam.joshymc.Joshymc, onComplete: (() -> Unit)? = null) {
        // Bypass warmup for staff
        if (player.hasPermission("joshymc.tp.nocooldown")) {
            BackCommand.lastLocations[player.uniqueId] = player.location
            player.teleport(destination)
            teleportCooldowns[player.uniqueId] = System.currentTimeMillis()
            onComplete?.invoke()
            return
        }

        // Cancel any existing pending teleport
        pendingTeleports.remove(player.uniqueId)?.let { Bukkit.getScheduler().cancelTask(it) }

        val startLoc = player.location.clone()
        plugin.commsManager.send(player, Component.text("Teleporting in 3 seconds... don't move!", NamedTextColor.YELLOW))

        // Show countdown
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
            if (!player.isOnline) return@scheduleSyncDelayedTask
            if (hasMoved(player, startLoc)) { cancelWarmup(player, plugin); return@scheduleSyncDelayedTask }
            plugin.commsManager.send(player, Component.text("Teleporting in 2...", NamedTextColor.YELLOW))
        }, 20L)

        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
            if (!player.isOnline) return@scheduleSyncDelayedTask
            if (hasMoved(player, startLoc)) { cancelWarmup(player, plugin); return@scheduleSyncDelayedTask }
            plugin.commsManager.send(player, Component.text("Teleporting in 1...", NamedTextColor.YELLOW))
        }, 40L)

        val taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
            pendingTeleports.remove(player.uniqueId)
            if (!player.isOnline) return@scheduleSyncDelayedTask
            if (hasMoved(player, startLoc)) { cancelWarmup(player, plugin); return@scheduleSyncDelayedTask }
            if (plugin.combatManager.isTagged(player)) {
                plugin.commsManager.send(player, Component.text("Teleport cancelled — you entered combat!", NamedTextColor.RED))
                return@scheduleSyncDelayedTask
            }

            BackCommand.lastLocations[player.uniqueId] = player.location
            player.teleport(destination)
            teleportCooldowns[player.uniqueId] = System.currentTimeMillis()
            plugin.commsManager.send(player, Component.text("Teleported!", NamedTextColor.GREEN))
            player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.2f)
            onComplete?.invoke()
        }, WARMUP_TICKS)

        pendingTeleports[player.uniqueId] = taskId
    }

    private fun hasMoved(player: Player, start: Location): Boolean {
        val loc = player.location
        return loc.blockX != start.blockX || loc.blockY != start.blockY || loc.blockZ != start.blockZ
    }

    private fun cancelWarmup(player: Player, plugin: com.liam.joshymc.Joshymc) {
        pendingTeleports.remove(player.uniqueId)
        plugin.commsManager.send(player, Component.text("Teleport cancelled — you moved!", NamedTextColor.RED))
    }
}

// ══════════════════════════════════════════════════════════
//  /trash — disposable inventory
// ══════════════════════════════════════════════════════════

class TrashCommand(private val plugin: Joshymc) : CommandExecutor, Listener {

    companion object {
        private const val TRASH_TITLE = "&c&lTrash"
    }

    private val trashTitle: Component by lazy { plugin.commsManager.parseLegacy(TRASH_TITLE) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.trash")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val inv = Bukkit.createInventory(null, 54, trashTitle)
        sender.openInventory(inv)
        return true
    }

    @EventHandler
    fun onClose(event: org.bukkit.event.inventory.InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val view = event.view
        if (view.title() != trashTitle) return

        // Items are already gone (virtual inventory)
        player.playSound(player.location, Sound.BLOCK_LAVA_EXTINGUISH, 0.5f, 1.2f)
        plugin.commsManager.send(player, Component.text("Trash emptied.", NamedTextColor.GRAY))
    }
}

private fun formatRemaining(ms: Long): String {
    val totalSeconds = (ms + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private fun resolveTarget(sender: CommandSender, args: Array<out String>): Player? {
    return if (args.isNotEmpty()) {
        Bukkit.getPlayer(args[0]) ?: run {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
            null
        }
    } else if (sender is Player) {
        sender
    } else {
        sender.sendMessage(Component.text("Specify a player.", NamedTextColor.RED))
        null
    }
}
