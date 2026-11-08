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
import org.bukkit.scheduler.BukkitTask
import org.joml.Vector3f
import java.time.Clock
import java.time.Month
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
 * - Monthly rotation (issue #1460): crates listed in `monthly.crates` show the current calendar
 *   month's model (read in `monthly.timezone`), else their static model, else the vanilla block
 *   ([CrateModels.resolve]). Only the display's item model changes, never the crate.
 *
 * No repeating task: everything is driven by chunk, pack-status and crate-location events, plus
 * (only while rotation is active) one delayed month check that reschedules itself at most an
 * hour ahead and redraws just the rotating crates whose model actually changed.
 */
class CrateModelDisplays(private val plugin: Joshymc, private val crates: CrateManager) : Listener {

    companion object {
        const val TAG = "joshymc_crate_model"
        private const val CONFIG_PATH = "crates.custom-models"
        private val ITEM_DEFINITION = Regex("^assets/([a-z0-9_.-]+)/items/([a-z0-9_./-]+)\\.json$")
    }

    /** The location key ("world:x:y:z") on every JoshyMC crate display and hitbox. */
    private val locationKeyTag = NamespacedKey(plugin, "crate_model")

    /** What one crate location has spawned. Only ids and chunk keys: no entity or world references. */
    private data class Spawned(val display: UUID, val hitbox: UUID?, val chunks: Set<Long>, val model: String)

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

    /** Wall clock the calendar month is read from (replaceable so month changes can be tested). */
    var clock: Clock = Clock.systemUTC()

    /** The month every drawn rotating crate currently uses. */
    var activeMonth: Month = Month.JANUARY
        private set

    /** The one pending month check while rotation is active (never one per crate). */
    private var rotationTask: BukkitTask? = null

    fun start() {
        settings = CrateModels.parse(sectionSource(plugin.config.getConfigurationSection(CONFIG_PATH)))
        for (problem in settings.problems) plugin.logger.warning("[Crates] $CONFIG_PATH.$problem")
        activeMonth = CrateModels.monthAt(clock.instant(), settings.monthly.zone)
        readBundledPack()
        for (model in settings.models.values) {
            if (!modelAvailable(model)) {
                plugin.logger.warning("[Crates] Custom model '${model.model}' for crate '${model.crateId}' is not in the bundled resource pack; that crate keeps its vanilla look.")
            } else if (modelBounds[model.model] == null) {
                plugin.logger.warning("[Crates] Could not read the elements of '${model.model}'; its hitbox assumes it fills one block.")
            }
        }
        warnMissingMonthlyModels()
        refreshAll()
        if (settings.enabled) {
            plugin.logger.info("[Crates] Custom crate models: ${spawned.size} shown (${spawned.values.count { it.hitbox != null }} with a hitbox), ${settings.models.size} configured (gamemodes: ${settings.gamemodes.joinToString()}).")
            if (settings.monthly.active) plugin.logger.info("[Crates] ${monthlySummary()}")
        }
        scheduleRotation()
        for ((key, problem) in placementProblems) plugin.logger.warning("[Crates] Crate model at $key not drawn: $problem")
    }

    fun stop() {
        rotationTask?.cancel()
        rotationTask = null
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
        if (settings.monthly.enabled || settings.monthly.crates.isNotEmpty()) out += monthlyLines()
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
            .filter { settings.models[it.lowercase()] == null && !rotates(it) }.distinct().sorted()
        if (settings.enabled && unmapped.isNotEmpty()) out += "  No model configured (vanilla look): ${unmapped.joinToString()}"
        for ((key, problem) in placementProblems.toSortedMap()) out += "  problem at $key: $problem"
        for (problem in settings.problems) out += "  problem: $problem"
        return out
    }

    // ── Monthly rotation (issue #1460) ─────────────────────────────────

    /** One line: this month, its model and the rotating crates (startup log and `/crate models monthly`). */
    fun monthlySummary(): String {
        val m = settings.monthly
        val key = m.modelFor(activeMonth)
        val state = when {
            key == null -> "no model set, using fallbacks"
            !available(key) -> "$key (not in the pack, using fallbacks)"
            else -> key
        }
        return "Monthly crate models: ${if (m.active) "on" else if (m.enabled) "on, but no crate listed" else "off"}; " +
            "${CrateModels.monthId(activeMonth)} in ${m.zone.id} -> $state; " +
            "rotating crates: ${m.crates.sorted().joinToString().ifEmpty { "none" }}"
    }

    /** `/crate models monthly`: the summary, every month's model and availability, and each rotating crate's fallback. */
    fun monthlyLines(): List<String> {
        val m = settings.monthly
        val out = mutableListOf("  " + monthlySummary())
        val next = CrateModels.nextMonthStart(clock.instant(), m.zone)
        out += "    Next month starts ${next.atZone(m.zone)} (checked at most every hour)"
        for (month in Month.entries) {
            val key = m.modelFor(month)
            val state = when {
                key == null -> "not set"
                !available(key) -> "$key — not in the pack"
                else -> "$key — ok"
            }
            out += "    ${if (month == activeMonth) ">" else " "} ${CrateModels.monthId(month)}: $state"
        }
        for (id in m.crates.sorted()) {
            val real = crates.resolveCrateId(id)
            if (crates.getCrate(real) == null) {
                out += "    crate $id: no crate with this id"
                continue
            }
            val r = resolve(real)
            val what = when (r.source) {
                CrateModels.ModelSource.MONTHLY -> "${r.model!!.model} (this month's model)"
                CrateModels.ModelSource.STATIC_FALLBACK -> "${r.model!!.model} (static fallback: ${r.note})"
                CrateModels.ModelSource.STATIC -> "${r.model!!.model} (static)"
                CrateModels.ModelSource.NONE -> "vanilla block (${r.note})"
            }
            out += "    crate ${if (real != id) "$id -> $real" else id}: $what"
        }
        return out
    }

    /** One warning (not one per month or crate) listing the months whose model can't be drawn. */
    private fun warnMissingMonthlyModels() {
        val m = settings.monthly
        if (!settings.enabled || !m.active) return
        val missing = Month.entries.filter { month -> m.modelFor(month)?.let(::available) != true }
        if (missing.isEmpty()) return
        plugin.logger.warning("[Crates] Monthly crate models missing or not in the bundled pack for: " +
            missing.joinToString { month -> CrateModels.monthId(month) + (m.modelFor(month)?.let { " ($it)" } ?: "") } +
            ". In those months rotating crates use their static model, or the vanilla block.")
        for (id in m.crates) {
            if (crates.getCrate(crates.resolveCrateId(id)) == null) {
                plugin.logger.warning("[Crates] crates.custom-models.monthly.crates lists '$id', which is not a crate id; it is ignored.")
            }
        }
    }

    /** Schedules the next month check: one delayed task, only while rotation is active. */
    private fun scheduleRotation() {
        rotationTask?.cancel()
        rotationTask = null
        if (!settings.enabled || !settings.monthly.active) return
        val delay = CrateModels.ticksFor(CrateModels.checkDelayMillis(clock.instant(), settings.monthly.zone))
        rotationTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            rotationTask = null
            try {
                checkMonth()
            } catch (e: Exception) {
                plugin.logger.warning("[Crates] Monthly crate model check failed: ${e.message}")
            }
            scheduleRotation()
        }, delay)
    }

    /**
     * Applies the current calendar month. Does nothing while the month hasn't changed; otherwise
     * redraws only the rotating crates whose drawn model differs from the new one.
     */
    fun checkMonth(): Int {
        val now = CrateModels.monthAt(clock.instant(), settings.monthly.zone)
        if (now == activeMonth) return 0
        val previous = activeMonth
        activeMonth = now
        val redrawn = refreshRotating()
        val key = settings.monthly.modelFor(now)
        plugin.logger.info("[Crates] Month changed (${CrateModels.monthId(previous)} -> ${CrateModels.monthId(now)}): " +
            "${key ?: "no monthly model set"}${if (key != null && !available(key)) " is not in the pack, rotating crates use their fallback" else ""}; " +
            "$redrawn crate model(s) redrawn.")
        return redrawn
    }

    /** Redraws the rotating crates whose model no longer matches; returns how many were redrawn. */
    private fun refreshRotating(): Int {
        var redrawn = 0
        for (loc in crates.getAllLocations()) {
            if (!rotates(crates.resolveCrateId(loc.crateType))) continue
            val key = CrateModels.locationKey(loc.world, loc.x, loc.y, loc.z)
            val want = expectedModel(loc)?.model
            val have = spawned[key]?.model
            if (want == have) continue
            val world = Bukkit.getWorld(loc.world)
            if (world == null) {
                remove(key)
                continue
            }
            // refresh() only spawns when the chunks are loaded; otherwise the next chunk load does.
            refresh(world, loc.x, loc.y, loc.z)
            redrawn++
        }
        return redrawn
    }

    // ── Spawning ───────────────────────────────────────────────────────

    private fun modelAvailable(model: CrateModels.Model): Boolean = available(model.model)

    /** True when the bundled resource pack has an item definition for model key [key]. */
    fun available(key: String): Boolean {
        val path = CrateModels.itemDefinitionPath(key) ?: return false
        return path in bundledItemDefinitions
    }

    /** Bundled item model keys that look like crate chest models (`*_chest`, `*_crate`), for tab completion. */
    fun chestModelKeys(): List<String> = bundledItemDefinitions.mapNotNull { path ->
        val m = ITEM_DEFINITION.find(path) ?: return@mapNotNull null
        val key = m.groupValues[1] + ":" + m.groupValues[2]
        key.takeIf { it.endsWith("_chest") || it.endsWith("_crate") }
    }.sorted()

    /** Why [key] can't be used as a crate model (bad key or not in the bundled pack), or null when it can. */
    fun modelProblem(key: String): String? = when {
        !CrateModels.isModelKey(key) -> "'$key' is not a namespaced item model key like joshymc:cabin_chest"
        !available(key) -> "'$key' is not in the bundled resource pack (${CrateModels.itemDefinitionPath(key)})"
        else -> null
    }

    /** The model (static or this month's) [crateId] shows in [month], with where it came from. */
    fun resolve(crateId: String, month: Month = activeMonth): CrateModels.Resolved =
        CrateModels.resolve(settings, crates.resolveCrateId(crateId), month, ::available, crates::resolveCrateId)

    private fun rotates(crateId: String): Boolean = CrateModels.rotates(settings, crateId, crates::resolveCrateId)

    /** The model this location should show, ignoring whether its chunks are loaded. */
    private fun expectedModel(loc: CrateManager.CrateLocation): CrateModels.Model? {
        val model = resolve(loc.crateType).model ?: return null
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
        spawned[key] = Spawned(display.uniqueId, hitbox?.uniqueId, chunks, model.model)
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
        val wanted = allModelKeys().mapNotNull(CrateModels::itemDefinitionPath).toSet()
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
        for (modelKey in allModelKeys()) {
            val path = CrateModels.itemDefinitionPath(modelKey) ?: continue
            val json = definitionJson[path] ?: continue
            bounds[modelKey] = CrateModelBounds.union(
                CrateModelBounds.modelRefs(json).map { ref ->
                    CrateModelBounds.modelPath(ref)?.let(modelJson::get)?.let(CrateModelBounds::elementBounds)
                }
            )
        }
        modelBounds = bounds
    }

    /** Every model key the config can draw: static entries plus the monthly ones. */
    private fun allModelKeys(): Set<String> = settings.models.values.map { it.model }.toSet() + settings.monthly.models.values

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
