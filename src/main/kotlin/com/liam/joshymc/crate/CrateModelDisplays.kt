package com.liam.joshymc.crate

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CrateManager
import com.liam.joshymc.util.BedrockUtil
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Draws each crate's custom chest model over its registered crate block (issue #1389), with an
 * optional click hitbox for models that reach past their block (issue #1451).
 *
 * - The crate block is never changed and stays the crate's identity: every click (on the block
 *   or on a hitbox) runs through [CrateManager] exactly as before. The display has no hitbox,
 *   drops nothing and can't be damaged or picked up.
 * - A model that visibly reaches past its block (by more than [CrateModels.OVERSIZE_TOLERANCE],
 *   measured from its elements in the bundled pack, scale, offset and rotation) gets an
 *   [Interaction] entity around what players see (`hitbox: auto`; `on`/`off` override). Its
 *   clicks go to [CrateManager.handleModelClick]: no second opening, key or reward path.
 * - Displays and hitboxes are **non-persistent**: they are never saved with the chunk, so a
 *   restart, crash or reload can't leave copies behind. They are spawned when every chunk they
 *   stand in is loaded (and at startup for loaded chunks), removed when any of those unloads,
 *   and every spawn first removes any entity tagged with the same location, so there is one
 *   display (and at most one hitbox) per crate. Chunks are never loaded for them.
 * - Both are hidden by default and shown only to Java players whose JoshyMC resource pack has
 *   loaded ([CrateModels.canSee]); everyone else (Bedrock, no pack) sees and clicks the
 *   ordinary crate block, which a hidden hitbox can't get in the way of.
 * - A model only shows where it is configured, enabled, present in the bundled pack, the crate
 *   exists, the block isn't air, the model sits on its block and the world's gamemode is listed
 *   (Survival only by default). Anything else falls back to the vanilla crate block.
 *
 * No repeating task: everything is driven by chunk, pack-status and crate-location events.
 */
class CrateModelDisplays(private val plugin: Joshymc, private val crates: CrateManager) : Listener {

    companion object {
        const val TAG = "joshymc_crate_model"
        private const val CONFIG_PATH = "crates.custom-models"
    }

    /** The location key ("world:x:y:z") on every JoshyMC crate display and hitbox. */
    private val locationKeyTag = NamespacedKey(plugin, "crate_model")

    /** What one crate location has spawned. Only ids and chunk keys: no entity or world references. */
    private data class Spawned(val display: UUID, val hitbox: UUID?, val chunks: Set<Long>)

    /** location key -> the entities drawn there. */
    private val spawned = mutableMapOf<String, Spawned>()

    /** location key -> why that crate is drawn with the vanilla block, for `/crate models`. */
    private val placementProblems = mutableMapOf<String, String>()

    /**
     * Players whose JoshyMC pack has loaded. Kept across [stop]/[start] (a soft /joshymc reload
     * keeps this instance, and players don't resend their pack status).
     */
    private val packLoaded = mutableSetOf<UUID>()

    var settings: CrateModels.Settings = CrateModels.Settings.DISABLED
        private set

    /** Item definitions the bundled resource pack holds, read once per start. */
    private var bundledItemDefinitions: Set<String> = emptySet()

    /** Model key -> the space its elements fill (null = unreadable, treated as a full block). */
    private var modelBounds: Map<String, CrateModels.ModelBounds?> = emptyMap()

    fun start() {
        settings = CrateModels.parse(sectionSource(plugin.config.getConfigurationSection(CONFIG_PATH)))
        for (problem in settings.problems) plugin.logger.warning("[Crates] $CONFIG_PATH.$problem")
        readBundledPack()
        for (model in settings.models.values) {
            if (!modelAvailable(model)) {
                plugin.logger.warning("[Crates] Custom model '${model.model}' for crate '${model.crateId}' is not in the bundled resource pack; that crate keeps its vanilla look.")
            } else if (modelBounds[model.model] == null) {
                plugin.logger.warning("[Crates] Could not read the elements of '${model.model}'; its hitbox assumes it fills one block.")
            }
        }
        refreshAll()
        if (settings.enabled) {
            plugin.logger.info("[Crates] Custom crate models: ${spawned.size} shown (${spawned.values.count { it.hitbox != null }} with a hitbox), ${settings.models.size} configured (gamemodes: ${settings.gamemodes.joinToString()}).")
        }
        for ((key, problem) in placementProblems) plugin.logger.warning("[Crates] Crate model at $key not drawn: $problem")
    }

    fun stop() {
        removeAll()
        // Also sweep loaded chunks for any entity this instance didn't spawn (e.g. a previous
        // plugin instance after a hard reload), so they can never pile up.
        removeStraysEverywhere()
        placementProblems.clear()
    }

    // ── Public hooks for the crate manager ─────────────────────────────

    /** Redraws the model at one crate location (after /crate setlocation or removelocation). */
    fun refresh(world: World, x: Int, y: Int, z: Int) {
        val key = CrateModels.locationKey(world.name, x, y, z)
        remove(key)
        placementProblems.remove(key)
        removeTaggedNear(world, x, z, key)
        val loc = crates.getAllLocations().firstOrNull { it.world == world.name && it.x == x && it.y == y && it.z == z }
            ?: return
        spawn(loc)
    }

    /** Removes every display and hitbox and redraws every crate whose chunks are loaded. */
    fun refreshAll() {
        removeAll()
        placementProblems.clear()
        removeStraysEverywhere()
        for (loc in crates.getAllLocations()) spawn(loc)
    }

    /** `/crate models`: overall counts, then one line per configured model, then problems. */
    fun describe(): List<String> {
        val out = mutableListOf<String>()
        val locations = crates.getAllLocations()
        val expected = locations.filter { expectedModel(it) != null }
        val liveDisplays = spawned.values.count { Bukkit.getEntity(it.display)?.isValid == true }
        val liveHitboxes = spawned.values.count { id -> id.hitbox?.let { Bukkit.getEntity(it)?.isValid } == true }
        var taggedDisplays = 0
        var taggedHitboxes = 0
        for (world in Bukkit.getWorlds()) {
            for (entity in world.getEntitiesByClasses(ItemDisplay::class.java, Interaction::class.java)) {
                if (!isOurs(entity)) continue
                if (entity is Interaction) taggedHitboxes++ else taggedDisplays++
            }
        }
        out += "Custom crate models: ${if (settings.enabled) "on" else "off"} (gamemodes: ${settings.gamemodes.joinToString().ifEmpty { "none" }})"
        val waiting = expected.count { loc ->
            val key = CrateModels.locationKey(loc.world, loc.x, loc.y, loc.z)
            key !in spawned && key !in placementProblems
        }
        out += "  Crate locations: ${locations.size}; expected models: ${expected.size}; " +
            "not drawn yet (chunk/world not loaded, or no block): $waiting"
        out += "  Spawned displays: $liveDisplays; interaction hitboxes: $liveHitboxes"
        val strays = (taggedDisplays - liveDisplays).coerceAtLeast(0) + (taggedHitboxes - liveHitboxes).coerceAtLeast(0)
        out += "  Crate model entities in loaded chunks: $taggedDisplays display(s), $taggedHitboxes hitbox(es)" +
            if (strays > 0) " — $strays untracked, run /crate models refresh" else ""
        for (model in settings.models.values.sortedBy { it.crateId }) {
            val state = when {
                !model.enabled -> "disabled"
                crates.getCrate(model.crateId) == null -> "no crate with this id"
                !modelAvailable(model) -> "model missing from the pack"
                else -> "ok"
            }
            val mine = locations.filter { crates.resolveCrateId(it.crateType) == model.crateId }
            val keys = mine.map { CrateModels.locationKey(it.world, it.x, it.y, it.z) }
            val shown = keys.count { it in spawned }
            val withHitbox = keys.count { spawned[it]?.hitbox != null }
            val bounds = when {
                !modelAvailable(model) -> ""
                modelBounds[model.model] == null -> ", bounds unreadable (treated as one block)"
                else -> modelBounds[model.model]!!.let { b -> ", height ${fmt(b.maxY - b.minY)}/16" }
            }
            out += "  ${model.crateId}: ${model.model} — $state, $shown/${mine.size} shown, " +
                "hitbox ${model.hitbox.name.lowercase()} ($withHitbox placed), scale ${fmt(model.scale)}, rotation ${rotationText(model.rotation)}$bounds"
        }
        val unmapped = locations.map { crates.resolveCrateId(it.crateType) }
            .filter { settings.models[it.lowercase()] == null }.distinct().sorted()
        if (settings.enabled && unmapped.isNotEmpty()) out += "  No model configured (vanilla look): ${unmapped.joinToString()}"
        for ((key, problem) in placementProblems.toSortedMap()) out += "  problem at $key: $problem"
        for (problem in settings.problems) out += "  problem: $problem"
        return out
    }

    // ── Spawning ───────────────────────────────────────────────────────

    private fun modelAvailable(model: CrateModels.Model): Boolean {
        val path = CrateModels.itemDefinitionPath(model.model) ?: return false
        return path in bundledItemDefinitions
    }

    /** The model this location should show, ignoring whether its chunks are loaded. */
    private fun expectedModel(loc: CrateManager.CrateLocation): CrateModels.Model? {
        val model = settings.modelFor(crates.resolveCrateId(loc.crateType)) ?: return null
        if (crates.getCrate(model.crateId) == null || !modelAvailable(model)) return null
        val world = Bukkit.getWorld(loc.world)
        if (world != null && !settings.showsIn(plugin.gamemodeLockManager.gamemodeOf(world).id)) return null
        return model
    }

    private fun spawn(loc: CrateManager.CrateLocation) {
        val model = expectedModel(loc) ?: return
        val world = Bukkit.getWorld(loc.world) ?: return
        // Never loads a chunk: reading the block (its facing) needs its chunk loaded.
        if (!world.isChunkLoaded(loc.x shr 4, loc.z shr 4)) return
        val block = world.getBlockAt(loc.x, loc.y, loc.z)
        val key = CrateModels.locationKey(loc.world, loc.x, loc.y, loc.z)
        // A crate whose block is gone gets no model: a floating chest nobody can click.
        if (block.type.isAir) return

        val facing = (block.blockData as? Directional)?.facing?.takeIf { it in BlockFace.entries.take(4) }?.name
        val bounds = modelBounds[model.model] ?: CrateModels.ModelBounds.FULL_BLOCK
        val placement = CrateModels.placement(model, bounds, facing)
        if (placement.problem != null) {
            remove(key)
            placementProblems[key] = placement.problem
            return
        }
        placementProblems.remove(key)
        val chunks = CrateModels.requiredChunks(loc.x, loc.z, placement)
        // Every chunk an entity would stand in must already be loaded; the next one to load
        // spawns it (see onChunkLoad).
        if (!chunks.all { world.isChunkLoaded((it shr 32).toInt(), it.toInt()) }) return

        remove(key)
        removeTaggedNear(world, loc.x, loc.z, key)

        val at = Location(world, loc.x + placement.displayX, loc.y + placement.displayY, loc.z + placement.displayZ)
        val item = ItemStack(Material.CHEST).apply {
            editMeta { meta ->
                NamespacedKey.fromString(model.model)?.let { meta.setItemModel(it) }
            }
        }
        val scale = model.scale.toFloat()
        val display = world.spawn(at, ItemDisplay::class.java) { d ->
            d.isPersistent = false
            d.isVisibleByDefault = false
            d.setRotation(placement.yaw, 0f)
            d.setItemStack(item)
            d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            d.transformation = Transformation(
                Vector3f(0f, placement.lift.toFloat(), 0f),
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
        val hitbox = placement.hitbox?.let { h ->
            world.spawn(Location(world, loc.x + h.x, loc.y + h.y, loc.z + h.z), Interaction::class.java) { i ->
                i.isPersistent = false
                i.isVisibleByDefault = false
                i.interactionWidth = h.width.toFloat()
                i.interactionHeight = h.height.toFloat()
                i.isResponsive = true
                i.addScoreboardTag(TAG)
                i.persistentDataContainer.set(locationKeyTag, PersistentDataType.STRING, key)
            }
        }
        spawned[key] = Spawned(display.uniqueId, hitbox?.uniqueId, chunks)
        for (player in world.players) {
            if (!eligible(player)) continue
            player.showEntity(plugin, display)
            hitbox?.let { player.showEntity(plugin, it) }
        }
    }

    private fun remove(key: String) {
        val parts = spawned.remove(key) ?: return
        Bukkit.getEntity(parts.display)?.remove()
        parts.hitbox?.let { Bukkit.getEntity(it)?.remove() }
    }

    private fun removeAll() {
        for (key in spawned.keys.toList()) remove(key)
    }

    /** True for display and hitbox entities JoshyMC spawned for a crate (tag and location key). */
    private fun isOurs(entity: Entity): Boolean =
        (entity is ItemDisplay || entity is Interaction) && TAG in entity.scoreboardTags &&
            entity.persistentDataContainer.has(locationKeyTag, PersistentDataType.STRING)

    private fun isTracked(entity: Entity): Boolean {
        val key = entity.persistentDataContainer.get(locationKeyTag, PersistentDataType.STRING) ?: return false
        val parts = spawned[key] ?: return false
        return entity.uniqueId == parts.display || entity.uniqueId == parts.hitbox
    }

    /** Removes untracked entities tagged with [key] from the loaded chunks around the crate (duplicates). */
    private fun removeTaggedNear(world: World, x: Int, z: Int, key: String) {
        for (cx in CrateModels.chunkOf(x, -2.0)..CrateModels.chunkOf(x, 3.0)) {
            for (cz in CrateModels.chunkOf(z, -2.0)..CrateModels.chunkOf(z, 3.0)) {
                if (!CrateModels.mayHoldEntities(x, z, cx, cz) || !world.isChunkLoaded(cx, cz)) continue
                for (entity in world.getChunkAt(cx, cz).entities) {
                    if (!isOurs(entity) || isTracked(entity)) continue
                    if (entity.persistentDataContainer.get(locationKeyTag, PersistentDataType.STRING) == key) entity.remove()
                }
            }
        }
    }

    /** Removes JoshyMC crate model entities this instance isn't tracking. Never touches anything else. */
    private fun removeStrays(entities: Collection<Entity>) {
        for (entity in entities) if (isOurs(entity) && !isTracked(entity)) entity.remove()
    }

    private fun removeStraysEverywhere() {
        for (world in Bukkit.getWorlds()) {
            removeStrays(world.getEntitiesByClasses(ItemDisplay::class.java, Interaction::class.java))
        }
    }

    // ── Hitbox clicks (issue #1451) ────────────────────────────────────

    /**
     * Right-click on a hitbox. HIGHEST and not ignoring cancellation, exactly like the crate
     * block's own handler, so a region plugin can't make the hitbox behave differently from the
     * block. The event itself is always cancelled: the entity has nothing else to do.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onHitboxInteract(event: PlayerInteractEntityEvent) {
        val hitbox = event.rightClicked as? Interaction ?: return
        if (!isOurs(hitbox)) return
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return
        route(event.player, hitbox, leftClick = false)
    }

    /** The "interact at" half of the same right-click: cancelled, never acted on (no double-fire). */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onHitboxInteractAt(event: PlayerInteractAtEntityEvent) {
        val hitbox = event.rightClicked as? Interaction ?: return
        if (isOurs(hitbox)) event.isCancelled = true
    }

    /** Left-click on a hitbox: Paper reports attacks on Interaction entities here (no damage event). */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onHitboxAttack(event: PrePlayerAttackEntityEvent) {
        val hitbox = event.attacked as? Interaction ?: return
        if (!isOurs(hitbox)) return
        event.isCancelled = true
        route(event.player, hitbox, leftClick = true)
    }

    /**
     * After a hitbox right-click, the client may also report using its held item in the air in
     * the same tick. The block route denies the held item's use; this does the same, so a key
     * that is also a usable item is never used up by the click that opened the crate.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onUseAfterHitbox(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR || spawned.isEmpty()) return
        if (crates.rightClickedCrateThisTick(event.player)) event.setUseItemInHand(Event.Result.DENY)
    }

    /** Managed displays and hitboxes can never be damaged or killed by gameplay. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDamage(event: EntityDamageEvent) {
        if (isOurs(event.entity)) event.isCancelled = true
    }

    private fun route(player: Player, hitbox: Interaction, leftClick: Boolean) {
        val key = hitbox.persistentDataContainer.get(locationKeyTag, PersistentDataType.STRING) ?: return
        // Only the hitbox this instance placed for a crate that still exists counts.
        if (spawned[key]?.hitbox != hitbox.uniqueId) {
            hitbox.remove()
            return
        }
        val loc = crates.getAllLocations().firstOrNull { CrateModels.locationKey(it.world, it.x, it.y, it.z) == key }
        if (loc == null) {
            remove(key)
            return
        }
        // The same players who could click the block: visible to them, same world, in reach.
        if (player.gameMode == GameMode.SPECTATOR || !eligible(player)) return
        if (player.world.uid != hitbox.world.uid || player.world.name != loc.world) return
        val eye = player.eyeLocation
        val box = hitbox.boundingBox
        val distanceSq = CrateModels.distanceSqToBox(
            eye.x, eye.y, eye.z,
            CrateModels.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ),
        )
        if (distanceSq > CrateModels.MAX_REACH * CrateModels.MAX_REACH) return
        val block = player.world.getBlockAt(loc.x, loc.y, loc.z)
        if (block.type.isAir) return
        crates.handleModelClick(player, block, leftClick)
    }

    // ── Visibility ─────────────────────────────────────────────────────

    private fun eligible(player: Player): Boolean =
        CrateModels.canSee(BedrockUtil.isBedrockPlayer(player), player.uniqueId in packLoaded)

    private fun applyVisibility(player: Player) {
        val show = eligible(player)
        for (parts in spawned.values) {
            for (id in listOfNotNull(parts.display, parts.hitbox)) {
                val entity = Bukkit.getEntity(id) ?: continue
                if (show) player.showEntity(plugin, entity) else player.hideEntity(plugin, entity)
            }
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
            if (loc.world != world || !CrateModels.mayHoldEntities(loc.x, loc.z, cx, cz)) continue
            if (CrateModels.locationKey(loc.world, loc.x, loc.y, loc.z) in spawned) continue
            spawn(loc)
        }
    }

    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        // Displays and hitboxes are never saved, but if one ever was (a crash mid-write, an old
        // build), it is removed here instead of stacking on top of the live one.
        removeStrays(event.entities)
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        if (spawned.isEmpty()) return
        val world = event.world.name
        val chunk = CrateModels.chunkKey(event.chunk.x, event.chunk.z)
        val prefix = "$world:"
        for ((key, parts) in spawned.entries.toList()) {
            if (key.startsWith(prefix) && chunk in parts.chunks) remove(key)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun fmt(v: Double): String = if (v == kotlin.math.floor(v)) v.toLong().toString() else "%.2f".format(v)

    private fun rotationText(rotation: CrateModels.Rotation): String = when (rotation) {
        CrateModels.Rotation.Auto -> "auto"
        is CrateModels.Rotation.Fixed -> "${fmt(rotation.degrees.toDouble())}°"
    }

    /** Reads the bundled pack's item definitions and the bounds of every configured model. */
    private fun readBundledPack() {
        bundledItemDefinitions = emptySet()
        modelBounds = emptyMap()
        val stream = plugin.getResource("resourcepack.zip") ?: run {
            plugin.logger.warning("[Crates] resourcepack.zip is missing from the plugin JAR; custom crate models are off.")
            return
        }
        val wanted = settings.models.values.mapNotNull { CrateModels.itemDefinitionPath(it.model) }.toSet()
        val definitions = mutableSetOf<String>()
        val definitionJson = mutableMapOf<String, String>()
        val modelJson = mutableMapOf<String, String>()
        try {
            // Pass 1: every item definition's name, and the JSON of the configured ones.
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.name.endsWith(".json") || "/items/" !in entry.name) continue
                    definitions += entry.name
                    if (entry.name in wanted) definitionJson[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            // Pass 2: only the model files those definitions name.
            val modelPaths = definitionJson.values.flatMap(CrateModelBounds::modelRefs).mapNotNull(CrateModelBounds::modelPath).toSet()
            if (modelPaths.isNotEmpty()) {
                plugin.getResource("resourcepack.zip")?.let { again ->
                    ZipInputStream(again).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.name in modelPaths) modelJson[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Could not read the bundled resource pack (${e.message}); custom crate models are off.")
            return
        }
        bundledItemDefinitions = definitions
        val bounds = mutableMapOf<String, CrateModels.ModelBounds?>()
        for (model in settings.models.values) {
            val path = CrateModels.itemDefinitionPath(model.model) ?: continue
            val json = definitionJson[path] ?: continue
            bounds[model.model] = CrateModelBounds.union(
                CrateModelBounds.modelRefs(json).map { ref ->
                    CrateModelBounds.modelPath(ref)?.let(modelJson::get)?.let(CrateModelBounds::elementBounds)
                }
            )
        }
        modelBounds = bounds
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
