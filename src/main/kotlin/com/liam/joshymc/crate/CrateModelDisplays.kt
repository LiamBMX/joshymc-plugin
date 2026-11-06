package com.liam.joshymc.crate

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CrateManager
import com.liam.joshymc.util.BedrockUtil
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Draws each crate's custom chest model over its registered crate block (issue #1389).
 *
 * - The crate block is never changed: clicks still land on it, so opening, keys, previews,
 *   animations, rewards and permissions all run through [CrateManager] exactly as before.
 *   The display has no hitbox, drops nothing and can't be damaged or picked up.
 * - Displays are **non-persistent**: they are never saved with the chunk, so a restart,
 *   crash or reload can't leave copies behind. They are spawned when a crate's chunk loads
 *   (and at startup for loaded chunks), removed when it unloads, and every spawn first
 *   removes any display already tagged with the same location, so there is one per crate.
 * - They are hidden by default and shown only to Java players whose JoshyMC resource pack
 *   has loaded ([CrateModels.canSee]); everyone else sees the ordinary crate block.
 * - A model only shows where it is configured, enabled, present in the bundled pack, the
 *   crate exists, the block isn't air and the world's gamemode is listed (Survival only by
 *   default). Anything else falls back to the vanilla crate block.
 *
 * No repeating task: everything is driven by chunk, pack-status and crate-location events.
 */
class CrateModelDisplays(private val plugin: Joshymc, private val crates: CrateManager) : Listener {

    companion object {
        const val TAG = "joshymc_crate_model"
        private const val CONFIG_PATH = "crates.custom-models"
    }

    private val locationKeyTag = NamespacedKey(plugin, "crate_model")

    /** location key -> the display drawn there. */
    private val displays = mutableMapOf<String, UUID>()

    /**
     * Players whose JoshyMC pack has loaded. Kept across [stop]/[start] (a soft /joshymc reload
     * keeps this instance, and players don't resend their pack status).
     */
    private val packLoaded = mutableSetOf<UUID>()

    var settings: CrateModels.Settings = CrateModels.Settings.DISABLED
        private set

    /** Item definitions the bundled resource pack holds, read once per start. */
    private var bundledItemDefinitions: Set<String> = emptySet()

    fun start() {
        settings = CrateModels.parse(sectionSource(plugin.config.getConfigurationSection(CONFIG_PATH)))
        for (problem in settings.problems) plugin.logger.warning("[Crates] $CONFIG_PATH.$problem")
        bundledItemDefinitions = readBundledItemDefinitions()
        for (model in settings.models.values) {
            if (!modelAvailable(model)) {
                plugin.logger.warning("[Crates] Custom model '${model.model}' for crate '${model.crateId}' is not in the bundled resource pack; that crate keeps its vanilla look.")
            }
        }
        refreshAll()
        if (settings.enabled) {
            plugin.logger.info("[Crates] Custom crate models: ${displays.size} shown, ${settings.models.size} configured (gamemodes: ${settings.gamemodes.joinToString()}).")
        }
    }

    fun stop() {
        removeAll()
        // Also sweep loaded chunks for any display this instance didn't spawn (e.g. a previous
        // plugin instance after a hard reload), so they can never pile up.
        for (world in Bukkit.getWorlds()) removeStrays(world.getEntitiesByClass(ItemDisplay::class.java))
    }

    // ── Public hooks for the crate manager ─────────────────────────────

    /** Redraws the display at one crate location (after /crate setlocation or removelocation). */
    fun refresh(world: World, x: Int, y: Int, z: Int) {
        val key = CrateModels.locationKey(world.name, x, y, z)
        remove(key)
        removeTaggedNear(world, x, z, key)
        val loc = crates.getAllLocations().firstOrNull { it.world == world.name && it.x == x && it.y == y && it.z == z }
            ?: return
        spawn(loc)
    }

    /** Removes every display and redraws every crate in a loaded chunk. */
    fun refreshAll() {
        removeAll()
        for (world in Bukkit.getWorlds()) removeStrays(world.getEntitiesByClass(ItemDisplay::class.java))
        for (loc in crates.getAllLocations()) spawn(loc)
    }

    /** One line per configured crate model for `/crate models`. */
    fun describe(): List<String> {
        val out = mutableListOf<String>()
        out += "Custom crate models: ${if (settings.enabled) "on" else "off"} (gamemodes: ${settings.gamemodes.joinToString().ifEmpty { "none" }})"
        for (model in settings.models.values.sortedBy { it.crateId }) {
            val state = when {
                !model.enabled -> "disabled"
                crates.getCrate(model.crateId) == null -> "no crate with this id"
                !modelAvailable(model) -> "model missing from the pack"
                else -> "ok"
            }
            val count = crates.getAllLocations().count { crates.resolveCrateId(it.crateType) == model.crateId }
            val shown = displays.keys.count { key -> crates.getAllLocations().any { it.matches(key) && crates.resolveCrateId(it.crateType) == model.crateId } }
            out += "  ${model.crateId}: ${model.model} — $state, $shown/$count location(s) shown"
        }
        for (problem in settings.problems) out += "  problem: $problem"
        return out
    }

    // ── Spawning ───────────────────────────────────────────────────────

    private fun modelAvailable(model: CrateModels.Model): Boolean {
        val path = CrateModels.itemDefinitionPath(model.model) ?: return false
        return path in bundledItemDefinitions
    }

    private fun spawn(loc: CrateManager.CrateLocation) {
        val model = settings.modelFor(crates.resolveCrateId(loc.crateType)) ?: return
        if (crates.getCrate(model.crateId) == null || !modelAvailable(model)) return
        val world = Bukkit.getWorld(loc.world) ?: return
        if (!world.isChunkLoaded(loc.x shr 4, loc.z shr 4)) return
        if (!settings.showsIn(plugin.gamemodeLockManager.gamemodeOf(world).id)) return
        val block = world.getBlockAt(loc.x, loc.y, loc.z)
        // A crate whose block is gone gets no model: a floating chest nobody can click.
        if (block.type.isAir) return

        val key = CrateModels.locationKey(loc.world, loc.x, loc.y, loc.z)
        remove(key)
        removeTaggedNear(world, loc.x, loc.z, key)

        val facing = (block.blockData as? Directional)?.facing?.takeIf { it in BlockFace.entries.take(4) }?.name
        val yaw = CrateModels.displayYaw(model.rotation, facing)
        val at = Location(world, loc.x + 0.5 + model.offsetX, loc.y + 0.5 + model.offsetY, loc.z + 0.5 + model.offsetZ)
        val item = ItemStack(Material.CHEST).apply {
            editMeta { meta ->
                NamespacedKey.fromString(model.model)?.let { meta.setItemModel(it) }
            }
        }
        val scale = model.scale.toFloat()
        val display = world.spawn(at, ItemDisplay::class.java) { d ->
            d.isPersistent = false
            d.isVisibleByDefault = false
            d.setRotation(yaw, 0f)
            d.setItemStack(item)
            d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            d.transformation = Transformation(
                Vector3f(0f, CrateModels.floorLift(model.scale).toFloat(), 0f),
                Quaternionf(),
                Vector3f(scale, scale, scale),
                Quaternionf(),
            )
            // Inside a full block (barrel, ...) the display would sample that block's darkness.
            if (block.type.isOccluding) {
                val above = block.getRelative(BlockFace.UP)
                d.brightness = Display.Brightness(above.lightFromBlocks.toInt(), above.lightFromSky.toInt())
            }
            d.addScoreboardTag(TAG)
            d.persistentDataContainer.set(locationKeyTag, PersistentDataType.STRING, key)
        }
        displays[key] = display.uniqueId
        for (player in world.players) if (eligible(player)) player.showEntity(plugin, display)
    }

    private fun remove(key: String) {
        val id = displays.remove(key) ?: return
        Bukkit.getEntity(id)?.remove()
    }

    private fun removeAll() {
        for (key in displays.keys.toList()) remove(key)
    }

    /** Removes displays tagged with [key] in the crate's chunk (strays from an earlier spawn). */
    private fun removeTaggedNear(world: World, x: Int, z: Int, key: String) {
        if (!world.isChunkLoaded(x shr 4, z shr 4)) return
        for (entity in world.getChunkAt(x shr 4, z shr 4).entities) {
            if (entity !is ItemDisplay || TAG !in entity.scoreboardTags) continue
            if (entity.persistentDataContainer.get(locationKeyTag, PersistentDataType.STRING) == key &&
                displays[key] != entity.uniqueId) {
                entity.remove()
            }
        }
    }

    /** Removes tagged crate displays this instance isn't tracking. */
    private fun removeStrays(entities: Collection<org.bukkit.entity.Entity>) {
        val tracked = displays.values.toSet()
        for (entity in entities) {
            if (entity is ItemDisplay && TAG in entity.scoreboardTags && entity.uniqueId !in tracked) entity.remove()
        }
    }

    // ── Visibility ─────────────────────────────────────────────────────

    private fun eligible(player: Player): Boolean =
        CrateModels.canSee(BedrockUtil.isBedrockPlayer(player), player.uniqueId in packLoaded)

    private fun applyVisibility(player: Player) {
        val show = eligible(player)
        for (id in displays.values) {
            val entity = Bukkit.getEntity(id) ?: continue
            if (show) player.showEntity(plugin, entity) else player.hideEntity(plugin, entity)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPackStatus(event: PlayerResourcePackStatusEvent) {
        val ours = plugin.resourcePackManager.packId
        if (ours == null || event.id != ours) return
        when (event.status) {
            PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED -> packLoaded.add(event.player.uniqueId)
            PlayerResourcePackStatusEvent.Status.DECLINED,
            PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD,
            PlayerResourcePackStatusEvent.Status.INVALID_URL,
            PlayerResourcePackStatusEvent.Status.FAILED_RELOAD,
            PlayerResourcePackStatusEvent.Status.DISCARDED -> packLoaded.remove(event.player.uniqueId)
            else -> return
        }
        applyVisibility(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        packLoaded.remove(event.player.uniqueId)
    }

    // ── Chunks ─────────────────────────────────────────────────────────

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (!settings.enabled) return
        val world = event.world.name
        val cx = event.chunk.x
        val cz = event.chunk.z
        for (loc in crates.getAllLocations()) {
            if (loc.world == world && loc.x shr 4 == cx && loc.z shr 4 == cz) spawn(loc)
        }
    }

    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        // Displays are never saved, but if one ever was (a crash mid-write, an old build),
        // it is removed here instead of stacking on top of the live one.
        removeStrays(event.entities)
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        if (displays.isEmpty()) return
        val world = event.world.name
        val cx = event.chunk.x
        val cz = event.chunk.z
        for (loc in crates.getAllLocations()) {
            if (loc.world == world && loc.x shr 4 == cx && loc.z shr 4 == cz) {
                remove(CrateModels.locationKey(loc.world, loc.x, loc.y, loc.z))
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun CrateManager.CrateLocation.matches(key: String) = CrateModels.locationKey(world, x, y, z) == key

    private fun readBundledItemDefinitions(): Set<String> {
        val stream = plugin.getResource("resourcepack.zip") ?: run {
            plugin.logger.warning("[Crates] resourcepack.zip is missing from the plugin JAR; custom crate models are off.")
            return emptySet()
        }
        val out = mutableSetOf<String>()
        try {
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith(".json") && "/items/" in entry.name) out += entry.name
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Could not read the bundled resource pack (${e.message}); custom crate models are off.")
            return emptySet()
        }
        return out
    }

    private fun sectionSource(section: ConfigurationSection?): CrateModels.Source = object : CrateModels.Source {
        override fun keys(path: String): Set<String> {
            val target = if (path.isEmpty()) section else section?.getConfigurationSection(path)
            return target?.getKeys(false) ?: emptySet()
        }

        override fun value(path: String): Any? {
            val s = section ?: return null
            if (!s.contains(path) || s.isConfigurationSection(path)) return null
            return s.get(path)
        }
    }
}
