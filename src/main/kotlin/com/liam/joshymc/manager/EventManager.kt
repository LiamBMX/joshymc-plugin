package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class EventManager(private val plugin: Joshymc) : Listener {

    // ── Config ────────────────────────────────────────────

    var eventWorldName: String = "event"
        private set
    var zoneMin: Location? = null
        private set
    var zoneMax: Location? = null
        private set
    var pvpEnabled: Boolean = false
        private set
    var buildEnabled: Boolean = false
        private set
    var breakEnabled: Boolean = false
        private set

    private val comms get() = plugin.commsManager
    private val wandKey = NamespacedKey(plugin, "event_wand")

    // ── Wand selections ───────────────────────────────────

    private val wandPos1 = HashMap<UUID, Location>()
    private val wandPos2 = HashMap<UUID, Location>()

    // ── Player state ──────────────────────────────────────

    private lateinit var savedInventoryFile: File
    private lateinit var savedInventoryConfig: YamlConfiguration
    val playersInEventWorld: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    // ── Game cycle ────────────────────────────────────────

    private var gameIndex = 0
    var currentGame: EventGame? = null
        private set
    private var cycleTask: BukkitTask? = null
    private var cycleTick = 0

    // ── Lifecycle ─────────────────────────────────────────

    private lateinit var configFile: File
    private lateinit var cfg: YamlConfiguration

    fun start() {
        configFile = plugin.configFile("event.yml")
        if (!configFile.exists()) {
            configFile.parentFile?.mkdirs()
            configFile.createNewFile()
        }
        cfg = YamlConfiguration.loadConfiguration(configFile)

        savedInventoryFile = File(plugin.dataFolder, "event_inventories.yml")
        savedInventoryConfig = if (savedInventoryFile.exists())
            YamlConfiguration.loadConfiguration(savedInventoryFile)
        else YamlConfiguration()

        loadConfig()
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // Tick every second: drives game timer and hourly cycle
        cycleTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickCycle() }, 20L, 20L)
        plugin.logger.info("[Event] Started. World: $eventWorldName")
    }

    fun shutdown() {
        cycleTask?.cancel()
        currentGame?.cleanup()
        currentGame = null
    }

    // ── Config persistence ────────────────────────────────

    private fun loadConfig() {
        eventWorldName = cfg.getString("world", "event") ?: "event"
        pvpEnabled = cfg.getBoolean("pvp", false)
        buildEnabled = cfg.getBoolean("build", false)
        breakEnabled = cfg.getBoolean("break-blocks", false)

        val min = cfg.getConfigurationSection("zone.min")
        val max = cfg.getConfigurationSection("zone.max")
        if (min != null && max != null) {
            val world = Bukkit.getWorld(eventWorldName)
            if (world != null) {
                zoneMin = Location(world, min.getDouble("x"), min.getDouble("y"), min.getDouble("z"))
                zoneMax = Location(world, max.getDouble("x"), max.getDouble("y"), max.getDouble("z"))
            }
        }
    }

    private fun saveConfig() {
        cfg.set("world", eventWorldName)
        cfg.set("pvp", pvpEnabled)
        cfg.set("build", buildEnabled)
        cfg.set("break-blocks", breakEnabled)
        val min = zoneMin
        val max = zoneMax
        if (min != null && max != null) {
            cfg.set("zone.min.x", min.x); cfg.set("zone.min.y", min.y); cfg.set("zone.min.z", min.z)
            cfg.set("zone.max.x", max.x); cfg.set("zone.max.y", max.y); cfg.set("zone.max.z", max.z)
        }
        try { cfg.save(configFile) } catch (e: Exception) {
            plugin.logger.warning("[Event] Failed to save event.yml: ${e.message}")
        }
    }

    // ── Inventory save/restore ────────────────────────────

    private fun saveInventory(player: Player) {
        val uuid = player.uniqueId.toString()
        savedInventoryConfig.set("$uuid.gamemode", player.gameMode.name)
        val inv = player.inventory
        for (i in 0 until inv.size) {
            val item = inv.getItem(i)
            if (item != null && item.type != Material.AIR) savedInventoryConfig.set("$uuid.inv.$i", item)
            else savedInventoryConfig.set("$uuid.inv.$i", null)
        }
        savedInventoryConfig.set("$uuid.armor.helmet", inv.helmet)
        savedInventoryConfig.set("$uuid.armor.chestplate", inv.chestplate)
        savedInventoryConfig.set("$uuid.armor.leggings", inv.leggings)
        savedInventoryConfig.set("$uuid.armor.boots", inv.boots)
        val offhand = inv.itemInOffHand
        if (offhand.type != Material.AIR) savedInventoryConfig.set("$uuid.armor.offhand", offhand)
        try { savedInventoryConfig.save(savedInventoryFile) } catch (_: Exception) {}
    }

    private fun restoreInventory(player: Player) {
        val uuid = player.uniqueId.toString()
        val section = savedInventoryConfig.getConfigurationSection(uuid) ?: return

        player.inventory.clear()
        player.inventory.setArmorContents(arrayOfNulls(4))
        player.inventory.setItemInOffHand(ItemStack(Material.AIR))

        val invSection = section.getConfigurationSection("inv")
        if (invSection != null) {
            for (key in invSection.getKeys(false)) {
                val slot = key.toIntOrNull() ?: continue
                val item = invSection.getItemStack(key) ?: continue
                player.inventory.setItem(slot, item)
            }
        }
        player.inventory.helmet = section.getItemStack("armor.helmet")
        player.inventory.chestplate = section.getItemStack("armor.chestplate")
        player.inventory.leggings = section.getItemStack("armor.leggings")
        player.inventory.boots = section.getItemStack("armor.boots")
        val offhand = section.getItemStack("armor.offhand")
        if (offhand != null) player.inventory.setItemInOffHand(offhand)

        val gm = section.getString("gamemode") ?: "SURVIVAL"
        runCatching { player.gameMode = GameMode.valueOf(gm) }

        savedInventoryConfig.set(uuid, null)
        try { savedInventoryConfig.save(savedInventoryFile) } catch (_: Exception) {}
    }

    // ── Event world entry/exit ────────────────────────────

    private fun onEnterEventWorld(player: Player) {
        if (!playersInEventWorld.add(player.uniqueId)) return
        val uuid = player.uniqueId.toString()
        // Don't overwrite a saved inventory that wasn't restored yet (handles restart mid-event)
        if (!savedInventoryConfig.contains(uuid)) {
            saveInventory(player)
        }
        player.inventory.clear()
        player.inventory.setArmorContents(arrayOfNulls(4))
        player.inventory.setItemInOffHand(ItemStack(Material.AIR))
        player.gameMode = GameMode.ADVENTURE
        comms.send(player, Component.text("Welcome to the event world! Use /spawn to leave.", NamedTextColor.YELLOW))
    }

    private fun onLeaveEventWorld(player: Player) {
        if (!playersInEventWorld.remove(player.uniqueId)) return
        currentGame?.removePlayer(player)
        if (player.gameMode == GameMode.SPECTATOR) player.gameMode = GameMode.SURVIVAL
        restoreInventory(player)
        comms.send(player, Component.text("Your inventory has been restored.", NamedTextColor.GREEN))
    }

    // ── Listeners ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        val from = event.from.name
        val to = event.player.world.name
        if (from == eventWorldName && to != eventWorldName) onLeaveEventWorld(event.player)
        else if (to == eventWorldName && from != eventWorldName) onEnterEventWorld(event.player)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline && player.world.name == eventWorldName) onEnterEventWorld(player)
        }, 2L)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        if (player.world.name == eventWorldName) onLeaveEventWorld(player)
        wandPos1.remove(player.uniqueId)
        wandPos2.remove(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        if (player.world.name != eventWorldName) return
        if (player.hasPermission("joshymc.event")) return
        val cmd = event.message.removePrefix("/").split(" ").first().lowercase()
        val allowed = setOf("spawn", "jmc", "joshymc")
        if (cmd !in allowed) {
            event.isCancelled = true
            comms.send(player, Component.text("Only /spawn is allowed in the event world.", NamedTextColor.RED))
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val player = event.player
        if (player.world.name != eventWorldName) return
        if (player.hasPermission("joshymc.event")) return
        if (!breakEnabled) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (player.world.name != eventWorldName) return
        if (player.hasPermission("joshymc.event")) return
        if (!buildEnabled) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val attacker: Player = run {
            val d = event.damager
            when {
                d is Player -> d
                d is Projectile && d.shooter is Player -> d.shooter as Player
                else -> null
            }
        } ?: return
        val victim = event.entity as? Player ?: return
        if (attacker.world.name != eventWorldName) return

        val game = currentGame
        if (game != null && game.isPvpAllowed()) {
            game.onPlayerDamage(attacker, victim, event)
        } else if (!pvpEnabled) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onWandInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val item = event.item ?: return
        val meta = item.itemMeta ?: return
        if (!meta.persistentDataContainer.has(wandKey, PersistentDataType.INTEGER)) return
        event.isCancelled = true
        val player = event.player
        val block = event.clickedBlock ?: return
        when (event.action) {
            org.bukkit.event.block.Action.LEFT_CLICK_BLOCK -> {
                wandPos1[player.uniqueId] = block.location
                comms.send(player, Component.text("Position 1 set to (${block.x}, ${block.y}, ${block.z}).", NamedTextColor.GREEN))
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.2f)
            }
            org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK -> {
                wandPos2[player.uniqueId] = block.location
                comms.send(player, Component.text("Position 2 set to (${block.x}, ${block.y}, ${block.z}).", NamedTextColor.GREEN))
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 0.8f)
            }
            else -> {}
        }
    }

    // ── Hourly cycle ──────────────────────────────────────

    private fun tickCycle() {
        cycleTick++
        currentGame?.tick()
        if (cycleTick >= 3600) {
            cycleTick = 0
            tryStartNextGame()
        }
    }

    private fun tryStartNextGame() {
        if (currentGame != null) return
        val world = Bukkit.getWorld(eventWorldName) ?: return
        val players = world.players.filter { playersInEventWorld.contains(it.uniqueId) }
        if (players.isEmpty()) return
        val names = listOf("tntrun", "redlightgreenlight", "sharksandminnows", "hideandseek")
        startGame(names[gameIndex % names.size], players)
        gameIndex++
    }

    fun startGame(name: String, players: List<Player>) {
        currentGame?.cleanup()
        currentGame = null
        val min = zoneMin ?: run { plugin.logger.warning("[Event] Zone not set."); return }
        val max = zoneMax ?: run { plugin.logger.warning("[Event] Zone not set."); return }
        currentGame = when (name.lowercase()) {
            "tntrun" -> TNTRunGame(players, min, max)
            "redlightgreenlight" -> RedLightGreenLightGame(players, min, max)
            "sharksandminnows" -> SharksAndMinnowsGame(players, min, max)
            "hideandseek" -> HideAndSeekGame(players, min, max)
            else -> null
        }
        if (currentGame == null) {
            comms.broadcast(Component.text("[Event] Unknown game: $name", NamedTextColor.RED))
            return
        }
        currentGame!!.start()
    }

    fun stopGame() {
        currentGame?.cleanup()
        currentGame = null
    }

    fun awardWinner(winner: Player) {
        plugin.economyManager.deposit(winner.uniqueId, 100_000.0)
        Bukkit.broadcast(
            Component.text("[Event] ", NamedTextColor.GOLD)
                .append(Component.text(winner.name, NamedTextColor.YELLOW))
                .append(Component.text(" won the event and received ", NamedTextColor.GOLD))
                .append(Component.text("$100,000", NamedTextColor.GREEN))
                .append(Component.text("!", NamedTextColor.GOLD))
        )
        winner.showTitle(Title.title(
            Component.text("Winner!", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true),
            Component.text("+$100,000", NamedTextColor.GREEN),
            Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(1000))
        ))
        winner.playSound(winner.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)
    }

    // ── Wand item ─────────────────────────────────────────

    private fun createWand(): ItemStack {
        val item = ItemStack(Material.BLAZE_ROD)
        val meta = item.itemMeta!!
        meta.displayName(
            Component.text("Event Wand", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true)
        )
        meta.lore(listOf(
            Component.empty(),
            Component.text("Left-click block: Set Position 1", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Right-click block: Set Position 2", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Run /event save to confirm zone.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ))
        meta.persistentDataContainer.set(wandKey, PersistentDataType.INTEGER, 1)
        item.itemMeta = meta
        return item
    }

    // ── Shared game helpers ───────────────────────────────

    private fun resetSpectatorsInWorld() {
        val world = Bukkit.getWorld(eventWorldName) ?: return
        for (player in world.players) {
            if (player.gameMode == GameMode.SPECTATOR && playersInEventWorld.contains(player.uniqueId)) {
                player.gameMode = GameMode.ADVENTURE
                teleportToZoneCenter(player)
            }
        }
    }

    private fun teleportToZoneCenter(player: Player) {
        val min = zoneMin ?: return
        val max = zoneMax ?: return
        val world = min.world ?: return
        val cx = (min.x + max.x) / 2
        val cz = (min.z + max.z) / 2
        val cy = world.getHighestBlockYAt(cx.toInt(), cz.toInt()).toDouble() + 1.0
        player.teleport(Location(world, cx, cy, cz))
    }

    // ── Game base class ───────────────────────────────────

    abstract inner class EventGame(
        protected val participants: MutableList<Player>,
        protected val min: Location,
        protected val max: Location
    ) {
        abstract fun start()
        abstract fun tick()
        abstract fun cleanup()
        open fun isPvpAllowed(): Boolean = false
        open fun onPlayerDamage(attacker: Player, victim: Player, event: EntityDamageByEntityEvent) {}

        fun removePlayer(player: Player) { participants.remove(player) }

        protected fun broadcastAll(msg: Component) = Bukkit.broadcast(msg)

        protected fun broadcastParticipants(msg: Component) = participants.forEach { comms.send(it, msg) }

        protected fun zoneCenter(): Location {
            val world = min.world!!
            val cx = (min.x + max.x) / 2
            val cz = (min.z + max.z) / 2
            val cy = world.getHighestBlockYAt(cx.toInt(), cz.toInt()).toDouble() + 1.0
            return Location(world, cx, cy, cz)
        }

        protected fun teleportCenter(player: Player) = player.teleport(zoneCenter())

        protected fun eliminate(player: Player, reason: String) {
            participants.remove(player)
            comms.send(player, Component.text("Eliminated: $reason", NamedTextColor.RED))
            broadcastParticipants(
                Component.text("${player.name} was eliminated! ", NamedTextColor.YELLOW)
                    .append(Component.text("(${participants.size} remain)", NamedTextColor.GRAY))
            )
            val specLoc = zoneCenter().add(0.0, 15.0, 0.0)
            player.teleport(specLoc)
            player.gameMode = GameMode.SPECTATOR
        }
    }

    // ── TNT Run ───────────────────────────────────────────

    inner class TNTRunGame(
        players: List<Player>,
        min: Location,
        max: Location
    ) : EventGame(players.toMutableList(), min, max) {

        private val scheduledBlocks = ConcurrentHashMap.newKeySet<Long>()
        private val blockTask = ArrayList<BukkitTask>()
        private var secondsElapsed = 0

        override fun start() {
            broadcastAll(
                Component.text("[Event] TNT Run is starting! Don't fall!", NamedTextColor.RED)
                    .decoration(TextDecoration.BOLD, true)
            )
            participants.forEach { it.gameMode = GameMode.ADVENTURE; teleportCenter(it) }
            blockTask.add(Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickBlocks() }, 5L, 3L))
        }

        private fun blockKey(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3FFFFFF) shl 38) or ((y.toLong() and 0xFFF) shl 26) or (z.toLong() and 0x3FFFFFF)

        private fun tickBlocks() {
            val world = min.world ?: return
            val floorY = min.blockY
            val toEliminate = mutableListOf<Player>()

            for (player in participants.toList()) {
                if (player.world != world) continue
                if (player.location.y < floorY - 4) { toEliminate.add(player); continue }

                val bx = player.location.blockX
                val by = player.location.blockY - 1
                val bz = player.location.blockZ
                val block = world.getBlockAt(bx, by, bz)
                if (block.type.isAir) continue

                val key = blockKey(bx, by, bz)
                if (scheduledBlocks.add(key)) {
                    val loc = block.location.clone()
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        val b = world.getBlockAt(loc)
                        if (!b.type.isAir) b.type = Material.AIR
                    }, 12L) // 0.6 seconds delay
                }
            }

            toEliminate.forEach { eliminate(it, "fell") }
            checkWin()
        }

        override fun tick() {
            secondsElapsed++
            if (secondsElapsed >= 600) { // 10-minute limit
                broadcastAll(Component.text("[Event] TNT Run: time limit reached!", NamedTextColor.YELLOW))
                if (participants.isNotEmpty()) awardWinner(participants.first())
                cleanup(); currentGame = null
            }
        }

        private fun checkWin() {
            when {
                participants.size == 1 -> { awardWinner(participants.first()); cleanup(); currentGame = null }
                participants.isEmpty() -> {
                    broadcastAll(Component.text("[Event] TNT Run: everyone fell — no winner!", NamedTextColor.YELLOW))
                    cleanup(); currentGame = null
                }
            }
        }

        override fun cleanup() {
            blockTask.forEach { it.cancel() }; blockTask.clear()
            scheduledBlocks.clear()
            resetSpectatorsInWorld()
        }
    }

    // ── Red Light Green Light ─────────────────────────────

    inner class RedLightGreenLightGame(
        players: List<Player>,
        min: Location,
        max: Location
    ) : EventGame(players.toMutableList(), min, max) {

        private var greenLight = true
        private var phaseTimer = 5
        private val lastPos = HashMap<UUID, Location>()
        private var secondsElapsed = 0
        private val finishZ = max.z

        override fun start() {
            broadcastAll(
                Component.text("[Event] Red Light, Green Light!", NamedTextColor.GREEN)
                    .decoration(TextDecoration.BOLD, true)
            )
            val world = min.world ?: return
            val startX = (min.x + max.x) / 2
            val startZ = min.z
            val startY = world.getHighestBlockYAt(startX.toInt(), startZ.toInt()).toDouble() + 1.0
            val startLoc = Location(world, startX, startY, startZ)
            participants.forEach {
                it.gameMode = GameMode.ADVENTURE
                it.teleport(startLoc)
                lastPos[it.uniqueId] = startLoc.clone()
            }
            greenLight = true
            phaseTimer = 5
            broadcastPhase()
        }

        private fun broadcastPhase() {
            if (greenLight) {
                broadcastParticipants(Component.text("GREEN LIGHT — GO!", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true))
            } else {
                broadcastParticipants(Component.text("RED LIGHT — STOP!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
            }
        }

        override fun tick() {
            secondsElapsed++
            phaseTimer--

            val toEliminate = mutableListOf<Player>()
            for (player in participants.toList()) {
                val loc = player.location
                // Finish: reached the far Z wall
                if (loc.z >= finishZ - 1.5) {
                    participants.remove(player)
                    awardWinner(player)
                    comms.send(player, Component.text("You reached the finish line!", NamedTextColor.GREEN))
                    cleanup(); currentGame = null
                    return
                }
                if (!greenLight) {
                    val last = lastPos[player.uniqueId]
                    if (last != null && loc.distanceSquared(last) > 0.09) {
                        toEliminate.add(player)
                    }
                }
                lastPos[player.uniqueId] = loc.clone()
            }

            toEliminate.forEach { eliminate(it, "moved on red light") }

            if (phaseTimer <= 0) {
                greenLight = !greenLight
                phaseTimer = if (greenLight) (5..8).random() else (2..4).random()
                broadcastPhase()
            }

            if (participants.isEmpty()) {
                broadcastAll(Component.text("[Event] Red Light Green Light: no winner!", NamedTextColor.YELLOW))
                cleanup(); currentGame = null; return
            }

            if (secondsElapsed >= 600) {
                broadcastAll(Component.text("[Event] Red Light Green Light: time limit — no winner!", NamedTextColor.YELLOW))
                cleanup(); currentGame = null
            }
        }

        override fun cleanup() {
            lastPos.clear()
            resetSpectatorsInWorld()
        }
    }

    // ── Sharks and Minnows ────────────────────────────────

    inner class SharksAndMinnowsGame(
        players: List<Player>,
        min: Location,
        max: Location
    ) : EventGame(players.toMutableList(), min, max) {

        private val sharks = mutableSetOf<UUID>()
        private val minnows = mutableSetOf<UUID>()
        private var secondsElapsed = 0
        private var runTimer = 0       // seconds left for current run
        private var waiting = true
        private var destinationZ = max.z  // alternates each round
        private var roundNum = 0

        override fun start() {
            if (participants.size < 2) {
                broadcastAll(Component.text("[Event] Not enough players for Sharks and Minnows!", NamedTextColor.RED))
                currentGame = null; return
            }
            broadcastAll(
                Component.text("[Event] Sharks and Minnows!", NamedTextColor.AQUA)
                    .decoration(TextDecoration.BOLD, true)
            )
            val firstShark = participants.random()
            sharks.add(firstShark.uniqueId)
            minnows.addAll(participants.filter { it.uniqueId != firstShark.uniqueId }.map { it.uniqueId })
            comms.send(firstShark, Component.text("You are the SHARK!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
            minnows.forEach { uuid -> Bukkit.getPlayer(uuid)?.let {
                comms.send(it, Component.text("You are a minnow! Cross to the other side!", NamedTextColor.AQUA))
            }}
            participants.forEach { it.gameMode = GameMode.ADVENTURE }
            startRound()
        }

        private fun startRound() {
            roundNum++
            waiting = true
            runTimer = 3 // 3-second warning
            val world = min.world ?: return
            val startZ = if (destinationZ == max.z) min.z else max.z
            val cx = (min.x + max.x) / 2
            val minnowStart = Location(world, cx, world.getHighestBlockYAt(cx.toInt(), startZ.toInt()).toDouble() + 1.0, startZ)
            val sharkStart = Location(world, cx, world.getHighestBlockYAt(cx.toInt(), ((min.z + max.z) / 2).toInt()).toDouble() + 1.0, (min.z + max.z) / 2)
            minnows.forEach { uuid -> Bukkit.getPlayer(uuid)?.teleport(minnowStart) }
            sharks.forEach { uuid -> Bukkit.getPlayer(uuid)?.teleport(sharkStart) }
            broadcastParticipants(
                Component.text("Round $roundNum — Minnows cross in 3s! Destination: ${if (destinationZ == max.z) "north" else "south"}", NamedTextColor.YELLOW)
            )
        }

        override fun isPvpAllowed() = true

        override fun onPlayerDamage(attacker: Player, victim: Player, event: EntityDamageByEntityEvent) {
            if (waiting) { event.isCancelled = true; return }
            if (attacker.uniqueId !in sharks || victim.uniqueId !in minnows) { event.isCancelled = true; return }
            event.isCancelled = true
            tagMinnow(victim)
        }

        private fun tagMinnow(victim: Player) {
            minnows.remove(victim.uniqueId)
            sharks.add(victim.uniqueId)
            comms.send(victim, Component.text("Tagged! You're now a SHARK!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
            broadcastParticipants(Component.text("${victim.name} was tagged! Sharks: ${sharks.size} | Minnows: ${minnows.size}", NamedTextColor.RED))
            if (minnows.size == 1) {
                val lastMinnow = Bukkit.getPlayer(minnows.first())
                if (lastMinnow != null) { awardWinner(lastMinnow); cleanup(); currentGame = null }
            } else if (minnows.isEmpty()) {
                broadcastAll(Component.text("[Event] Sharks win — no minnows survived!", NamedTextColor.RED))
                cleanup(); currentGame = null
            }
        }

        override fun tick() {
            secondsElapsed++
            if (secondsElapsed >= 900) {
                val lastMinnow = minnows.firstOrNull()?.let { Bukkit.getPlayer(it) }
                if (lastMinnow != null) awardWinner(lastMinnow)
                else broadcastAll(Component.text("[Event] Sharks and Minnows ended.", NamedTextColor.YELLOW))
                cleanup(); currentGame = null; return
            }

            if (waiting) {
                runTimer--
                if (runTimer <= 0) {
                    waiting = false
                    runTimer = 15 // 15 seconds to cross
                    broadcastParticipants(Component.text("GO! Minnows, cross now!", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true))
                }
            } else {
                runTimer--
                if (runTimer <= 0) {
                    // End of crossing period — eliminate uncrossed minnows
                    val world = min.world ?: return
                    val midZ = (min.z + max.z) / 2
                    val safe = if (destinationZ == max.z) { { z: Double -> z >= midZ } } else { { z: Double -> z <= midZ } }
                    val toTag = mutableListOf<Player>()
                    for (uuid in minnows.toList()) {
                        val p = Bukkit.getPlayer(uuid) ?: continue
                        if (!safe(p.location.z)) toTag.add(p)
                    }
                    toTag.forEach { tagMinnow(it) }
                    if (currentGame == null) return
                    // Flip destination and start next round
                    destinationZ = if (destinationZ == max.z) min.z else max.z
                    if (minnows.size > 1) startRound()
                    else if (minnows.size == 1) {
                        val lastMinnow = Bukkit.getPlayer(minnows.first())
                        if (lastMinnow != null) { awardWinner(lastMinnow); cleanup(); currentGame = null }
                    }
                }
            }
        }

        override fun cleanup() {
            sharks.clear(); minnows.clear()
            resetSpectatorsInWorld()
        }
    }

    // ── Hide and Seek ─────────────────────────────────────

    inner class HideAndSeekGame(
        players: List<Player>,
        min: Location,
        max: Location
    ) : EventGame(players.toMutableList(), min, max) {

        private val seekers = mutableSetOf<UUID>()
        private val hiders = mutableSetOf<UUID>()
        private var phase = 0 // 0 = hiding, 1 = seeking
        private var phaseTimer = 30
        private var secondsElapsed = 0

        override fun start() {
            if (participants.size < 2) {
                broadcastAll(Component.text("[Event] Not enough players for Hide and Seek!", NamedTextColor.RED))
                currentGame = null; return
            }
            broadcastAll(
                Component.text("[Event] Hide and Seek!", NamedTextColor.DARK_PURPLE)
                    .decoration(TextDecoration.BOLD, true)
            )
            val seekerCount = if (participants.size >= 7) 2 else 1
            val shuffled = participants.shuffled()
            shuffled.take(seekerCount).forEach { player ->
                seekers.add(player.uniqueId)
                player.gameMode = GameMode.ADVENTURE
                teleportCenter(player)
                player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 600, 1, false, false))
                comms.send(player, Component.text("You are a SEEKER! Wait 30 seconds while hiders hide.", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
            }
            shuffled.drop(seekerCount).forEach { player ->
                hiders.add(player.uniqueId)
                player.gameMode = GameMode.ADVENTURE
                teleportCenter(player)
                comms.send(player, Component.text("You are a HIDER! You have 30 seconds to hide!", NamedTextColor.DARK_PURPLE).decoration(TextDecoration.BOLD, true))
            }
            broadcastParticipants(Component.text("30 seconds to hide!", NamedTextColor.YELLOW))
            phase = 0
            phaseTimer = 30
        }

        override fun isPvpAllowed() = phase == 1

        override fun onPlayerDamage(attacker: Player, victim: Player, event: EntityDamageByEntityEvent) {
            event.isCancelled = true // No real damage
            if (phase != 1) return
            if (attacker.uniqueId !in seekers || victim.uniqueId !in hiders) return
            hiders.remove(victim.uniqueId)
            eliminate(victim, "found by a seeker")
            broadcastParticipants(
                Component.text("${victim.name} was found! ", NamedTextColor.YELLOW)
                    .append(Component.text("${hiders.size} hider(s) remain.", NamedTextColor.GRAY))
            )
            if (hiders.isEmpty()) {
                broadcastAll(Component.text("[Event] Seekers found everyone! Seekers win!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
                seekers.forEach { uuid -> Bukkit.getPlayer(uuid)?.let { awardWinner(it) } }
                cleanup(); currentGame = null
            }
        }

        override fun tick() {
            secondsElapsed++
            phaseTimer--

            when (phase) {
                0 -> {
                    if (phaseTimer % 10 == 0 && phaseTimer > 0) {
                        broadcastParticipants(Component.text("${phaseTimer}s until seekers are released!", NamedTextColor.YELLOW))
                    }
                    if (phaseTimer <= 0) {
                        phase = 1
                        phaseTimer = 300 // 5 minutes
                        seekers.forEach { uuid -> Bukkit.getPlayer(uuid)?.removePotionEffect(PotionEffectType.BLINDNESS) }
                        broadcastParticipants(
                            Component.text("SEEKERS RELEASED! Hiders, run!", NamedTextColor.RED)
                                .decoration(TextDecoration.BOLD, true)
                        )
                    }
                }
                1 -> {
                    if (phaseTimer % 60 == 0 && phaseTimer > 0) {
                        broadcastParticipants(Component.text("${phaseTimer / 60}m remaining — ${hiders.size} hider(s) left.", NamedTextColor.YELLOW))
                    }
                    if (phaseTimer <= 0) {
                        if (hiders.isNotEmpty()) {
                            broadcastAll(
                                Component.text("[Event] Time's up! ${hiders.size} hider(s) survived — Hiders win!", NamedTextColor.DARK_PURPLE)
                                    .decoration(TextDecoration.BOLD, true)
                            )
                            hiders.forEach { uuid -> Bukkit.getPlayer(uuid)?.let { awardWinner(it) } }
                        } else {
                            broadcastAll(Component.text("[Event] Time's up! No hiders survived.", NamedTextColor.YELLOW))
                        }
                        cleanup(); currentGame = null
                    }
                }
            }
        }

        override fun cleanup() {
            seekers.forEach { uuid -> Bukkit.getPlayer(uuid)?.removePotionEffect(PotionEffectType.BLINDNESS) }
            seekers.clear(); hiders.clear()
            resetSpectatorsInWorld()
        }
    }

    // ── EventCommand ──────────────────────────────────────

    inner class EventCommand : CommandExecutor, TabCompleter {

        override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
            if (sender !is Player) { sender.sendMessage("Players only."); return true }
            if (!sender.hasPermission("joshymc.event")) {
                comms.send(sender, Component.text("No permission.", NamedTextColor.RED)); return true
            }
            if (args.isEmpty()) { sendUsage(sender); return true }

            when (args[0].lowercase()) {
                "wand" -> {
                    sender.inventory.addItem(createWand())
                    comms.send(sender, Component.text("Event wand added. Left-click = Pos1, Right-click = Pos2.", NamedTextColor.GREEN))
                }
                "save" -> {
                    val p1 = wandPos1[sender.uniqueId]
                    val p2 = wandPos2[sender.uniqueId]
                    if (p1 == null || p2 == null) {
                        comms.send(sender, Component.text("Set both positions with the event wand first.", NamedTextColor.RED)); return true
                    }
                    val world = p1.world ?: run { comms.send(sender, Component.text("Invalid world.", NamedTextColor.RED)); return true }
                    zoneMin = Location(world, minOf(p1.x, p2.x), minOf(p1.y, p2.y), minOf(p1.z, p2.z))
                    zoneMax = Location(world, maxOf(p1.x, p2.x), maxOf(p1.y, p2.y), maxOf(p1.z, p2.z))
                    saveConfig()
                    comms.send(sender, Component.text("Zone saved: (${zoneMin!!.blockX},${zoneMin!!.blockY},${zoneMin!!.blockZ}) to (${zoneMax!!.blockX},${zoneMax!!.blockY},${zoneMax!!.blockZ})", NamedTextColor.GREEN))
                }
                "pvp" -> {
                    if (args.size < 2) { comms.send(sender, Component.text("Usage: /event pvp <on|off>", NamedTextColor.RED)); return true }
                    pvpEnabled = args[1].lowercase() == "on"
                    saveConfig()
                    comms.send(sender, Component.text("Event PvP: ${if (pvpEnabled) "ON" else "OFF"}", if (pvpEnabled) NamedTextColor.GREEN else NamedTextColor.RED))
                }
                "build" -> {
                    if (args.size < 2) { comms.send(sender, Component.text("Usage: /event build <on|off>", NamedTextColor.RED)); return true }
                    buildEnabled = args[1].lowercase() == "on"
                    saveConfig()
                    comms.send(sender, Component.text("Event build: ${if (buildEnabled) "ON" else "OFF"}", if (buildEnabled) NamedTextColor.GREEN else NamedTextColor.RED))
                }
                "break" -> {
                    if (args.size < 2) { comms.send(sender, Component.text("Usage: /event break <on|off>", NamedTextColor.RED)); return true }
                    breakEnabled = args[1].lowercase() == "on"
                    saveConfig()
                    comms.send(sender, Component.text("Event break: ${if (breakEnabled) "ON" else "OFF"}", if (breakEnabled) NamedTextColor.GREEN else NamedTextColor.RED))
                }
                "setworld" -> {
                    if (args.size < 2) { comms.send(sender, Component.text("Usage: /event setworld <world>", NamedTextColor.RED)); return true }
                    eventWorldName = args[1]
                    saveConfig()
                    comms.send(sender, Component.text("Event world: $eventWorldName", NamedTextColor.GREEN))
                }
                "start" -> {
                    val world = Bukkit.getWorld(eventWorldName)
                        ?: run { comms.send(sender, Component.text("World '$eventWorldName' not loaded.", NamedTextColor.RED)); return true }
                    val players = if (playersInEventWorld.isEmpty())
                        listOf(sender)
                    else
                        world.players.filter { playersInEventWorld.contains(it.uniqueId) }
                    val gameName = if (args.size >= 2) args[1] else {
                        val names = listOf("tntrun", "redlightgreenlight", "sharksandminnows", "hideandseek")
                        names[gameIndex % names.size].also { gameIndex++ }
                    }
                    startGame(gameName, players)
                    comms.send(sender, Component.text("Starting: $gameName with ${players.size} player(s).", NamedTextColor.GREEN))
                }
                "stop" -> {
                    if (currentGame == null) { comms.send(sender, Component.text("No game running.", NamedTextColor.RED)); return true }
                    stopGame()
                    comms.send(sender, Component.text("Game stopped.", NamedTextColor.GREEN))
                }
                "status" -> {
                    comms.send(sender, Component.text("=== Event Status ===", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
                    comms.send(sender, Component.text("World: $eventWorldName", NamedTextColor.GRAY))
                    comms.send(sender, Component.text("Zone: ${zoneMin?.let { "(${it.blockX},${it.blockY},${it.blockZ})" } ?: "not set"} to ${zoneMax?.let { "(${it.blockX},${it.blockY},${it.blockZ})" } ?: "not set"}", NamedTextColor.GRAY))
                    comms.send(sender, Component.text("PvP: ${if (pvpEnabled) "ON" else "OFF"} | Build: ${if (buildEnabled) "ON" else "OFF"} | Break: ${if (breakEnabled) "ON" else "OFF"}", NamedTextColor.GRAY))
                    comms.send(sender, Component.text("Game: ${currentGame?.javaClass?.simpleName ?: "none"} | Players in world: ${playersInEventWorld.size}", NamedTextColor.GRAY))
                }
                else -> sendUsage(sender)
            }
            return true
        }

        private fun sendUsage(player: Player) {
            listOf(
                "", "&6&lEvent Commands:",
                "&e/event wand &7- Get event zone wand",
                "&e/event save &7- Save selected zone",
                "&e/event pvp <on|off> &7- Toggle PvP",
                "&e/event build <on|off> &7- Toggle building",
                "&e/event break <on|off> &7- Toggle breaking",
                "&e/event setworld <world> &7- Set event world",
                "&e/event start [game] &7- Start a game (tntrun/redlightgreenlight/sharksandminnows/hideandseek)",
                "&e/event stop &7- Stop current game",
                "&e/event status &7- Show status", ""
            ).forEach { player.sendMessage(comms.parseLegacy(it)) }
        }

        override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
            if (!sender.hasPermission("joshymc.event")) return emptyList()
            return when (args.size) {
                1 -> listOf("wand", "save", "pvp", "build", "break", "setworld", "start", "stop", "status")
                    .filter { it.startsWith(args[0].lowercase()) }
                2 -> when (args[0].lowercase()) {
                    "pvp", "build", "break" -> listOf("on", "off").filter { it.startsWith(args[1].lowercase()) }
                    "start" -> listOf("tntrun", "redlightgreenlight", "sharksandminnows", "hideandseek")
                        .filter { it.startsWith(args[1].lowercase()) }
                    "setworld" -> Bukkit.getWorlds().map { it.name }.filter { it.startsWith(args[1].lowercase()) }
                    else -> emptyList()
                }
                else -> emptyList()
            }
        }
    }
}
