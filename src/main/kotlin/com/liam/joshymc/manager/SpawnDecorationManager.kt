package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class SpawnDecorationManager(private val plugin: Joshymc) : Listener {

    private val legacySerializer = LegacyComponentSerializer.legacyAmpersand()

    /** DB row id -> list of spawned armor stand UUIDs (for multi-line holograms) */
    private val spawnedEntities = mutableMapOf<Int, MutableList<UUID>>()

    /** Active particle task */
    private var particleTask: BukkitTask? = null

    /** Cached decoration rows for particle/beacon rendering */
    private val particleEmitters = mutableListOf<DecorationRow>()
    private val beaconEmitters = mutableListOf<DecorationRow>()
    private val fireworkEmitters = mutableListOf<DecorationRow>()
    /** Last firework launch tick per row id, used for the launch interval */
    private val fireworkLastLaunch = mutableMapOf<Int, Long>()

    private companion object {
        const val TAG = "joshymc_spawn_decor"
        const val LINE_SPACING = 0.3

        val PARTICLE_TYPES = mapOf(
            "flame" to Particle.FLAME,
            "enchant" to Particle.ENCHANT,
            "end_rod" to Particle.END_ROD,
            "heart" to Particle.HEART,
            "note" to Particle.NOTE,
            "soul" to Particle.SOUL_FIRE_FLAME,
            "portal" to Particle.PORTAL,
            "cherry" to Particle.CHERRY_LEAVES,
            "snow" to Particle.SNOWFLAKE,
            "redstone" to Particle.DUST,
        )

        val ANIMATION_TYPES = listOf("static", "spiral", "ring", "fountain", "vortex")

        val BEACON_COLORS = mapOf(
            "red" to Color.RED,
            "blue" to Color.BLUE,
            "green" to Color.GREEN,
            "gold" to Color.fromRGB(255, 215, 0),
            "purple" to Color.PURPLE,
            "white" to Color.WHITE,
            "aqua" to Color.AQUA,
        )
    }

    data class DecorationRow(
        val id: Int,
        val type: String,
        val world: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val data: String?,
        val yaw: Float = 0f,
        val locked: Boolean = false,
        val scale: Float = 1f,
    )

    // ── Lifecycle ───────────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS spawn_decorations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                world TEXT NOT NULL,
                x REAL NOT NULL,
                y REAL NOT NULL,
                z REAL NOT NULL,
                data TEXT,
                yaw REAL DEFAULT 0.0,
                locked INTEGER DEFAULT 0,
                scale REAL DEFAULT 1.0
            )
        """.trimIndent())
        try { plugin.databaseManager.execute("ALTER TABLE spawn_decorations ADD COLUMN yaw REAL DEFAULT 0.0") } catch (_: Exception) {}
        try { plugin.databaseManager.execute("ALTER TABLE spawn_decorations ADD COLUMN locked INTEGER DEFAULT 0") } catch (_: Exception) {}
        try { plugin.databaseManager.execute("ALTER TABLE spawn_decorations ADD COLUMN scale REAL DEFAULT 1.0") } catch (_: Exception) {}

        // Spawn entities once the server is ready
        plugin.server.scheduler.runTaskLater(plugin, Runnable { loadAll() }, 1L)

        plugin.logger.info("[SpawnDecor] Manager started.")
    }

    fun stop() {
        particleTask?.cancel()
        particleTask = null
        particleEmitters.clear()
        beaconEmitters.clear()
        fireworkEmitters.clear()
        fireworkLastLaunch.clear()

        for ((_, uuids) in spawnedEntities) {
            for (uuid in uuids) {
                Bukkit.getEntity(uuid)?.remove()
            }
        }
        spawnedEntities.clear()

        plugin.logger.info("[SpawnDecor] Manager stopped.")
    }

    // ── Loading ─────────────────────────────────────────────

    private fun loadAll() {
        // Clean up any leftover entities from previous runs (both old ArmorStands and new TextDisplays)
        for (world in Bukkit.getWorlds()) {
            world.entities
                .filter { it.scoreboardTags.contains(TAG) }
                .forEach { it.remove() }
        }

        val rows = plugin.databaseManager.query(
            "SELECT id, type, world, x, y, z, data, yaw, locked, scale FROM spawn_decorations"
        ) { rs ->
            DecorationRow(
                rs.getInt("id"), rs.getString("type"), rs.getString("world"),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getString("data"),
                yaw = rs.getFloat("yaw"),
                locked = rs.getInt("locked") == 1,
                scale = rs.getFloat("scale").let { if (it <= 0f) 1f else it }
            )
        }

        for (row in rows) {
            when (row.type) {
                "hologram", "logo" -> spawnHologramEntities(row)
                "particle" -> particleEmitters.add(row)
                "beacon" -> beaconEmitters.add(row)
                "firework" -> fireworkEmitters.add(row)
            }
        }

        startParticleTask()
    }

    // ── Hologram / Logo spawning ────────────────────────────

    private fun spawnHologramEntities(row: DecorationRow) {
        val world = Bukkit.getWorld(row.world) ?: return
        val text = row.data ?: return

        val lines = text.split("\\n")
        val uuids = mutableListOf<UUID>()
        val effectiveSpacing = LINE_SPACING * row.scale

        for ((index, line) in lines.withIndex()) {
            val loc = Location(world, row.x, row.y - (index * effectiveSpacing), row.z)
            if (row.locked) loc.yaw = row.yaw

            val display = world.spawn(loc, org.bukkit.entity.TextDisplay::class.java) { entity ->
                entity.text(legacySerializer.deserialize(line))
                // FIXED billboard = locked direction (no auto-facing).
                // CENTER billboard = always faces the player.
                entity.billboard = if (row.locked) org.bukkit.entity.Display.Billboard.FIXED else org.bukkit.entity.Display.Billboard.CENTER
                entity.backgroundColor = org.bukkit.Color.fromARGB(0, 0, 0, 0)
                entity.isShadowed = true
                entity.isPersistent = true
                entity.addScoreboardTag(TAG)
                entity.addScoreboardTag("joshymc_decor_${row.id}")

                if (row.scale != 1f) {
                    val t = entity.transformation
                    val scaleVec = org.joml.Vector3f(row.scale, row.scale, row.scale)
                    entity.transformation = org.bukkit.util.Transformation(
                        t.translation, t.leftRotation, scaleVec, t.rightRotation
                    )
                }
            }
            uuids.add(display.uniqueId)
        }

        spawnedEntities[row.id] = uuids
    }

    // ── Particle task ───────────────────────────────────────

    private fun startParticleTask() {
        if (particleEmitters.isEmpty() && beaconEmitters.isEmpty() && fireworkEmitters.isEmpty()) return

        particleTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            // Particle emitters
            for (row in particleEmitters) {
                val world = Bukkit.getWorld(row.world) ?: continue
                val loc = Location(world, row.x, row.y, row.z)

                // Parse data: either "type" (legacy) or "type;radius=X;count=X;speed=X;animation=X"
                val rawData = row.data ?: "end_rod"
                val parts = rawData.split(";")
                val typeName = parts[0]
                val props = parts.drop(1).associate {
                    val (k, v) = it.split("=", limit = 2)
                    k to v
                }
                val radius = props["radius"]?.toDoubleOrNull() ?: 1.0
                val count = props["count"]?.toIntOrNull() ?: 5
                val speed = props["speed"]?.toDoubleOrNull() ?: 0.0
                val animation = props["animation"] ?: "static"

                val particleType = PARTICLE_TYPES[typeName] ?: Particle.END_ROD
                val isDust = particleType == Particle.DUST

                when (animation) {
                    "spiral" -> {
                        val time = System.currentTimeMillis() / 1000.0
                        val angle = time * 2.0 * Math.PI
                        for (i in 0..5) {
                            val a = angle + i * (Math.PI * 2 / 6)
                            val px = loc.x + cos(a) * radius
                            val pz = loc.z + sin(a) * radius
                            val py = loc.y + (i * 0.3)
                            val pLoc = Location(world, px, py, pz)
                            if (isDust) world.spawnParticle(particleType, pLoc, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(Color.RED, 1.0f))
                            else world.spawnParticle(particleType, pLoc, 1, 0.0, 0.0, 0.0, 0.0)
                        }
                    }
                    "ring" -> {
                        for (i in 0..20) {
                            val a = i * (Math.PI * 2 / 20)
                            val px = loc.x + cos(a) * radius
                            val pz = loc.z + sin(a) * radius
                            val pLoc = Location(world, px, loc.y, pz)
                            if (isDust) world.spawnParticle(particleType, pLoc, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(Color.RED, 1.0f))
                            else world.spawnParticle(particleType, pLoc, 1, 0.0, 0.0, 0.0, 0.0)
                        }
                    }
                    "fountain" -> {
                        if (isDust) world.spawnParticle(particleType, loc, count, 0.3, 0.5, 0.3, speed, Particle.DustOptions(Color.RED, 1.0f))
                        else world.spawnParticle(particleType, loc, count, 0.3, 0.5, 0.3, speed)
                    }
                    "vortex" -> {
                        val time = System.currentTimeMillis() / 500.0
                        for (i in 0..10) {
                            val h = i * 0.5
                            val a = time + h * 2
                            val r = radius * (1.0 - h / 5.0)
                            val px = loc.x + cos(a) * r
                            val pz = loc.z + sin(a) * r
                            val pLoc = Location(world, px, loc.y + h, pz)
                            if (isDust) world.spawnParticle(particleType, pLoc, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(Color.RED, 1.0f))
                            else world.spawnParticle(particleType, pLoc, 1, 0.0, 0.0, 0.0, 0.0)
                        }
                    }
                    else -> {
                        // static
                        if (isDust) world.spawnParticle(particleType, loc, count, radius * 0.5, 0.3, radius * 0.5, speed, Particle.DustOptions(Color.RED, 1.0f))
                        else world.spawnParticle(particleType, loc, count, radius * 0.5, 0.3, radius * 0.5, speed)
                    }
                }
            }

            // Firework launchers — periodically launch a burst of 1-3 fireworks
            tickFireworks()

            // Beacon beams (END_ROD column)
            for (row in beaconEmitters) {
                val world = Bukkit.getWorld(row.world) ?: continue
                val color = BEACON_COLORS[row.data] ?: Color.WHITE
                val dustOptions = Particle.DustOptions(color, 1.5f)

                for (y in 0..100 step 2) {
                    val loc = Location(world, row.x, y.toDouble(), row.z)
                    world.spawnParticle(Particle.DUST, loc, 1, 0.0, 0.0, 0.0, 0.0, dustOptions)
                }
            }
        }, 5L, 5L)
    }

    // ── CRUD operations ─────────────────────────────────────

    fun addHologram(location: Location, text: String): Int {
        plugin.databaseManager.execute(
            "INSERT INTO spawn_decorations (type, world, x, y, z, data) VALUES (?, ?, ?, ?, ?, ?)",
            "hologram", location.world.name, location.x, location.y, location.z, text
        )
        val id = plugin.databaseManager.queryFirst(
            "SELECT last_insert_rowid() AS id"
        ) { it.getInt("id") } ?: -1

        val row = DecorationRow(id, "hologram", location.world.name, location.x, location.y, location.z, text)
        spawnHologramEntities(row)
        return id
    }

    fun addParticle(
        location: Location,
        type: String,
        radius: Double = 1.0,
        count: Int = 5,
        speed: Double = 0.0,
        animation: String = "static"
    ): Int {
        val data = "$type;radius=$radius;count=$count;speed=$speed;animation=$animation"
        plugin.databaseManager.execute(
            "INSERT INTO spawn_decorations (type, world, x, y, z, data) VALUES (?, ?, ?, ?, ?, ?)",
            "particle", location.world.name, location.x, location.y, location.z, data
        )
        val id = plugin.databaseManager.queryFirst(
            "SELECT last_insert_rowid() AS id"
        ) { it.getInt("id") } ?: -1

        val row = DecorationRow(id, "particle", location.world.name, location.x, location.y, location.z, data)
        particleEmitters.add(row)
        restartParticleTask()
        return id
    }

    /**
     * Add a firework launcher (single point or line) at this location.
     *
     * Data format (key=value;key=value):
     *   interval=<seconds>           Seconds between bursts (default 4)
     *   burst=<n>                    Fireworks per burst (default 3)
     *   power=<0-3>                  Rocket flight power (default 1)
     *   colors=<csv>                 Color names (default red,blue,white)
     *   fade=<csv>                   Fade colors (default = empty = same as colors)
     *   effect=<ball|ball_large|star|burst|creeper|random>
     *   flicker=<true|false|random>  Flicker (default random)
     *   trail=<true|false|random>    Trail (default random)
     *   sync=<group_id>              Sync group: all emitters in the same group fire at the same global tick
     *   line_x2,line_y2,line_z2      End coordinates (if present, this is a line)
     *   step=<blocks>                Step size between launchers along the line (default 2)
     *   pattern=<simultaneous|sequential|pingpong|wave|splatter|center_out|outside_in>
     */
    fun addFirework(location: Location, interval: Int, burst: Int, power: Int, colors: String): Int {
        val data = "interval=$interval;burst=$burst;power=$power;colors=$colors;effect=random;flicker=random;trail=random"
        return insertFirework(location, data)
    }

    /** Add a firework line spanning two coordinates with a launch pattern. */
    fun addFireworkLine(
        worldName: String,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        interval: Int, burst: Int, power: Int, colors: String,
        step: Double, pattern: String
    ): Int {
        val data = "interval=$interval;burst=$burst;power=$power;colors=$colors;effect=random;flicker=random;trail=random" +
                ";line_x2=$x2;line_y2=$y2;line_z2=$z2;step=$step;pattern=$pattern"
        val world = Bukkit.getWorld(worldName) ?: return -1
        val loc = Location(world, x1, y1, z1)
        return insertFirework(loc, data)
    }

    private fun insertFirework(location: Location, data: String): Int {
        plugin.databaseManager.execute(
            "INSERT INTO spawn_decorations (type, world, x, y, z, data) VALUES (?, ?, ?, ?, ?, ?)",
            "firework", location.world.name, location.x, location.y, location.z, data
        )
        val id = plugin.databaseManager.queryFirst("SELECT last_insert_rowid() AS id") { it.getInt("id") } ?: -1
        val row = DecorationRow(id, "firework", location.world.name, location.x, location.y, location.z, data)
        fireworkEmitters.add(row)
        restartParticleTask()
        return id
    }

    // ── Event-triggered fireworks ──────────────────────────

    @EventHandler
    fun onJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
        val player = event.player
        // Defer one tick so the player is fully spawned in
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            // First-join: hasPlayedBefore == false
            if (!player.hasPlayedBefore()) {
                fireTriggered("first_join", player.location)
            }
            fireTriggered("join", player.location)
        }, 5L)
    }

    @EventHandler
    fun onDeath(event: org.bukkit.event.entity.PlayerDeathEvent) {
        fireTriggered("death", event.entity.location)
    }

    @EventHandler
    fun onLevelUp(event: org.bukkit.event.player.PlayerLevelChangeEvent) {
        if (event.newLevel > event.oldLevel) {
            fireTriggered("levelup", event.player.location)
        }
    }

    /** Public hook so other systems (e.g. VoteManager) can fire vote-triggered decorations. */
    fun onVote(player: Player) {
        fireTriggered("vote", player.location)
    }

    /** Set the event trigger for a firework. */
    fun setTrigger(id: Int, event: String): Boolean {
        return editFirework(id, "trigger", event)
    }

    /** Remove the event trigger from a firework (it goes back to interval-only). */
    fun removeTrigger(id: Int): Boolean {
        val row = fireworkEmitters.firstOrNull { it.id == id } ?: return false
        val map = parseDataMap(row.data)
        map.remove("trigger")
        val newData = map.entries.joinToString(";") { "${it.key}=${it.value}" }
        plugin.databaseManager.execute("UPDATE spawn_decorations SET data = ? WHERE id = ?", newData, id)
        fireworkEmitters.removeAll { it.id == id }
        fireworkEmitters.add(row.copy(data = newData))
        return true
    }

    /** Manually fire a single firework decoration once (used by event triggers). */
    fun fireFireworkOnce(id: Int, atLocation: Location? = null) {
        val row = fireworkEmitters.firstOrNull { it.id == id } ?: return
        val world = atLocation?.world ?: Bukkit.getWorld(row.world) ?: return
        val props = parseFireworkData(row.data)

        // If a specific location is provided (e.g. player join), fire there once
        if (atLocation != null) {
            val pts = if (props.lineEnd != null) computeLaunchPoints(world, row, props)
                      else listOf(atLocation)
            schedulePatternedLaunches(world, pts, props)
        } else {
            val pts = computeLaunchPoints(world, row, props)
            schedulePatternedLaunches(world, pts, props)
        }
    }

    /** Fire all fireworks tagged with the given trigger event. */
    fun fireTriggered(event: String, atLocation: Location? = null) {
        for (row in fireworkEmitters.toList()) {
            val props = parseFireworkData(row.data)
            val trigger = parseDataMap(row.data)["trigger"] ?: continue
            if (trigger.equals(event, ignoreCase = true)) {
                fireFireworkOnce(row.id, atLocation)
            }
        }
    }

    /** Edit a single property on an existing firework decoration. */
    fun editFirework(id: Int, property: String, value: String): Boolean {
        val row = fireworkEmitters.firstOrNull { it.id == id } ?: return false
        val current = parseDataMap(row.data)
        val key = property.lowercase()
        // Validate the property name
        if (key !in setOf("interval", "burst", "power", "colors", "fade", "effect",
                "flicker", "trail", "sync", "step", "pattern", "trigger")) return false
        current[key] = value
        val newData = current.entries.joinToString(";") { "${it.key}=${it.value}" }
        plugin.databaseManager.execute("UPDATE spawn_decorations SET data = ? WHERE id = ?", newData, id)
        // Replace cached row
        fireworkEmitters.removeAll { it.id == id }
        fireworkEmitters.add(row.copy(data = newData))
        return true
    }

    /** Tick: launch fireworks for any emitter whose interval has elapsed. */
    private fun tickFireworks() {
        if (fireworkEmitters.isEmpty()) return
        val nowMs = System.currentTimeMillis()

        for (row in fireworkEmitters) {
            val world = Bukkit.getWorld(row.world) ?: continue
            val dataMap = parseDataMap(row.data)

            // Skip emitters that are event-triggered only (no auto-interval firing)
            if (dataMap.containsKey("trigger") && dataMap["trigger"] != "none") continue

            val props = parseFireworkData(row.data)
            val intervalMs = (props.interval * 1000L).coerceAtLeast(250L)

            // Sync group: all emitters with the same sync key fire at the same global slot
            val last = fireworkLastLaunch[row.id] ?: 0L
            val syncedNow = if (props.sync != null) {
                // Round nowMs to the nearest interval boundary
                (nowMs / intervalMs) * intervalMs
            } else nowMs

            if (syncedNow - last < intervalMs) continue
            fireworkLastLaunch[row.id] = syncedNow

            // Generate the list of launch points (single point or line)
            val launchPoints = computeLaunchPoints(world, row, props)

            // Apply pattern to schedule the launches over time
            schedulePatternedLaunches(world, launchPoints, props)
        }
    }

    /** Build the list of base launch positions. */
    private fun computeLaunchPoints(world: org.bukkit.World, row: DecorationRow, props: FireworkProps): List<Location> {
        val list = mutableListOf<Location>()
        if (props.lineEnd != null) {
            val (x2, y2, z2) = props.lineEnd
            val dx = x2 - row.x; val dy = y2 - row.y; val dz = z2 - row.z
            val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            val step = props.step.coerceAtLeast(0.5)
            val count = (length / step).toInt().coerceAtLeast(1)
            for (i in 0..count) {
                val t = if (count == 0) 0.0 else i.toDouble() / count
                list.add(Location(world, row.x + dx * t, row.y + dy * t, row.z + dz * t))
            }
        } else {
            list.add(Location(world, row.x, row.y, row.z))
        }
        return list
    }

    /** Apply the pattern: schedule each point's launch with a per-point delay. */
    private fun schedulePatternedLaunches(world: org.bukkit.World, points: List<Location>, props: FireworkProps) {
        val pattern = props.pattern.lowercase()
        val burst = props.burst.coerceIn(1, 20)

        when (pattern) {
            "sequential" -> {
                // Walk along the line, one point per ~2 ticks
                for ((i, p) in points.withIndex()) {
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        for (b in 0 until burst) launchAt(p, props)
                    }, (i * 2L))
                }
            }
            "pingpong" -> {
                val seq = points + points.reversed().drop(1)
                for ((i, p) in seq.withIndex()) {
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        for (b in 0 until burst) launchAt(p, props)
                    }, (i * 2L))
                }
            }
            "wave" -> {
                // Sine wave: launch in order but with varying vertical timing
                for ((i, p) in points.withIndex()) {
                    val phase = i.toDouble() / points.size.coerceAtLeast(1) * Math.PI * 2
                    val delayTicks = ((kotlin.math.sin(phase) + 1.0) * 6).toLong()
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        for (b in 0 until burst) launchAt(p, props)
                    }, delayTicks)
                }
            }
            "splatter" -> {
                // Random launch order with random delays
                val shuffled = points.shuffled()
                for ((i, p) in shuffled.withIndex()) {
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        for (b in 0 until burst) launchAt(p, props)
                    }, (i + (0..3).random()).toLong())
                }
            }
            "center_out" -> {
                val mid = points.size / 2
                for ((i, p) in points.withIndex()) {
                    val distance = kotlin.math.abs(i - mid)
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        for (b in 0 until burst) launchAt(p, props)
                    }, (distance * 2L))
                }
            }
            "outside_in" -> {
                val mid = points.size / 2
                for ((i, p) in points.withIndex()) {
                    val distance = kotlin.math.abs(i - mid)
                    val maxDist = mid
                    val delay = ((maxDist - distance) * 2L).coerceAtLeast(0L)
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        for (b in 0 until burst) launchAt(p, props)
                    }, delay)
                }
            }
            else -> { // "simultaneous" or default
                for (p in points) {
                    for (b in 0 until burst) {
                        // Slight scatter so they don't perfectly stack
                        val ox = (Math.random() - 0.5) * 1.5
                        val oz = (Math.random() - 0.5) * 1.5
                        launchAt(Location(p.world, p.x + ox, p.y, p.z + oz), props)
                    }
                }
            }
        }
    }

    private fun launchAt(loc: Location, props: FireworkProps) {
        spawnFirework(loc, props.power, props.colors, props.fadeColors, props.effect, props.flicker, props.trail)
    }

    private data class FireworkProps(
        val interval: Int,
        val burst: Int,
        val power: Int,
        val colors: List<Color>,
        val fadeColors: List<Color>,
        val effect: String,           // ball, ball_large, star, burst, creeper, random
        val flicker: String,          // true, false, random
        val trail: String,            // true, false, random
        val sync: String?,
        val lineEnd: Triple<Double, Double, Double>?,
        val step: Double,
        val pattern: String           // simultaneous, sequential, pingpong, wave, splatter, center_out, outside_in
    )

    private fun parseDataMap(data: String?): MutableMap<String, String> {
        return (data ?: "").split(";")
            .mapNotNull { val kv = it.split("=", limit = 2); if (kv.size == 2) kv[0].trim() to kv[1].trim() else null }
            .toMap()
            .toMutableMap()
    }

    private fun parseFireworkData(data: String?): FireworkProps {
        val parts = parseDataMap(data)
        val interval = parts["interval"]?.toIntOrNull() ?: 4
        val burst = parts["burst"]?.toIntOrNull() ?: 3
        val power = parts["power"]?.toIntOrNull() ?: 1
        val colors = parseColorList(parts["colors"]).ifEmpty { listOf(Color.RED, Color.BLUE, Color.WHITE) }
        val fadeColors = parseColorList(parts["fade"])
        val effect = parts["effect"] ?: "random"
        val flicker = parts["flicker"] ?: "random"
        val trail = parts["trail"] ?: "random"
        val sync = parts["sync"]

        // Line endpoints
        val x2 = parts["line_x2"]?.toDoubleOrNull()
        val y2 = parts["line_y2"]?.toDoubleOrNull()
        val z2 = parts["line_z2"]?.toDoubleOrNull()
        val lineEnd = if (x2 != null && y2 != null && z2 != null) Triple(x2, y2, z2) else null

        val step = parts["step"]?.toDoubleOrNull() ?: 2.0
        val pattern = parts["pattern"] ?: "simultaneous"

        return FireworkProps(interval, burst, power, colors, fadeColors, effect, flicker, trail, sync, lineEnd, step, pattern)
    }

    private fun parseColorList(csv: String?): List<Color> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(",").mapNotNull { name ->
            when (name.trim().lowercase()) {
                "red" -> Color.RED
                "blue" -> Color.BLUE
                "green" -> Color.GREEN
                "lime" -> Color.LIME
                "gold", "orange" -> Color.ORANGE
                "yellow" -> Color.YELLOW
                "white" -> Color.WHITE
                "black" -> Color.BLACK
                "purple" -> Color.PURPLE
                "pink" -> Color.FUCHSIA
                "aqua", "cyan" -> Color.AQUA
                "silver", "gray" -> Color.SILVER
                "maroon" -> Color.MAROON
                "navy" -> Color.NAVY
                "teal" -> Color.TEAL
                "olive" -> Color.OLIVE
                else -> {
                    // Allow hex like #FF5555
                    if (name.trim().startsWith("#") && name.trim().length == 7) {
                        try { Color.fromRGB(name.trim().substring(1).toInt(16)) } catch (_: Exception) { null }
                    } else null
                }
            }
        }
    }

    private fun spawnFirework(
        loc: Location,
        power: Int,
        colors: List<Color>,
        fadeColors: List<Color>,
        effectType: String,
        flicker: String,
        trail: String
    ) {
        val world = loc.world ?: return
        val firework = world.spawn(loc, org.bukkit.entity.Firework::class.java) { fw ->
            val meta = fw.fireworkMeta
            meta.power = power.coerceIn(0, 3)

            val type = when (effectType.lowercase()) {
                "ball" -> org.bukkit.FireworkEffect.Type.BALL
                "ball_large", "large" -> org.bukkit.FireworkEffect.Type.BALL_LARGE
                "star" -> org.bukkit.FireworkEffect.Type.STAR
                "burst" -> org.bukkit.FireworkEffect.Type.BURST
                "creeper" -> org.bukkit.FireworkEffect.Type.CREEPER
                else -> arrayOf(
                    org.bukkit.FireworkEffect.Type.BALL,
                    org.bukkit.FireworkEffect.Type.BALL_LARGE,
                    org.bukkit.FireworkEffect.Type.STAR,
                    org.bukkit.FireworkEffect.Type.BURST
                ).random()
            }

            val effectiveFade = if (fadeColors.isEmpty()) colors else fadeColors

            val flickerVal = when (flicker.lowercase()) { "true" -> true; "false" -> false; else -> Math.random() < 0.5 }
            val trailVal = when (trail.lowercase()) { "true" -> true; "false" -> false; else -> Math.random() < 0.5 }

            val effect = org.bukkit.FireworkEffect.builder()
                .with(type)
                .withColor(colors.shuffled().take((1..colors.size).random()))
                .withFade(effectiveFade.shuffled().take(1))
                .flicker(flickerVal)
                .trail(trailVal)
                .build()
            meta.addEffect(effect)
            fw.fireworkMeta = meta
            fw.addScoreboardTag(TAG)
        }
        firework.persistentDataContainer.set(
            org.bukkit.NamespacedKey(plugin, "decor_firework"),
            org.bukkit.persistence.PersistentDataType.BYTE, 1
        )
    }

    fun addBeacon(location: Location, color: String): Int {
        plugin.databaseManager.execute(
            "INSERT INTO spawn_decorations (type, world, x, y, z, data) VALUES (?, ?, ?, ?, ?, ?)",
            "beacon", location.world.name, location.x, location.y, location.z, color
        )
        val id = plugin.databaseManager.queryFirst(
            "SELECT last_insert_rowid() AS id"
        ) { it.getInt("id") } ?: -1

        val row = DecorationRow(id, "beacon", location.world.name, location.x, location.y, location.z, color)
        beaconEmitters.add(row)
        restartParticleTask()
        return id
    }

    fun removeDecoration(id: Int): Boolean {
        val affected = plugin.databaseManager.executeUpdate(
            "DELETE FROM spawn_decorations WHERE id = ?", id
        )
        if (affected == 0) return false

        // Remove spawned entities — try Bukkit.getEntity first, then brute force search
        val uuids = spawnedEntities.remove(id)
        if (uuids != null) {
            for (uuid in uuids) {
                val entity = Bukkit.getEntity(uuid)
                if (entity != null) {
                    entity.remove()
                } else {
                    // Brute force: search all worlds for armor stands with our tag
                    for (world in Bukkit.getWorlds()) {
                        for (e in world.entities) {
                            if (e.uniqueId == uuid) { e.remove(); break }
                        }
                    }
                }
            }
        }

        // Also clean up any armor stands with our tag near the decoration location
        // Query the location before deletion would have been better, so search by tag
        for (world in Bukkit.getWorlds()) {
            world.entities.filter { it.scoreboardTags.contains("joshymc_spawn_decor") && it is ArmorStand }.forEach { stand ->
                // If this armor stand's UUID isn't tracked by any other decoration, it's orphaned
                val isTracked = spawnedEntities.values.any { list -> list.contains(stand.uniqueId) }
                if (!isTracked && stand.scoreboardTags.contains("joshymc_decor_$id")) {
                    stand.remove()
                }
            }
        }

        // Remove from particle/beacon/firework caches
        particleEmitters.removeAll { it.id == id }
        beaconEmitters.removeAll { it.id == id }
        fireworkEmitters.removeAll { it.id == id }
        fireworkLastLaunch.remove(id)
        restartParticleTask()
        return true
    }

    fun listDecorations(): List<DecorationRow> {
        return plugin.databaseManager.query(
            "SELECT id, type, world, x, y, z, data, yaw, locked, scale FROM spawn_decorations ORDER BY id"
        ) { rs ->
            DecorationRow(
                rs.getInt("id"), rs.getString("type"), rs.getString("world"),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getString("data"),
                yaw = rs.getFloat("yaw"),
                locked = rs.getInt("locked") == 1,
                scale = rs.getFloat("scale").let { if (it <= 0f) 1f else it }
            )
        }
    }

    /**
     * Remove every entity in [world] tagged with our spawn-decor scoreboard tag.
     * Optionally filter by type ("logo", "hologram", "particle", "beacon", or "all").
     *
     * This ONLY touches entities tagged "joshymc_spawn_decor" — no NPCs, no
     * holograms from /holo, no other plugin entities are affected.
     *
     * Returns the number of entities removed.
     */
    fun cleanupOrphans(world: org.bukkit.World, typeFilter: String = "all"): Int {
        // Pre-compute the set of decor IDs we want to keep alive (none, since we re-spawn after)
        // and the per-id type lookup so we can match by type.
        val typeById: Map<Int, String> = listDecorations().associate { it.id to it.type }

        var removed = 0
        for (entity in world.entities) {
            // Only entities we tagged
            if (!entity.scoreboardTags.contains(TAG)) continue

            // Find which decor this entity belongs to (joshymc_decor_<id>)
            val decorTag = entity.scoreboardTags.firstOrNull { it.startsWith("joshymc_decor_") }
            if (typeFilter != "all" && decorTag != null) {
                val id = decorTag.removePrefix("joshymc_decor_").toIntOrNull()
                val entityType = typeById[id] ?: "unknown"
                // Both "logo" and "hologram" share the spawnHologramEntities path; treat them as one
                val matches = when (typeFilter) {
                    "logo" -> entityType == "logo"
                    "hologram" -> entityType == "hologram"
                    "particle" -> entityType == "particle"
                    "beacon" -> entityType == "beacon"
                    "firework" -> entityType == "firework"
                    else -> true
                }
                if (!matches) continue
            }

            entity.remove()
            removed++
        }

        // Clear in-memory tracking so loadAll() can re-spawn cleanly
        spawnedEntities.clear()
        return removed
    }

    /** Set the visual scale of a decoration. Returns true if the row exists. */
    fun setScale(id: Int, scale: Float): Boolean {
        if (scale <= 0f) return false
        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE spawn_decorations SET scale = ? WHERE id = ?", scale, id
        )
        if (rows == 0) return false
        respawnById(id)
        return true
    }

    /**
     * Lock a decoration to face one direction. yaw in degrees:
     * 0=south, 90=west, 180=north, 270=east. Pass null to unlock.
     */
    fun setRotation(id: Int, yawDegrees: Float?): Boolean {
        val rows = if (yawDegrees == null) {
            plugin.databaseManager.executeUpdate("UPDATE spawn_decorations SET locked = 0 WHERE id = ?", id)
        } else {
            plugin.databaseManager.executeUpdate(
                "UPDATE spawn_decorations SET yaw = ?, locked = 1 WHERE id = ?", yawDegrees, id
            )
        }
        if (rows == 0) return false
        respawnById(id)
        return true
    }

    private fun respawnById(id: Int) {
        // Despawn current entities
        spawnedEntities.remove(id)?.forEach { uuid -> Bukkit.getEntity(uuid)?.remove() }

        // Reload the row from the DB
        val row = plugin.databaseManager.queryFirst(
            "SELECT id, type, world, x, y, z, data, yaw, locked, scale FROM spawn_decorations WHERE id = ?", id
        ) { rs ->
            DecorationRow(
                rs.getInt("id"), rs.getString("type"), rs.getString("world"),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getString("data"),
                yaw = rs.getFloat("yaw"),
                locked = rs.getInt("locked") == 1,
                scale = rs.getFloat("scale").let { if (it <= 0f) 1f else it }
            )
        } ?: return

        when (row.type) {
            "hologram", "logo" -> spawnHologramEntities(row)
        }
    }

    private fun restartParticleTask() {
        particleTask?.cancel()
        particleTask = null
        startParticleTask()
    }

    // ── Logo ────────────────────────────────────────────────

    fun addLogo(location: Location): Int {
        val data = "\uE000"
        plugin.databaseManager.execute(
            "INSERT INTO spawn_decorations (type, world, x, y, z, data) VALUES (?, ?, ?, ?, ?, ?)",
            "logo", location.world.name, location.x, location.y, location.z, data
        )
        val id = plugin.databaseManager.queryFirst(
            "SELECT last_insert_rowid() AS id"
        ) { it.getInt("id") } ?: -1

        val row = DecorationRow(id, "logo", location.world.name, location.x, location.y, location.z, data)
        spawnHologramEntities(row)
        return id
    }

    // ── First-join particle burst ───────────────────────────

    fun playJoinEffect(player: Player) {
        val loc = player.location
        val world = loc.world ?: return

        // Sphere burst of mixed particles
        for (i in 0..50) {
            val theta = Math.random() * Math.PI * 2
            val phi = Math.random() * Math.PI
            val r = 1.5
            val px = loc.x + cos(theta) * sin(phi) * r
            val py = loc.y + 1.0 + cos(phi) * r
            val pz = loc.z + sin(theta) * sin(phi) * r
            val particleLoc = Location(world, px, py, pz)

            when (i % 3) {
                0 -> world.spawnParticle(Particle.FIREWORK, particleLoc, 1, 0.0, 0.0, 0.0, 0.05)
                1 -> world.spawnParticle(Particle.END_ROD, particleLoc, 1, 0.0, 0.0, 0.0, 0.02)
                2 -> world.spawnParticle(Particle.ENCHANT, particleLoc, 1, 0.0, 0.0, 0.0, 0.5)
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!player.hasPlayedBefore()) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (player.isOnline) playJoinEffect(player)
            }, 40L) // 2-second delay
        }
    }

    // ── Command ─────────────────────────────────────────────

    inner class SpawnDecorCommand : CommandExecutor, TabCompleter {

        override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
            if (sender !is Player) {
                sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED))
                return true
            }

            if (!sender.hasPermission("joshymc.spawndecor")) {
                plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
                return true
            }

            if (args.isEmpty()) {
                sendUsage(sender)
                return true
            }

            val group = args[0].lowercase()

            // Universal commands
            when (group) {
                "logo" -> { handleLogo(sender, args); return true }
                "remove" -> { handleUniversalRemove(sender, args); return true }
                "move" -> { handleMove(sender, args); return true }
                "edit" -> { handleEdit(sender, args); return true }
                "list" -> { handleUniversalList(sender); return true }
                "near" -> { handleNear(sender); return true }
                "scale" -> { handleScale(sender, args); return true }
                "rotation" -> { handleRotation(sender, args); return true }
                "cleanup" -> { handleCleanup(sender, args); return true }
            }

            if (args.size < 2) {
                sendUsage(sender)
                return true
            }

            val action = args[1].lowercase()

            when (group) {
                "hologram" -> handleHologram(sender, action, args)
                "particle" -> handleParticle(sender, action, args)
                "beacon" -> handleBeacon(sender, action, args)
                "firework" -> handleFirework(sender, action, args)
                else -> sendUsage(sender)
            }
            return true
        }

        private fun handleLogo(player: Player, args: Array<out String>) {
            val loc = if (args.size >= 4) {
                val x = args[1].toDoubleOrNull()
                val y = args[2].toDoubleOrNull()
                val z = args[3].toDoubleOrNull()
                if (x == null || y == null || z == null) {
                    plugin.commsManager.send(player, Component.text("Usage: /spawndecor logo [x y z]", NamedTextColor.RED))
                    return
                }
                Location(player.world, x, y, z)
            } else {
                player.location
            }
            val id = addLogo(loc)
            plugin.commsManager.send(player, Component.text("Logo hologram #$id created.", NamedTextColor.GREEN))
        }

        private fun handleHologram(player: Player, action: String, args: Array<out String>) {
            when (action) {
                "add" -> {
                    if (args.size < 3) {
                        plugin.commsManager.send(player, Component.text("Usage: /spawndecor hologram add <text>", NamedTextColor.RED))
                        return
                    }
                    val text = args.drop(2).joinToString(" ")
                    val id = addHologram(player.location, text)
                    plugin.commsManager.send(player, Component.text("Hologram #$id created.", NamedTextColor.GREEN))
                }
                "remove" -> {
                    val id = args.getOrNull(2)?.toIntOrNull()
                    if (id == null) {
                        plugin.commsManager.send(player, Component.text("Usage: /spawndecor hologram remove <id>", NamedTextColor.RED))
                        return
                    }
                    if (removeDecoration(id)) {
                        plugin.commsManager.send(player, Component.text("Decoration #$id removed.", NamedTextColor.GREEN))
                    } else {
                        plugin.commsManager.send(player, Component.text("Decoration #$id not found.", NamedTextColor.RED))
                    }
                }
                "list" -> showList(player)
                else -> sendUsage(player)
            }
        }

        private fun handleParticle(player: Player, action: String, args: Array<out String>) {
            when (action) {
                "add" -> {
                    val type = args.getOrNull(2)?.lowercase()
                    if (type == null || type !in PARTICLE_TYPES) {
                        plugin.commsManager.send(player, Component.text(
                            "Usage: /spawndecor particle add <type> [radius] [count] [speed] [animation]", NamedTextColor.RED))
                        return
                    }
                    val radius = args.getOrNull(3)?.toDoubleOrNull() ?: 1.0
                    val count = args.getOrNull(4)?.toIntOrNull() ?: 5
                    val speed = args.getOrNull(5)?.toDoubleOrNull() ?: 0.0
                    val animation = args.getOrNull(6)?.lowercase() ?: "static"
                    if (animation !in ANIMATION_TYPES) {
                        plugin.commsManager.send(player, Component.text(
                            "Invalid animation. Options: ${ANIMATION_TYPES.joinToString(", ")}", NamedTextColor.RED))
                        return
                    }
                    val id = addParticle(player.location, type, radius, count, speed, animation)
                    plugin.commsManager.send(player, Component.text("Particle emitter #$id created ($type, $animation).", NamedTextColor.GREEN))
                }
                "remove" -> {
                    val id = args.getOrNull(2)?.toIntOrNull()
                    if (id == null) {
                        plugin.commsManager.send(player, Component.text("Usage: /spawndecor particle remove <id>", NamedTextColor.RED))
                        return
                    }
                    if (removeDecoration(id)) {
                        plugin.commsManager.send(player, Component.text("Decoration #$id removed.", NamedTextColor.GREEN))
                    } else {
                        plugin.commsManager.send(player, Component.text("Decoration #$id not found.", NamedTextColor.RED))
                    }
                }
                "list" -> showList(player)
                else -> sendUsage(player)
            }
        }

        private fun handleFirework(player: Player, action: String, args: Array<out String>) {
            when (action) {
                "add" -> {
                    val interval = args.getOrNull(2)?.toIntOrNull() ?: 4
                    val burst = args.getOrNull(3)?.toIntOrNull() ?: 3
                    val power = args.getOrNull(4)?.toIntOrNull() ?: 1
                    val colors = args.getOrNull(5) ?: "red,blue,white"
                    val id = addFirework(player.location, interval, burst, power, colors)
                    plugin.commsManager.send(player, Component.text("Firework launcher #$id created.", NamedTextColor.GREEN))
                    plugin.commsManager.send(player,
                        Component.text("  Interval: ${interval}s, burst: $burst, power: $power, colors: $colors", NamedTextColor.GRAY))
                    plugin.commsManager.send(player,
                        Component.text("  Edit with /spawndecor firework edit $id <prop> <value>", NamedTextColor.DARK_GRAY))
                }
                "line" -> handleFireworkLine(player, args)
                "edit" -> handleFireworkEdit(player, args)
                "trigger" -> handleFireworkTrigger(player, args)
                "remove" -> {
                    val id = args.getOrNull(2)?.toIntOrNull()
                    if (id == null) {
                        plugin.commsManager.send(player, Component.text("Usage: /spawndecor firework remove <id>", NamedTextColor.RED))
                        return
                    }
                    if (removeDecoration(id)) {
                        plugin.commsManager.send(player, Component.text("Decoration #$id removed.", NamedTextColor.GREEN))
                    } else {
                        plugin.commsManager.send(player, Component.text("Decoration #$id not found.", NamedTextColor.RED))
                    }
                }
                "list" -> showList(player)
                else -> sendFireworkUsage(player)
            }
        }

        private fun sendFireworkUsage(player: Player) {
            val msgs = listOf(
                "&6&l--- Firework Spawn Decor ---",
                "&e/spawndecor firework add [interval] [burst] [power] [colors]",
                "&7  Adds a single emitter at your location",
                "&e/spawndecor firework line <x2> <y2> <z2> [step] [pattern] [interval] [burst] [power] [colors]",
                "&7  Adds a line emitter from your location to (x2,y2,z2)",
                "&7  Patterns: simultaneous, sequential, pingpong, wave, splatter, center_out, outside_in",
                "&e/spawndecor firework edit <id> <property> <value>",
                "&7  Properties: interval, burst, power, colors, fade, effect, flicker, trail, sync, step, pattern",
                "&7  Effects: ball, ball_large, star, burst, creeper, random",
                "&e/spawndecor firework trigger <event> <id>",
                "&7  Events: first_join, join, death, levelup, vote",
                "&e/spawndecor firework remove <id>",
                "&e/spawndecor firework list"
            )
            for (m in msgs) {
                player.sendMessage(plugin.commsManager.parseLegacy(m))
            }
        }

        private fun handleFireworkLine(player: Player, args: Array<out String>) {
            // /spawndecor firework line <x2> <y2> <z2> [step] [pattern] [interval] [burst] [power] [colors]
            if (args.size < 5) {
                plugin.commsManager.send(player, Component.text("Usage: /spawndecor firework line <x2> <y2> <z2> [step] [pattern] [interval] [burst] [power] [colors]", NamedTextColor.RED))
                plugin.commsManager.send(player, Component.text("  Patterns: simultaneous, sequential, pingpong, wave, splatter, center_out, outside_in", NamedTextColor.GRAY))
                return
            }
            val x2 = args[2].toDoubleOrNull()
            val y2 = args[3].toDoubleOrNull()
            val z2 = args[4].toDoubleOrNull()
            if (x2 == null || y2 == null || z2 == null) {
                plugin.commsManager.send(player, Component.text("End coordinates must be numbers.", NamedTextColor.RED))
                return
            }
            val step = args.getOrNull(5)?.toDoubleOrNull() ?: 2.0
            val pattern = args.getOrNull(6) ?: "simultaneous"
            val interval = args.getOrNull(7)?.toIntOrNull() ?: 4
            val burst = args.getOrNull(8)?.toIntOrNull() ?: 1
            val power = args.getOrNull(9)?.toIntOrNull() ?: 1
            val colors = args.getOrNull(10) ?: "red,blue,white"

            val id = addFireworkLine(
                player.world.name,
                player.location.x, player.location.y, player.location.z,
                x2, y2, z2,
                interval, burst, power, colors,
                step, pattern
            )
            plugin.commsManager.send(player,
                Component.text("Firework line #$id created from your position to ($x2, $y2, $z2).", NamedTextColor.GREEN))
            plugin.commsManager.send(player,
                Component.text("  Step: $step, pattern: $pattern, interval: ${interval}s", NamedTextColor.GRAY))
        }

        private fun handleFireworkEdit(player: Player, args: Array<out String>) {
            // /spawndecor firework edit <id> <property> <value>
            if (args.size < 5) {
                plugin.commsManager.send(player, Component.text("Usage: /spawndecor firework edit <id> <property> <value>", NamedTextColor.RED))
                plugin.commsManager.send(player, Component.text("  Properties: interval, burst, power, colors, fade, effect, flicker, trail, sync, step, pattern", NamedTextColor.GRAY))
                return
            }
            val id = args[2].toIntOrNull()
            val property = args[3]
            val value = args.drop(4).joinToString(" ")
            if (id == null) {
                plugin.commsManager.send(player, Component.text("Id must be a number.", NamedTextColor.RED))
                return
            }
            if (editFirework(id, property, value)) {
                plugin.commsManager.send(player,
                    Component.text("Updated firework #$id: $property = $value", NamedTextColor.GREEN))
            } else {
                plugin.commsManager.send(player,
                    Component.text("Firework #$id not found or invalid property '$property'.", NamedTextColor.RED))
            }
        }

        private fun handleFireworkTrigger(player: Player, args: Array<out String>) {
            // /spawndecor firework trigger <event> <id>
            if (args.size < 4) {
                plugin.commsManager.send(player, Component.text("Usage: /spawndecor firework trigger <event> <id>", NamedTextColor.RED))
                plugin.commsManager.send(player, Component.text("  Events: first_join, join, death, levelup, vote", NamedTextColor.GRAY))
                plugin.commsManager.send(player, Component.text("  e.g. /spawndecor firework trigger first_join 5", NamedTextColor.DARK_GRAY))
                return
            }
            val event = args[2].lowercase()
            val id = args[3].toIntOrNull()
            if (id == null) {
                plugin.commsManager.send(player, Component.text("Id must be a number.", NamedTextColor.RED))
                return
            }
            if (event !in setOf("first_join", "join", "death", "levelup", "vote", "none")) {
                plugin.commsManager.send(player, Component.text("Unknown event '$event'.", NamedTextColor.RED))
                return
            }
            if (event == "none") {
                if (removeTrigger(id)) {
                    plugin.commsManager.send(player, Component.text("Trigger removed from firework #$id.", NamedTextColor.GREEN))
                }
            } else {
                if (setTrigger(id, event)) {
                    plugin.commsManager.send(player,
                        Component.text("Firework #$id will now fire on '$event' events.", NamedTextColor.GREEN))
                } else {
                    plugin.commsManager.send(player, Component.text("Firework #$id not found.", NamedTextColor.RED))
                }
            }
        }

        private fun handleBeacon(player: Player, action: String, args: Array<out String>) {
            when (action) {
                "add" -> {
                    val color = args.getOrNull(2)?.lowercase()
                    if (color == null || color !in BEACON_COLORS) {
                        plugin.commsManager.send(player, Component.text(
                            "Usage: /spawndecor beacon add <${BEACON_COLORS.keys.joinToString("|")}>", NamedTextColor.RED))
                        return
                    }
                    val id = addBeacon(player.location, color)
                    plugin.commsManager.send(player, Component.text("Beacon beam #$id created ($color).", NamedTextColor.GREEN))
                }
                "remove" -> {
                    val id = args.getOrNull(2)?.toIntOrNull()
                    if (id == null) {
                        plugin.commsManager.send(player, Component.text("Usage: /spawndecor beacon remove <id>", NamedTextColor.RED))
                        return
                    }
                    if (removeDecoration(id)) {
                        plugin.commsManager.send(player, Component.text("Decoration #$id removed.", NamedTextColor.GREEN))
                    } else {
                        plugin.commsManager.send(player, Component.text("Decoration #$id not found.", NamedTextColor.RED))
                    }
                }
                "list" -> showList(player)
                else -> sendUsage(player)
            }
        }

        private fun showList(player: Player) {
            val rows = listDecorations()
            if (rows.isEmpty()) {
                plugin.commsManager.send(player, Component.text("No spawn decorations.", NamedTextColor.GRAY))
                return
            }
            plugin.commsManager.send(player, Component.text("Spawn Decorations:", NamedTextColor.GOLD))
            for (row in rows) {
                val info = when (row.type) {
                    "hologram" -> "\"${row.data}\""
                    "particle" -> row.data ?: "unknown"
                    "beacon" -> row.data ?: "unknown"
                    "logo" -> "logo"
                    else -> row.type
                }
                plugin.commsManager.send(player, Component.text(
                    "  #${row.id} [${row.type}] $info (${row.world} ${row.x.toInt()}, ${row.y.toInt()}, ${row.z.toInt()})",
                    NamedTextColor.YELLOW
                ))
            }
        }

        // ── Universal commands ────────────────────────────

        private fun handleCleanup(sender: Player, args: Array<out String>) {
            val typeFilter = args.getOrNull(1)?.lowercase() ?: "all"
            val validTypes = setOf("all", "logo", "hologram", "particle", "beacon", "firework")
            if (typeFilter !in validTypes) {
                plugin.commsManager.send(sender, Component.text("Usage: /spawndecor cleanup [all|logo|hologram|particle|beacon]", NamedTextColor.RED))
                plugin.commsManager.send(sender, Component.text("  This removes spawn decor entities only — nothing else is touched.", NamedTextColor.GRAY))
                return
            }

            val removed = cleanupOrphans(sender.world, typeFilter)
            plugin.commsManager.send(
                sender,
                Component.text("Removed $removed spawn-decor entit${if (removed != 1) "ies" else "y"}", NamedTextColor.GREEN)
                    .append(if (typeFilter != "all") Component.text(" of type '$typeFilter'", NamedTextColor.GRAY) else Component.empty())
                    .append(Component.text(" from ${sender.world.name}.", NamedTextColor.GREEN))
            )
            // Re-spawn tracked decorations so legitimate ones come back
            loadAll()
        }

        private fun handleScale(sender: Player, args: Array<out String>) {
            if (args.size < 3) {
                plugin.commsManager.send(sender, Component.text("Usage: /spawndecor scale <id> <size>", NamedTextColor.RED))
                plugin.commsManager.send(sender, Component.text("  e.g. /spawndecor scale 1 2.5  (1.0 = normal, 0.5 = half)", NamedTextColor.GRAY))
                return
            }
            val id = args[1].toIntOrNull()
            val scale = args[2].toFloatOrNull()
            if (id == null || scale == null || scale <= 0f) {
                plugin.commsManager.send(sender, Component.text("Invalid id or scale.", NamedTextColor.RED))
                return
            }
            if (setScale(id, scale)) {
                plugin.commsManager.send(sender, Component.text("Decoration #$id scale set to $scale.", NamedTextColor.GREEN))
            } else {
                plugin.commsManager.send(sender, Component.text("Decoration #$id not found.", NamedTextColor.RED))
            }
        }

        private fun handleRotation(sender: Player, args: Array<out String>) {
            if (args.size < 3) {
                plugin.commsManager.send(sender, Component.text("Usage: /spawndecor rotation <id> <degrees|unlock>", NamedTextColor.RED))
                plugin.commsManager.send(sender, Component.text("  Yaw degrees: 0=south, 90=west, 180=north, 270=east", NamedTextColor.GRAY))
                plugin.commsManager.send(sender, Component.text("  Use 'here' to lock to your current facing direction.", NamedTextColor.GRAY))
                return
            }
            val id = args[1].toIntOrNull()
            if (id == null) {
                plugin.commsManager.send(sender, Component.text("Id must be a number.", NamedTextColor.RED))
                return
            }
            val arg = args[2].lowercase()

            if (arg == "unlock" || arg == "off") {
                if (setRotation(id, null)) {
                    plugin.commsManager.send(sender, Component.text("Decoration #$id now follows the player (unlocked).", NamedTextColor.GREEN))
                } else {
                    plugin.commsManager.send(sender, Component.text("Decoration #$id not found.", NamedTextColor.RED))
                }
                return
            }

            val degrees: Float = if (arg == "here") {
                // Use player's current yaw + 180 so the logo FACES the player from where they're standing
                (sender.location.yaw + 180f) % 360f
            } else {
                arg.toFloatOrNull() ?: run {
                    plugin.commsManager.send(sender, Component.text("Rotation must be a number, 'here', or 'unlock'.", NamedTextColor.RED))
                    return
                }
            }

            if (setRotation(id, degrees)) {
                plugin.commsManager.send(sender, Component.text("Decoration #$id locked to ${"%.1f".format(degrees)}°.", NamedTextColor.GREEN))
            } else {
                plugin.commsManager.send(sender, Component.text("Decoration #$id not found.", NamedTextColor.RED))
            }
        }

        private fun handleUniversalRemove(sender: Player, args: Array<out String>) {
            if (args.size < 2) {
                plugin.commsManager.send(sender, Component.text("Usage: /spawndecor remove <id>", NamedTextColor.RED))
                return
            }
            val id = args[1].toIntOrNull()
            if (id == null) {
                plugin.commsManager.send(sender, Component.text("Invalid ID.", NamedTextColor.RED))
                return
            }
            if (removeDecoration(id)) {
                plugin.commsManager.send(sender, Component.text("Decoration #$id removed.", NamedTextColor.GREEN))
            } else {
                plugin.commsManager.send(sender, Component.text("Decoration #$id not found.", NamedTextColor.RED))
            }
        }

        private fun handleMove(sender: Player, args: Array<out String>) {
            if (args.size < 2) {
                plugin.commsManager.send(sender, Component.text("Usage: /spawndecor move <id> [x y z]", NamedTextColor.RED))
                return
            }
            val id = args[1].toIntOrNull()
            if (id == null) {
                plugin.commsManager.send(sender, Component.text("Invalid ID.", NamedTextColor.RED))
                return
            }

            val loc = if (args.size >= 5) {
                val x = args[2].toDoubleOrNull(); val y = args[3].toDoubleOrNull(); val z = args[4].toDoubleOrNull()
                if (x == null || y == null || z == null) { plugin.commsManager.send(sender, Component.text("Invalid coordinates.", NamedTextColor.RED)); return }
                Location(sender.world, x, y, z)
            } else {
                sender.location
            }

            // Update DB
            plugin.databaseManager.execute(
                "UPDATE spawn_decorations SET world = ?, x = ?, y = ?, z = ? WHERE id = ?",
                loc.world.name, loc.x, loc.y, loc.z, id
            )

            // Remove old entities and reload
            spawnedEntities.remove(id)?.forEach { uuid -> Bukkit.getEntity(uuid)?.remove() }
            particleEmitters.removeAll { it.id == id }
            beaconEmitters.removeAll { it.id == id }

            // Reload this decoration
            val row = plugin.databaseManager.queryFirst(
                "SELECT id, type, world, x, y, z, data FROM spawn_decorations WHERE id = ?", id
            ) { rs -> DecorationRow(rs.getInt("id"), rs.getString("type"), rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getString("data")) }

            if (row != null) {
                when (row.type) {
                    "hologram", "logo" -> spawnHologramEntities(row)
                    "particle" -> { particleEmitters.add(row); restartParticleTask() }
                    "beacon" -> { beaconEmitters.add(row); restartParticleTask() }
                    "firework" -> { fireworkEmitters.add(row); restartParticleTask() }
                }
            }

            plugin.commsManager.send(sender, Component.text("Decoration #$id moved to ${loc.blockX}, ${loc.blockY}, ${loc.blockZ}.", NamedTextColor.GREEN))
        }

        private fun handleUniversalList(sender: Player) {
            val decorations = listDecorations()
            if (decorations.isEmpty()) {
                plugin.commsManager.send(sender, Component.text("No decorations.", NamedTextColor.GRAY))
                return
            }
            sender.sendMessage(Component.text("--- Decorations (${decorations.size}) ---", NamedTextColor.GOLD).decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true))
            for (row in decorations) {
                val info = when (row.type) {
                    "hologram" -> "\"${row.data}\""
                    "logo" -> "logo"
                    "particle" -> row.data ?: "unknown"
                    "beacon" -> row.data ?: "unknown"
                    else -> row.data ?: ""
                }
                sender.sendMessage(
                    Component.text("  #${row.id} ", NamedTextColor.GOLD)
                        .append(Component.text("[${row.type}] ", NamedTextColor.YELLOW))
                        .append(Component.text("${row.world} ${row.x.toInt()},${row.y.toInt()},${row.z.toInt()} ", NamedTextColor.GRAY))
                        .append(Component.text(info, NamedTextColor.DARK_GRAY))
                )
            }
        }

        private fun handleNear(sender: Player) {
            val decorations = listDecorations()
            val nearby = decorations.filter { row ->
                row.world == sender.world.name &&
                    sender.location.distanceSquared(Location(sender.world, row.x, row.y, row.z)) < 400 // 20 blocks
            }
            if (nearby.isEmpty()) {
                plugin.commsManager.send(sender, Component.text("No decorations within 20 blocks.", NamedTextColor.GRAY))
                return
            }
            sender.sendMessage(Component.text("--- Nearby (${nearby.size}) ---", NamedTextColor.GOLD))
            for (row in nearby) {
                val dist = sender.location.distance(Location(sender.world, row.x, row.y, row.z))
                sender.sendMessage(
                    Component.text("  #${row.id} ", NamedTextColor.GOLD)
                        .append(Component.text("[${row.type}] ", NamedTextColor.YELLOW))
                        .append(Component.text("${"%.1f".format(dist)}m away", NamedTextColor.GRAY))
                )
            }
        }

        /**
         * /spawndecor edit <id> <property> <value>
         * Properties: text, type, radius, count, speed, animation, color
         */
        private fun handleEdit(sender: Player, args: Array<out String>) {
            if (args.size < 4) {
                plugin.commsManager.send(sender, Component.text("Usage: /spawndecor edit <id> <property> <value>", NamedTextColor.RED))
                plugin.commsManager.send(sender, Component.text("  Properties: text, type, radius, count, speed, animation, color", NamedTextColor.GRAY))
                return
            }

            val id = args[1].toIntOrNull()
            if (id == null) { plugin.commsManager.send(sender, Component.text("Invalid ID.", NamedTextColor.RED)); return }

            val row = plugin.databaseManager.queryFirst(
                "SELECT id, type, world, x, y, z, data FROM spawn_decorations WHERE id = ?", id
            ) { rs -> DecorationRow(rs.getInt("id"), rs.getString("type"), rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getString("data")) }

            if (row == null) { plugin.commsManager.send(sender, Component.text("Decoration #$id not found.", NamedTextColor.RED)); return }

            val property = args[2].lowercase()
            val value = args.drop(3).joinToString(" ")

            when (row.type) {
                "hologram", "logo" -> {
                    if (property == "text") {
                        plugin.databaseManager.execute("UPDATE spawn_decorations SET data = ? WHERE id = ?", value, id)
                        // Respawn
                        spawnedEntities.remove(id)?.forEach { uuid ->
                            for (w in Bukkit.getWorlds()) w.entities.filter { it.uniqueId == uuid }.forEach { it.remove() }
                        }
                        val updated = row.copy(data = value)
                        spawnHologramEntities(updated)
                        plugin.commsManager.send(sender, Component.text("Text updated.", NamedTextColor.GREEN))
                    } else {
                        plugin.commsManager.send(sender, Component.text("Holograms/logos only support: text", NamedTextColor.RED))
                    }
                }
                "particle" -> {
                    val rawData = row.data ?: "end_rod"
                    val parts = rawData.split(";")
                    val typeName = parts[0]
                    val props = parts.drop(1).associate {
                        val (k, v) = it.split("=", limit = 2)
                        k to v
                    }.toMutableMap()

                    when (property) {
                        "type" -> {
                            if (value !in PARTICLE_TYPES) {
                                plugin.commsManager.send(sender, Component.text("Unknown type. Available: ${PARTICLE_TYPES.keys.joinToString()}", NamedTextColor.RED))
                                return
                            }
                            val newData = "$value;radius=${props["radius"] ?: "1.0"};count=${props["count"] ?: "5"};speed=${props["speed"] ?: "0.0"};animation=${props["animation"] ?: "static"}"
                            plugin.databaseManager.execute("UPDATE spawn_decorations SET data = ? WHERE id = ?", newData, id)
                        }
                        "radius", "count", "speed" -> {
                            props[property] = value
                            val newData = "$typeName;radius=${props["radius"] ?: "1.0"};count=${props["count"] ?: "5"};speed=${props["speed"] ?: "0.0"};animation=${props["animation"] ?: "static"}"
                            plugin.databaseManager.execute("UPDATE spawn_decorations SET data = ? WHERE id = ?", newData, id)
                        }
                        "animation" -> {
                            if (value !in ANIMATION_TYPES) {
                                plugin.commsManager.send(sender, Component.text("Unknown animation. Available: ${ANIMATION_TYPES.joinToString()}", NamedTextColor.RED))
                                return
                            }
                            props["animation"] = value
                            val newData = "$typeName;radius=${props["radius"] ?: "1.0"};count=${props["count"] ?: "5"};speed=${props["speed"] ?: "0.0"};animation=$value"
                            plugin.databaseManager.execute("UPDATE spawn_decorations SET data = ? WHERE id = ?", newData, id)
                        }
                        else -> {
                            plugin.commsManager.send(sender, Component.text("Particle properties: type, radius, count, speed, animation", NamedTextColor.RED))
                            return
                        }
                    }
                    // Reload particle
                    particleEmitters.removeAll { it.id == id }
                    val updatedRow = plugin.databaseManager.queryFirst(
                        "SELECT id, type, world, x, y, z, data FROM spawn_decorations WHERE id = ?", id
                    ) { rs -> DecorationRow(rs.getInt("id"), rs.getString("type"), rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getString("data")) }
                    if (updatedRow != null) particleEmitters.add(updatedRow)
                    restartParticleTask()
                    plugin.commsManager.send(sender, Component.text("Particle $property updated to $value.", NamedTextColor.GREEN))
                }
                "beacon" -> {
                    if (property == "color") {
                        if (value !in BEACON_COLORS) {
                            plugin.commsManager.send(sender, Component.text("Unknown color. Available: ${BEACON_COLORS.keys.joinToString()}", NamedTextColor.RED))
                            return
                        }
                        plugin.databaseManager.execute("UPDATE spawn_decorations SET data = ? WHERE id = ?", value, id)
                        beaconEmitters.removeAll { it.id == id }
                        beaconEmitters.add(row.copy(data = value))
                        restartParticleTask()
                        plugin.commsManager.send(sender, Component.text("Beacon color updated.", NamedTextColor.GREEN))
                    } else {
                        plugin.commsManager.send(sender, Component.text("Beacons only support: color", NamedTextColor.RED))
                    }
                }
            }
        }

        private fun sendUsage(player: Player) {
            plugin.commsManager.send(player, Component.text("--- Spawn Decorations ---", NamedTextColor.GOLD).decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true))
            plugin.commsManager.send(player, Component.text("  /spawndecor hologram add <text>", NamedTextColor.YELLOW))
            plugin.commsManager.send(player, Component.text("  /spawndecor particle add <type> [radius] [count] [speed] [animation]", NamedTextColor.YELLOW))
            plugin.commsManager.send(player, Component.text("    Animations: static, spiral, ring, fountain, vortex", NamedTextColor.DARK_GRAY))
            plugin.commsManager.send(player, Component.text("  /spawndecor beacon add <color>", NamedTextColor.YELLOW))
            plugin.commsManager.send(player, Component.text("  /spawndecor firework add [interval] [burst] [power] [colors]", NamedTextColor.YELLOW)
                .append(Component.text(" — single emitter", NamedTextColor.GRAY)))
            plugin.commsManager.send(player, Component.text("  /spawndecor firework line <x2> <y2> <z2> [step] [pattern]", NamedTextColor.YELLOW)
                .append(Component.text(" — line of emitters with patterns", NamedTextColor.GRAY)))
            plugin.commsManager.send(player, Component.text("  /spawndecor firework edit <id> <prop> <value>", NamedTextColor.YELLOW)
                .append(Component.text(" — change colors/size/burst/etc.", NamedTextColor.GRAY)))
            plugin.commsManager.send(player, Component.text("  /spawndecor firework trigger <event> <id>", NamedTextColor.YELLOW)
                .append(Component.text(" — fire on first_join/join/death/levelup/vote", NamedTextColor.GRAY)))
            plugin.commsManager.send(player, Component.text("  /spawndecor logo [x y z]", NamedTextColor.YELLOW))
            plugin.commsManager.send(player, Component.text("  /spawndecor remove <id>", NamedTextColor.YELLOW).append(Component.text(" — remove any decoration", NamedTextColor.GRAY)))
            plugin.commsManager.send(player, Component.text("  /spawndecor move <id> [x y z]", NamedTextColor.YELLOW).append(Component.text(" — move to location", NamedTextColor.GRAY)))
            plugin.commsManager.send(player, Component.text("  /spawndecor edit <id> <property> <value>", NamedTextColor.YELLOW).append(Component.text(" — edit properties", NamedTextColor.GRAY)))
            plugin.commsManager.send(player, Component.text("  /spawndecor scale <id> <size>", NamedTextColor.YELLOW).append(Component.text(" — resize a logo/hologram", NamedTextColor.GRAY)))
            plugin.commsManager.send(player, Component.text("    e.g. 0.5 = half, 1.0 = normal, 3.0 = triple", NamedTextColor.DARK_GRAY))
            plugin.commsManager.send(player, Component.text("  /spawndecor rotation <id> <degrees|here|unlock>", NamedTextColor.YELLOW).append(Component.text(" — lock facing direction", NamedTextColor.GRAY)))
            plugin.commsManager.send(player, Component.text("    'here' faces you from your standing position", NamedTextColor.DARK_GRAY))
            plugin.commsManager.send(player, Component.text("    Yaw: 0=south, 90=west, 180=north, 270=east", NamedTextColor.DARK_GRAY))
            plugin.commsManager.send(player, Component.text("  /spawndecor cleanup [all|logo|hologram|particle|beacon]", NamedTextColor.YELLOW).append(Component.text(" — kill orphaned spawn-decor only", NamedTextColor.GRAY)))
            plugin.commsManager.send(player, Component.text("  /spawndecor list", NamedTextColor.YELLOW).append(Component.text(" — list all decorations", NamedTextColor.GRAY)))
            plugin.commsManager.send(player, Component.text("  /spawndecor near", NamedTextColor.YELLOW).append(Component.text(" — show nearby decorations", NamedTextColor.GRAY)))
        }

        override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
            if (!sender.hasPermission("joshymc.spawndecor")) return emptyList()

            return when (args.size) {
                1 -> listOf("hologram", "particle", "beacon", "firework", "logo", "remove", "move", "edit", "scale", "rotation", "cleanup", "list", "near").filter { it.startsWith(args[0], true) }
                2 -> {
                    val g = args[0].lowercase()
                    if (g == "logo" || g == "list" || g == "near") return emptyList()
                    if (g == "cleanup") {
                        return listOf("all", "logo", "hologram", "particle", "beacon", "firework").filter { it.startsWith(args[1], true) }
                    }
                    if (g == "firework") {
                        return listOf("add", "line", "edit", "trigger", "remove", "list").filter { it.startsWith(args[1], true) }
                    }
                    if (g == "remove" || g == "move" || g == "edit" || g == "scale" || g == "rotation") {
                        return listDecorations().map { it.id.toString() }.filter { it.startsWith(args[1]) }
                    }
                    listOf("add", "remove", "list").filter { it.startsWith(args[1], true) }
                }
                3 -> {
                    val group = args[0].lowercase()
                    val action = args[1].lowercase()
                    when {
                        group == "scale" -> listOf("0.5", "1.0", "1.5", "2.0", "3.0", "5.0").filter { it.startsWith(args[2]) }
                        group == "rotation" -> listOf("0", "90", "180", "270", "here", "unlock").filter { it.startsWith(args[2], true) }
                        group == "edit" -> listOf("text", "type", "radius", "count", "speed", "animation", "color").filter { it.startsWith(args[2], true) }
                        group == "firework" && action == "edit" ->
                            fireworkEmitters.map { it.id.toString() }.filter { it.startsWith(args[2]) }
                        group == "firework" && action == "remove" ->
                            fireworkEmitters.map { it.id.toString() }.filter { it.startsWith(args[2]) }
                        group == "firework" && action == "trigger" ->
                            listOf("first_join", "join", "death", "levelup", "vote", "none").filter { it.startsWith(args[2], true) }
                        action == "remove" -> listDecorations()
                            .filter { it.type == group || group == "hologram" && it.type == "logo" }
                            .map { it.id.toString() }
                            .filter { it.startsWith(args[2], true) }
                        group == "particle" && action == "add" ->
                            PARTICLE_TYPES.keys.filter { it.startsWith(args[2], true) }
                        group == "beacon" && action == "add" ->
                            BEACON_COLORS.keys.filter { it.startsWith(args[2], true) }
                        else -> emptyList()
                    }
                }
                4 -> {
                    val group = args[0].lowercase()
                    val action = args[1].lowercase()
                    if (group == "firework" && action == "edit") {
                        return listOf("interval", "burst", "power", "colors", "fade", "effect",
                            "flicker", "trail", "sync", "step", "pattern").filter { it.startsWith(args[3], true) }
                    }
                    if (group == "firework" && action == "trigger") {
                        return fireworkEmitters.map { it.id.toString() }.filter { it.startsWith(args[3]) }
                    }
                    val prop = args[2].lowercase()
                    if (group == "edit") {
                        return when (prop) {
                            "type" -> PARTICLE_TYPES.keys.filter { it.startsWith(args[3], true) }
                            "animation" -> ANIMATION_TYPES.filter { it.startsWith(args[3], true) }
                            "color" -> BEACON_COLORS.keys.filter { it.startsWith(args[3], true) }
                            "radius" -> listOf("0.5", "1", "2", "3", "5").filter { it.startsWith(args[3]) }
                            "count" -> listOf("3", "5", "10", "20", "50").filter { it.startsWith(args[3]) }
                            "speed" -> listOf("0", "0.05", "0.1", "0.2").filter { it.startsWith(args[3]) }
                            else -> emptyList()
                        }
                    }
                    if (group == "particle" && action == "add")
                        listOf("0.5", "1", "2", "3", "5").filter { it.startsWith(args[3], true) }
                    else emptyList()
                }
                5 -> {
                    val group = args[0].lowercase()
                    val action = args[1].lowercase()
                    if (group == "particle" && action == "add")
                        listOf("3", "5", "10", "20").filter { it.startsWith(args[4], true) }
                    else emptyList()
                }
                6 -> {
                    val group = args[0].lowercase()
                    val action = args[1].lowercase()
                    if (group == "particle" && action == "add")
                        listOf("0", "0.05", "0.1", "0.2").filter { it.startsWith(args[5], true) }
                    else emptyList()
                }
                7 -> {
                    val group = args[0].lowercase()
                    val action = args[1].lowercase()
                    if (group == "particle" && action == "add")
                        ANIMATION_TYPES.filter { it.startsWith(args[6], true) }
                    else emptyList()
                }
                else -> emptyList()
            }
        }
    }
}
