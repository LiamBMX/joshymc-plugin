package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.*
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

class AntiCheatManager(private val plugin: Joshymc) : Listener {

    // ══════════════════════════════════════════════════════════
    //  DATA
    // ══════════════════════════════════════════════════════════

    enum class CheckType(val displayName: String, val configKey: String) {
        FLIGHT("Flight", "flight"),
        SPEED("Speed", "speed"),
        NO_FALL("NoFall", "nofall"),
        JESUS("Jesus", "jesus"),
        PHASE("Phase", "phase"),
        TIMER("Timer", "timer"),
        KILL_AURA("KillAura", "killaura"),
        REACH("Reach", "reach"),
        AUTO_CLICK("AutoClick", "autoclick"),
        VELOCITY("Velocity", "velocity"),
        FAST_BREAK("FastBreak", "fastbreak"),
        FAST_PLACE("FastPlace", "fastplace"),
        SCAFFOLD("Scaffold", "scaffold"),
        NUKER("Nuker", "nuker"),
        BAD_PACKETS("BadPackets", "badpackets"),
        ILLEGAL_ITEMS("IllegalItems", "illegitems"),
        INVENTORY("Inventory", "inventory")
    }

    data class PlayerData(
        val uuid: UUID,
        // Violations
        val violations: MutableMap<CheckType, Double> = mutableMapOf(),
        var lastAlertTime: MutableMap<CheckType, Long> = mutableMapOf(),
        // Movement
        var lastLocation: Location? = null,
        var lastMoveTime: Long = 0L,
        var lastOnGround: Boolean = true,
        var airTicks: Int = 0,
        var lastGroundY: Double = 0.0,
        var lastFallDistance: Float = 0f,
        var teleportTicks: Int = 0,
        // Timer
        var movePacketCount: Int = 0,
        var lastPacketResetTime: Long = 0L,
        // Combat
        var clickTimes: MutableList<Long> = mutableListOf(),
        var lastAttackTime: Long = 0L,
        var attackCountInSecond: Int = 0,
        var lastAttackResetTime: Long = 0L,
        // Velocity
        var pendingKnockback: Boolean = false,
        var knockbackTick: Long = 0L,
        var preKnockbackLocation: Location? = null,
        // Block
        var lastBlockBreakTime: Long = 0L,
        var blocksBrokenThisSecond: Int = 0,
        var lastBreakResetTime: Long = 0L,
        var lastBlockPlaceTime: Long = 0L,
        var blocksPlacedThisSecond: Int = 0,
        var lastPlaceResetTime: Long = 0L,
        // Inventory
        var lastInventoryClickTime: Long = 0L,
        var inventoryClicksThisSecond: Int = 0,
        var lastInvClickResetTime: Long = 0L,
        // Scaffold
        var blocksPlacedBelowFeet: Int = 0,
        var lastScaffoldResetTime: Long = 0L
    )

    // ══════════════════════════════════════════════════════════
    //  STATE
    // ══════════════════════════════════════════════════════════

    private val playerData = ConcurrentHashMap<UUID, PlayerData>()
    private val enabledChecks = mutableSetOf<CheckType>()
    private var enabled = true
    private var alertVL = 20.0
    private var kickVL = 150.0
    private var alertCooldownMs = 3000L
    private var decayTask: BukkitTask? = null

    // ══════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════

    fun start() {
        val cfg = plugin.config
        enabled = cfg.getBoolean("anticheat.enabled", true)
        alertVL = cfg.getDouble("anticheat.alert-vl", 10.0)
        kickVL = cfg.getDouble("anticheat.kick-vl", 50.0)
        alertCooldownMs = cfg.getLong("anticheat.alert-cooldown-ms", 3000L)

        enabledChecks.clear()
        for (check in CheckType.entries) {
            if (cfg.getBoolean("anticheat.checks.${check.configKey}", true)) {
                enabledChecks.add(check)
            }
        }

        // Decay VL by 1 per second
        decayTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (data in playerData.values) {
                val iter = data.violations.iterator()
                while (iter.hasNext()) {
                    val entry = iter.next()
                    val newVL = entry.value - 1.0
                    if (newVL <= 0.0) {
                        iter.remove()
                    } else {
                        entry.setValue(newVL)
                    }
                }
            }
        }, 20L, 20L)

        val checkCount = enabledChecks.size
        plugin.logger.info("[AntiCheat] Started with $checkCount/${CheckType.entries.size} checks enabled.")
    }

    fun stop() {
        decayTask?.cancel()
        decayTask = null
        playerData.clear()
    }

    private fun getData(player: Player): PlayerData {
        return playerData.computeIfAbsent(player.uniqueId) { PlayerData(uuid = it) }
    }

    private fun isExempt(player: Player): Boolean {
        return !enabled
                || player.hasPermission("joshymc.anticheat.bypass")
                || player.gameMode == GameMode.CREATIVE
                || player.gameMode == GameMode.SPECTATOR
    }

    fun isCheckEnabled(type: CheckType): Boolean = type in enabledChecks

    fun toggleCheck(type: CheckType): Boolean {
        val newState = if (type in enabledChecks) {
            enabledChecks.remove(type)
            false
        } else {
            enabledChecks.add(type)
            true
        }
        return newState
    }

    fun getTopViolators(limit: Int): List<Pair<String, Double>> {
        return playerData.values
            .map { data ->
                val name = Bukkit.getOfflinePlayer(data.uuid).name ?: data.uuid.toString().substring(0, 8)
                val totalVL = data.violations.values.sum()
                name to totalVL
            }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit)
    }

    fun getPlayerViolations(uuid: UUID): Map<CheckType, Double> {
        return playerData[uuid]?.violations?.toMap() ?: emptyMap()
    }

    // ══════════════════════════════════════════════════════════
    //  VIOLATION SYSTEM
    // ══════════════════════════════════════════════════════════

    private fun flag(player: Player, check: CheckType, vlAdd: Double, detail: String = "") {
        if (isExempt(player)) return
        val data = getData(player)
        val newVL = (data.violations[check] ?: 0.0) + vlAdd
        data.violations[check] = newVL

        // Alert staff (with cooldown per check type)
        val now = System.currentTimeMillis()
        val lastAlert = data.lastAlertTime[check] ?: 0L
        if (newVL >= alertVL && now - lastAlert > alertCooldownMs) {
            data.lastAlertTime[check] = now
            val msg = Component.text("[AC] ", TextColor.color(0xFF5555))
                .append(Component.text(player.name, NamedTextColor.WHITE))
                .append(Component.text(" failed ", TextColor.color(0xFF5555)))
                .append(Component.text(check.displayName, NamedTextColor.GOLD))
                .append(Component.text(" (VL: ${"%.0f".format(newVL)})", NamedTextColor.GRAY))
                .let { if (detail.isNotEmpty()) it.append(Component.text(" $detail", NamedTextColor.DARK_GRAY)) else it }

            for (staff in Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("joshymc.anticheat.alerts")) {
                    staff.sendMessage(msg)
                }
            }
            plugin.logger.info("[AntiCheat] ${player.name} failed ${check.displayName} VL=${"%.0f".format(newVL)} $detail")
        }

        // Kick at threshold
        if (newVL >= kickVL) {
            data.violations[check] = 0.0
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin) {
                if (player.isOnline) {
                    player.kick(
                        Component.text("Kicked by AntiCheat: ${check.displayName}", NamedTextColor.RED)
                    )
                    plugin.logger.warning("[AntiCheat] Kicked ${player.name} for ${check.displayName} (VL reached ${"%.0f".format(kickVL)})")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  JOIN / QUIT
    // ══════════════════════════════════════════════════════════

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        playerData[player.uniqueId] = PlayerData(uuid = player.uniqueId)

        // Delayed illegal item scan (give server time to load inventory)
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
            if (player.isOnline) checkIllegalItems(player)
        }, 20L)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        playerData.remove(event.player.uniqueId)
    }

    // ══════════════════════════════════════════════════════════
    //  TELEPORT — exempt briefly after teleport
    // ══════════════════════════════════════════════════════════

    @EventHandler
    fun onTeleport(event: PlayerTeleportEvent) {
        val data = getData(event.player)
        data.teleportTicks = 40 // 2 seconds of exemption after teleport
        data.lastLocation = event.to
        data.airTicks = 0
        data.lastOnGround = true
        data.pendingKnockback = false
    }

    // ══════════════════════════════════════════════════════════
    //  MOVEMENT CHECKS
    // ══════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        if (isExempt(player)) return

        val from = event.from
        val to = event.to
        val data = getData(player)
        val now = System.currentTimeMillis()

        // Decrement teleport exemption
        if (data.teleportTicks > 0) {
            data.teleportTicks--
            data.lastLocation = to
            data.lastMoveTime = now
            return
        }

        // Skip if only head rotation (no position change)
        if (from.x == to.x && from.y == to.y && from.z == to.z) {
            data.lastMoveTime = now
            return
        }

        val lastLoc = data.lastLocation ?: run {
            data.lastLocation = to
            data.lastMoveTime = now
            return
        }

        // Exempt players in vehicles, dead, sleeping, gliding, swimming, or recently bounced
        if (player.isInsideVehicle || player.isDead || player.isSleeping) {
            data.lastLocation = to
            data.lastMoveTime = now
            data.airTicks = 0
            return
        }

        // Exempt after slime block / bed bounce / wind charge
        val blockBelow = to.block.getRelative(BlockFace.DOWN)
        val belowType = blockBelow.type
        if (belowType == Material.SLIME_BLOCK || belowType.name.endsWith("_BED")) {
            data.teleportTicks = 30
            data.lastLocation = to
            data.lastMoveTime = now
            return
        }

        val isGliding = player.isGliding
        val isSwimming = player.isSwimming
        val isClimbing = isOnClimbable(to.block)
        val isInWater = player.isInWater
        val isInLava = to.block.type == Material.LAVA || to.block.getRelative(BlockFace.DOWN).type == Material.LAVA
        val hasLevitation = player.hasPotionEffect(PotionEffectType.LEVITATION)
        val hasSlowFalling = player.hasPotionEffect(PotionEffectType.SLOW_FALLING)

        val dx = to.x - lastLoc.x
        val dy = to.y - lastLoc.y
        val dz = to.z - lastLoc.z
        val horizontalDist = sqrt(dx * dx + dz * dz)

        // ── Timer Check ─────────────────────────────────
        if (isCheckEnabled(CheckType.TIMER)) {
            checkTimer(player, data, now)
        }

        // ── Flight Check ────────────────────────────────
        if (isCheckEnabled(CheckType.FLIGHT) && !isGliding && !isClimbing && !isInWater && !hasLevitation && !hasSlowFalling) {
            checkFlight(player, data, to, dy)
        }

        // ── Speed Check ─────────────────────────────────
        if (isCheckEnabled(CheckType.SPEED) && !isGliding && !isInWater && !isInLava) {
            checkSpeed(player, data, horizontalDist, now)
        }

        // ── NoFall Check ────────────────────────────────
        if (isCheckEnabled(CheckType.NO_FALL)) {
            checkNoFall(player, data, to)
        }

        // ── Jesus Check (WaterWalk) ─────────────────────
        if (isCheckEnabled(CheckType.JESUS)) {
            checkJesus(player, data, to, horizontalDist)
        }

        // ── Phase Check ─────────────────────────────────
        if (isCheckEnabled(CheckType.PHASE) && horizontalDist > 0.1) {
            checkPhase(player, data, lastLoc, to)
        }

        // ── BadPackets (Pitch) ──────────────────────────
        if (isCheckEnabled(CheckType.BAD_PACKETS)) {
            checkBadPacketsMove(player, to)
        }

        // Update state
        data.lastOnGround = player.isOnGround
        if (player.isOnGround) {
            data.airTicks = 0
            data.lastGroundY = to.y
        } else {
            data.airTicks++
        }
        data.lastLocation = to
        data.lastMoveTime = now
        data.lastFallDistance = player.fallDistance
    }

    // ── Flight ──────────────────────────────────────────
    private fun checkFlight(player: Player, data: PlayerData, to: Location, dy: Double) {
        // Players with allowFlight (e.g. /fly) or elytra gliding bypass the flight check
        if (player.allowFlight || player.isFlying || player.isGliding) {
            data.airTicks = 0
            return
        }

        // Players with the fly permission also bypass
        if (player.hasPermission("joshymc.fly") || player.hasPermission("joshymc.anticheat.fly.bypass")) {
            data.airTicks = 0
            return
        }

        if (player.isOnGround) {
            data.airTicks = 0
            return
        }

        // Allow normal jump arcs (up to ~1.25 blocks up, ~12 ticks air)
        // Flag sustained hovering or ascending after a long time airborne
        // Only flag VERY obvious flight — sustained hovering for 4+ seconds
        if (data.airTicks > 80 && dy >= -0.01 && dy <= 0.01) {
            // 80 ticks (~4 sec) airborne and perfectly level = likely flying
            if (!isNearSolid(to, 3)) {
                flag(player, CheckType.FLIGHT, 2.0, "air=${data.airTicks} dy=${"%.3f".format(dy)}")
            }
        }

        // Ascending WAY too long without jump boost — 3+ seconds of going up
        if (data.airTicks > 60 && dy > 0.2 && !player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
            if (!isNearSolid(to, 3)) {
                flag(player, CheckType.FLIGHT, 2.0, "ascend air=${data.airTicks} dy=${"%.3f".format(dy)}")
            }
        }
    }

    // ── Speed ───────────────────────────────────────────
    private fun checkSpeed(player: Player, data: PlayerData, horizontalDist: Double, now: Long) {
        val timeDelta = now - data.lastMoveTime
        if (timeDelta <= 0 || timeDelta > 1000) return // Skip first tick or big gaps

        // Base max speed per tick — very generous to avoid false positives
        var maxSpeed = 0.75 // sprint-jump with some momentum

        // Speed effect
        val speedEffect = player.getPotionEffect(PotionEffectType.SPEED)
        if (speedEffect != null) {
            maxSpeed += 0.06 * (speedEffect.amplifier + 1)
        }

        // Soul speed on soul sand/soil
        val below = player.location.block.getRelative(BlockFace.DOWN).type
        if (below == Material.SOUL_SAND || below == Material.SOUL_SOIL) {
            maxSpeed += 0.12
        }

        // Ice / packed ice / blue ice
        if (below == Material.ICE || below == Material.PACKED_ICE || below == Material.BLUE_ICE || below == Material.FROSTED_ICE) {
            maxSpeed += 0.6 // Ice is very slippery
        }

        // Dolphins grace
        if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
            maxSpeed += 0.3
        }

        // Add tolerance for lag (50%) and server TPS drops
        maxSpeed *= 1.5

        // Only flag extreme speed, not borderline
        if (horizontalDist > maxSpeed) {
            flag(player, CheckType.SPEED, 1.0, "dist=${"%.3f".format(horizontalDist)} max=${"%.3f".format(maxSpeed)}")
        }
    }

    // ── NoFall ──────────────────────────────────────────
    private fun checkNoFall(player: Player, data: PlayerData, to: Location) {
        // Only flag when player should have taken fatal fall damage but didn't
        // Check: fell more than 20 blocks but claims on ground with no damage
        if (player.isOnGround && data.airTicks > 60 && player.fallDistance < 1.0f && data.lastFallDistance > 20.0f) {
            if (!player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                && !player.isGliding
                && !player.isSwimming
            ) {
                flag(player, CheckType.NO_FALL, 2.0, "fell ${data.lastFallDistance} blocks, no damage")
            }
        }
    }

    // ── Jesus (WaterWalk) ───────────────────────────────
    private fun checkJesus(player: Player, data: PlayerData, to: Location, horizontalDist: Double) {
        if (horizontalDist < 0.1) return
        if (player.isInsideVehicle) return

        // Check if player is on top of water without being in it
        val blockAt = to.block
        val blockBelow = to.clone().add(0.0, -0.3, 0.0).block

        if ((blockBelow.type == Material.WATER || blockBelow.type == Material.LAVA)
            && !blockAt.isLiquid
            && !player.isInWater
            && !player.isOnGround
        ) {
            // Check for lily pad, frost walker, boat
            val feetBlock = to.clone().add(0.0, -0.1, 0.0).block
            if (feetBlock.type == Material.LILY_PAD) return
            if (player.inventory.boots?.containsEnchantment(Enchantment.FROST_WALKER) == true) return

            flag(player, CheckType.JESUS, 4.0, "walking on ${blockBelow.type.name.lowercase()}")
        }
    }

    // ── Phase (NoClip) ──────────────────────────────────
    private fun checkPhase(player: Player, data: PlayerData, from: Location, to: Location) {
        // Check if the player moved through a solid block horizontally
        val dx = to.x - from.x
        val dz = to.z - from.z
        val dist = sqrt(dx * dx + dz * dz)

        if (dist < 0.3) return // Too small to be phasing

        // Ray check between from and to for solid blocks at player's body height
        val midX = (from.x + to.x) / 2.0
        val midZ = (from.z + to.z) / 2.0
        val midBlock = from.world.getBlockAt(Location(from.world, midX, from.y + 0.5, midZ))

        if (midBlock.type.isSolid && midBlock.type.isOccluding) {
            // Verify it's not a door, gate, trapdoor, etc.
            if (!isPassableBlock(midBlock.type)) {
                flag(player, CheckType.PHASE, 5.0, "through ${midBlock.type.name.lowercase()}")
            }
        }
    }

    // ── Timer ───────────────────────────────────────────
    private fun checkTimer(player: Player, data: PlayerData, now: Long) {
        data.movePacketCount++

        if (now - data.lastPacketResetTime >= 1000L) {
            val packetsPerSecond = data.movePacketCount

            // Normal is ~20 packets/second, lag bursts can cause 30+
            // Only flag extreme timer abuse (40+)
            if (packetsPerSecond > 40) {
                flag(player, CheckType.TIMER, 1.0, "packets=$packetsPerSecond/s")
            }

            data.movePacketCount = 0
            data.lastPacketResetTime = now
        }
    }

    // ── BadPackets (Move) ───────────────────────────────
    private fun checkBadPacketsMove(player: Player, to: Location) {
        // Impossible pitch values
        if (abs(to.pitch) > 90.1f) {
            flag(player, CheckType.BAD_PACKETS, 10.0, "pitch=${"%.1f".format(to.pitch)}")
        }
    }

    // ══════════════════════════════════════════════════════════
    //  COMBAT CHECKS
    // ══════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        val attacker = event.damager as? Player ?: return
        val victim = event.entity
        if (isExempt(attacker)) return

        val data = getData(attacker)
        val now = System.currentTimeMillis()

        // ── Reach Check ─────────────────────────────────
        if (isCheckEnabled(CheckType.REACH)) {
            val distance = attacker.eyeLocation.distance(victim.location.add(0.0, victim.height / 2.0, 0.0))
            val maxReach = if (attacker.gameMode == GameMode.CREATIVE) 6.5 else 4.5

            if (distance > maxReach) {
                flag(attacker, CheckType.REACH, 2.0, "dist=${"%.2f".format(distance)} max=${"%.1f".format(maxReach)}")
            }
        }

        // ── KillAura Check ──────────────────────────────
        if (isCheckEnabled(CheckType.KILL_AURA)) {
            // Attack rate check
            if (now - data.lastAttackResetTime >= 1000L) {
                data.attackCountInSecond = 0
                data.lastAttackResetTime = now
            }
            data.attackCountInSecond++

            if (data.attackCountInSecond > 25) {
                flag(attacker, CheckType.KILL_AURA, 2.0, "rate=${data.attackCountInSecond}/s")
            }

            // Angle check — is the target behind the player?
            val eyeDir = attacker.eyeLocation.direction.normalize()
            val toTarget = victim.location.toVector().subtract(attacker.eyeLocation.toVector()).normalize()
            val dot = eyeDir.dot(toTarget)

            // dot < 0 means target is behind the player (>90 degrees)
            if (dot < -0.3) {
                flag(attacker, CheckType.KILL_AURA, 5.0, "angle=behind dot=${"%.2f".format(dot)}")
            }

            data.lastAttackTime = now
        }
    }

    // ── Velocity Check ──────────────────────────────────
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (isExempt(player)) return
        if (!isCheckEnabled(CheckType.VELOCITY)) return

        if (event.cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
            || event.cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
        ) {
            val data = getData(player)
            data.pendingKnockback = true
            data.knockbackTick = player.world.gameTime
            data.preKnockbackLocation = player.location.clone()

            // Check in 5 ticks if they actually moved
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
                if (!player.isOnline || isExempt(player)) return@scheduleSyncDelayedTask
                if (!data.pendingKnockback) return@scheduleSyncDelayedTask
                data.pendingKnockback = false

                val postLoc = player.location
                val preLoc = data.preKnockbackLocation ?: return@scheduleSyncDelayedTask

                val movedDist = preLoc.distance(postLoc)

                // Only flag if they literally didn't move at all (walls can stop knockback)
                if (movedDist < 0.01 && !player.isBlocking && !player.isDead && !isNearSolid(postLoc, 1)) {
                    flag(player, CheckType.VELOCITY, 1.0, "moved=${"%.3f".format(movedDist)}")
                }
            }, 5L)
        }
    }

    // ── AutoClick (CPS) ─────────────────────────────────
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (isExempt(player)) return
        if (!isCheckEnabled(CheckType.AUTO_CLICK)) return

        if (event.action == org.bukkit.event.block.Action.LEFT_CLICK_AIR
            || event.action == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK
        ) {
            val data = getData(player)
            val now = System.currentTimeMillis()
            data.clickTimes.add(now)

            // Remove clicks older than 1 second
            data.clickTimes.removeAll { now - it > 1000L }

            val cps = data.clickTimes.size
            if (cps > 30) { // 30 CPS is basically impossible without macros
                flag(player, CheckType.AUTO_CLICK, 2.0, "cps=$cps")
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  WORLD CHECKS
    // ══════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (isExempt(player)) return

        val data = getData(player)
        val now = System.currentTimeMillis()

        // ── Nuker Check ─────────────────────────────────
        if (isCheckEnabled(CheckType.NUKER)) {
            if (now - data.lastBreakResetTime >= 1000L) {
                data.blocksBrokenThisSecond = 0
                data.lastBreakResetTime = now
            }
            data.blocksBrokenThisSecond++

            // Veinminer/treefeller can break 128+ blocks — only flag insane values
            if (data.blocksBrokenThisSecond > 200) {
                flag(player, CheckType.NUKER, 2.0, "breaks=${data.blocksBrokenThisSecond}/s")
            }
        }

        // ── FastBreak Check ─────────────────────────────
        if (isCheckEnabled(CheckType.FAST_BREAK)) {
            val timeSinceLast = now - data.lastBlockBreakTime
            // Minimum ~50ms between breaks (accounting for insta-break with efficiency/haste)
            if (timeSinceLast in 1..30 && !isInstantBreak(event.block, player)) {
                flag(player, CheckType.FAST_BREAK, 3.0, "delta=${timeSinceLast}ms")
            }
            data.lastBlockBreakTime = now
        }

        // ── Reach (Block Break) ─────────────────────────
        if (isCheckEnabled(CheckType.REACH)) {
            val distance = player.eyeLocation.distance(event.block.location.add(0.5, 0.5, 0.5))
            if (distance > 6.5) { // Survival limit ~5.0 + tolerance
                flag(player, CheckType.REACH, 3.0, "break-dist=${"%.2f".format(distance)}")
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (isExempt(player)) return

        val data = getData(player)
        val now = System.currentTimeMillis()

        // ── FastPlace Check ─────────────────────────────
        if (isCheckEnabled(CheckType.FAST_PLACE)) {
            if (now - data.lastPlaceResetTime >= 1000L) {
                data.blocksPlacedThisSecond = 0
                data.lastPlaceResetTime = now
            }
            data.blocksPlacedThisSecond++

            if (data.blocksPlacedThisSecond > 18) {
                flag(player, CheckType.FAST_PLACE, 3.0, "places=${data.blocksPlacedThisSecond}/s")
                event.isCancelled = true
            }

            val timeSinceLast = now - data.lastBlockPlaceTime
            if (timeSinceLast in 1..30) {
                flag(player, CheckType.FAST_PLACE, 2.0, "delta=${timeSinceLast}ms")
            }
            data.lastBlockPlaceTime = now
        }

        // ── Scaffold Check ──────────────────────────────
        if (isCheckEnabled(CheckType.SCAFFOLD)) {
            val blockY = event.blockPlaced.y
            val playerY = player.location.blockY

            // Block placed directly below feet while moving
            if (blockY == playerY - 1
                && event.blockPlaced.x == player.location.blockX
                && event.blockPlaced.z == player.location.blockZ
            ) {
                if (now - data.lastScaffoldResetTime >= 2000L) {
                    data.blocksPlacedBelowFeet = 0
                    data.lastScaffoldResetTime = now
                }
                data.blocksPlacedBelowFeet++

                // Occasional bridging is normal, sustained fast bridging is suspicious
                if (data.blocksPlacedBelowFeet > 20) { // 20 in 2s = 10/s which is extremely fast scaffolding
                    flag(player, CheckType.SCAFFOLD, 1.0, "scaffold=${data.blocksPlacedBelowFeet}/2s")
                }
            }
        }

        // ── Reach (Block Place) ─────────────────────────
        if (isCheckEnabled(CheckType.REACH)) {
            val distance = player.eyeLocation.distance(event.blockPlaced.location.add(0.5, 0.5, 0.5))
            if (distance > 7.0) { // Placement has slightly longer effective range
                flag(player, CheckType.REACH, 3.0, "place-dist=${"%.2f".format(distance)}")
                event.isCancelled = true
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  PLAYER CHECKS
    // ══════════════════════════════════════════════════════════

    // ── Inventory Speed ─────────────────────────────────
    @EventHandler(priority = EventPriority.HIGH)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (isExempt(player)) return
        if (!isCheckEnabled(CheckType.INVENTORY)) return

        val data = getData(player)
        val now = System.currentTimeMillis()

        if (now - data.lastInvClickResetTime >= 1000L) {
            data.inventoryClicksThisSecond = 0
            data.lastInvClickResetTime = now
        }
        data.inventoryClicksThisSecond++

        // More than 25 inventory clicks per second is impossible legitimately
        if (data.inventoryClicksThisSecond > 25) {
            flag(player, CheckType.INVENTORY, 3.0, "clicks=${data.inventoryClicksThisSecond}/s")
            event.isCancelled = true
        }

        // Individual click speed: less than 20ms between clicks
        val timeSinceLast = now - data.lastInventoryClickTime
        if (timeSinceLast in 1..15) {
            flag(player, CheckType.INVENTORY, 2.0, "delta=${timeSinceLast}ms")
        }
        data.lastInventoryClickTime = now
    }

    // ── BadPackets (Invalid Actions) ────────────────────
    @EventHandler
    fun onBadPacketInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (isExempt(player)) return
        if (!isCheckEnabled(CheckType.BAD_PACKETS)) return

        // Interacting while dead or sleeping
        if (player.isDead) {
            flag(player, CheckType.BAD_PACKETS, 10.0, "interact-while-dead")
        }
        if (player.isSleeping) {
            flag(player, CheckType.BAD_PACKETS, 5.0, "interact-while-sleeping")
        }
    }

    // ── Illegal Items ───────────────────────────────────
    fun checkIllegalItems(player: Player) {
        if (isExempt(player)) return
        if (!isCheckEnabled(CheckType.ILLEGAL_ITEMS)) return

        for (item in player.inventory.contents.filterNotNull()) {
            if (item.type == Material.AIR) continue

            // Check for over-enchanted items
            for ((enchant, level) in item.enchantments) {
                if (level > enchant.maxLevel + 2) { // Allow some leeway for custom items
                    flag(player, CheckType.ILLEGAL_ITEMS, 10.0,
                        "over-enchant: ${enchant.key.key} level $level on ${item.type.name.lowercase()}")
                    // Remove the illegal enchantment
                    item.removeEnchantment(enchant)
                }
            }

            // Check for stacked unstackables
            if (item.amount > item.maxStackSize) {
                flag(player, CheckType.ILLEGAL_ITEMS, 10.0,
                    "stacked: ${item.type.name.lowercase()} x${item.amount} (max ${item.maxStackSize})")
                item.amount = item.maxStackSize
            }

            // Check for negative durability (shouldn't exist)
            val meta = item.itemMeta
            if (meta is org.bukkit.inventory.meta.Damageable && meta.damage < 0) {
                flag(player, CheckType.ILLEGAL_ITEMS, 10.0,
                    "negative-durability: ${item.type.name.lowercase()} dmg=${meta.damage}")
                meta.damage = 0
                item.itemMeta = meta
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  UTILITY
    // ══════════════════════════════════════════════════════════

    private fun isOnClimbable(block: Block): Boolean {
        val type = block.type
        return type == Material.LADDER
                || type == Material.VINE
                || type == Material.TWISTING_VINES
                || type == Material.TWISTING_VINES_PLANT
                || type == Material.WEEPING_VINES
                || type == Material.WEEPING_VINES_PLANT
                || type == Material.SCAFFOLDING
                || type == Material.CAVE_VINES
                || type == Material.CAVE_VINES_PLANT
    }

    private fun isNearSolid(loc: Location, radius: Int): Boolean {
        for (x in -radius..radius) {
            for (y in -radius..radius) {
                for (z in -radius..radius) {
                    val block = loc.clone().add(x.toDouble(), y.toDouble(), z.toDouble()).block
                    if (block.type.isSolid) return true
                }
            }
        }
        return false
    }

    private fun isActuallyOnGround(loc: Location): Boolean {
        val below = loc.clone().add(0.0, -0.1, 0.0).block
        return below.type.isSolid || below.type == Material.LILY_PAD
    }

    private fun isPassableBlock(type: Material): Boolean {
        return type.name.contains("DOOR")
                || type.name.contains("GATE")
                || type.name.contains("TRAPDOOR")
                || type.name.contains("SIGN")
                || type.name.contains("BANNER")
                || type.name.contains("BUTTON")
                || type.name.contains("LEVER")
                || type.name.contains("PRESSURE_PLATE")
                || type.name.contains("CARPET")
                || type.name.contains("SLAB") // half slabs can be passable
                || type.name.contains("FENCE") // can jump over
                || type.name.contains("WALL")
                || type == Material.COBWEB
                || type == Material.POWDER_SNOW
    }

    private fun isInstantBreak(block: Block, player: Player): Boolean {
        // Some blocks are instant-break regardless (crops, torches, flowers, etc.)
        val type = block.type
        if (type.hardness == 0f) return true

        // Haste + efficiency makes many blocks instant
        val haste = player.getPotionEffect(PotionEffectType.HASTE)
        if (haste != null && haste.amplifier >= 1) return true

        // Creative mode is instant
        if (player.gameMode == GameMode.CREATIVE) return true

        return false
    }
}
