package com.liam.joshymc.cosmetic

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.BackCosmeticItem
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.BedrockUtil
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

/**
 * Draws back cosmetics (issue #1516): while a player wears a [BackCosmeticItem] in the chest
 * slot, one non-persistent [ItemDisplay] of its model follows them.
 *
 * - **Follows, never rides**: the display is teleported to the wearer every tick (yaw = body
 *   yaw) and the client smooths it with `teleport_duration`. A passenger would make Paper refuse
 *   every teleport of the wearer (see [BackCosmeticRules]).
 * - **One per wearer, no leaks**: displays are non-persistent (never saved with a chunk), tagged
 *   [BackCosmeticRules.TAG] + owner UUID, removed on unequip, quit, death, hide reasons, reload
 *   and shutdown, and respawned (old one removed first) on world change or a long teleport.
 *   Untracked tagged displays are swept at start and whenever a chunk's entities load.
 * - **Purely visual**: no hitbox (display entities can't be hit or collided with), hidden by
 *   default and shown only to Java viewers whose JoshyMC pack loaded and who can see the wearer.
 *   Hidden while the wearer is vanished, in Mod Mode, invisible, spectating, dead, combat-tagged,
 *   in the event world, or not standing/sneaking ([BackCosmeticRules.hidden]).
 * - Scoped items only draw in their own gamemode's worlds.
 */
class BackCosmeticManager(private val plugin: Joshymc) : Listener {

    private val ownerKey = NamespacedKey(plugin, BackCosmeticRules.OWNER_KEY)

    /** One live attachment per wearer. Only ids and plain values: no entity or world references kept. */
    private class Attachment(
        val display: UUID,
        val itemId: String,
        var sneaking: Boolean,
        var shown: MutableSet<UUID> = mutableSetOf(),
    )

    private val attachments = mutableMapOf<UUID, Attachment>()

    /** Players whose JoshyMC pack loaded; kept across a soft reload (clients don't resend it). */
    private val packLoaded = mutableSetOf<UUID>()

    var settings = BackCosmeticRules.Settings()
        private set

    private var task: BukkitTask? = null
    private var tick = 0L
    private var running = false

    fun start() {
        val section = plugin.config.getConfigurationSection("back-cosmetics")
        settings = BackCosmeticRules.Settings(
            enabled = plugin.isFeatureEnabled("back-cosmetics"),
            showToWearer = section?.getBoolean("show-to-wearer", true) ?: true,
            hideInCombat = section?.getBoolean("hide-in-combat", true) ?: true,
            smoothingTicks = BackCosmeticRules.smoothing(
                section?.getInt("smoothing-ticks", BackCosmeticRules.DEFAULT_SMOOTHING_TICKS)
                    ?: BackCosmeticRules.DEFAULT_SMOOTHING_TICKS
            ),
        )
        removeStraysEverywhere()
        plugin.server.pluginManager.registerEvents(this, plugin)
        running = true
        if (!settings.enabled) {
            plugin.logger.info("[BackCosmetics] Back cosmetics are disabled (features.back-cosmetics).")
            return
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { onTick() }, 1L, 1L)
    }

    fun stop() {
        running = false
        task?.cancel()
        task = null
        HandlerList.unregisterAll(this)
        for (owner in attachments.keys.toList()) detach(owner)
        removeStraysEverywhere()
    }

    /** `/joshymc` diagnostics and tests: how many wings are drawn right now. */
    fun activeCount(): Int = attachments.size

    // ── Tick ───────────────────────────────────────────────────────────

    private fun onTick() {
        tick++
        try {
            // Every 5 ticks: who wears what, who may see it. Every tick: follow.
            if (tick % 5 == 0L) {
                val online = Bukkit.getOnlinePlayers()
                for (player in online) refreshWearer(player)
                for (owner in attachments.keys.toList()) {
                    if (Bukkit.getPlayer(owner) == null) detach(owner)
                }
                for ((owner, attachment) in attachments) {
                    val wearer = Bukkit.getPlayer(owner) ?: continue
                    syncViewers(wearer, attachment, online)
                }
            }
            for ((owner, attachment) in attachments.entries.toList()) {
                val wearer = Bukkit.getPlayer(owner) ?: continue
                follow(wearer, attachment)
            }
        } catch (e: Exception) {
            plugin.logger.warning("[BackCosmetics] Tick failed: ${e.message}")
        }
    }

    /** Attaches, swaps or detaches [player]'s back model to match their chest slot and state. */
    private fun refreshWearer(player: Player) {
        val item = backItem(player)
        val hidden = item?.let { BackCosmeticRules.hidden(settings, wearerState(player, it)) }
        val current = attachments[player.uniqueId]
        if (item == null || hidden != null) {
            if (current != null) detach(player.uniqueId)
            return
        }
        if (current != null && current.itemId == item.id && Bukkit.getEntity(current.display)?.isValid == true) return
        detach(player.uniqueId)
        attach(player, item)
    }

    private fun backItem(player: Player): CustomItem? {
        val chest = player.inventory.chestplate ?: return null
        if (chest.type.isAir) return null
        val id = plugin.itemManager.getCustomItemId(chest) ?: return null
        val item = plugin.itemManager.getItem(id) ?: return null
        return item.takeIf { it is BackCosmeticItem }
    }

    private fun wearerState(player: Player, item: CustomItem): BackCosmeticRules.Wearer {
        val scope = item.gamemodeScope
        val allowedHere = scope == null || scope.gamemode == null ||
            (plugin.gamemodeLockManager.gamemodeOf(player.world) == scope.gamemode &&
                plugin.gamemodeLockManager.isAllowed(scope, player.world))
        return BackCosmeticRules.Wearer(
            allowedHere = allowedHere,
            dead = player.isDead || player.health <= 0.0,
            spectator = player.gameMode == GameMode.SPECTATOR,
            invisible = player.isInvisible || player.hasPotionEffect(PotionEffectType.INVISIBILITY),
            vanished = player.getMetadata("vanished").any { it.asBoolean() },
            modMode = runCatching { plugin.modModeManager.isModMode(player) }.getOrDefault(false),
            combatTagged = runCatching { plugin.combatManager.isTagged(player) }.getOrDefault(false),
            inEventWorld = runCatching { plugin.eventManager.isInEventWorld(player) }.getOrDefault(false),
            pose = player.pose.name,
        )
    }

    // ── Spawning and following ─────────────────────────────────────────

    private fun attach(player: Player, item: CustomItem) {
        val back = item as BackCosmeticItem
        val sneaking = player.isSneaking
        val stack = ItemStack(Material.PAPER).apply {
            editMeta { it.setItemModel(back.backModel) }
        }
        val display = player.world.spawn(followLocation(player), ItemDisplay::class.java) { d ->
            d.isPersistent = false
            d.isVisibleByDefault = false
            d.isSilent = true
            d.setGravity(false)
            d.setItemStack(stack)
            d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            d.teleportDuration = settings.smoothingTicks
            d.transformation = transformation(player, back, sneaking)
            d.addScoreboardTag(BackCosmeticRules.TAG)
            d.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, player.uniqueId.toString())
        }
        attachments[player.uniqueId] = Attachment(display.uniqueId, item.id, sneaking)
        syncViewers(player, attachments.getValue(player.uniqueId), Bukkit.getOnlinePlayers())
    }

    private fun detach(owner: UUID) {
        val attachment = attachments.remove(owner) ?: return
        Bukkit.getEntity(attachment.display)?.remove()
    }

    private fun follow(player: Player, attachment: Attachment) {
        val display = Bukkit.getEntity(attachment.display) as? ItemDisplay
        if (display == null || !display.isValid) {
            // Removed by something else (a /kill, a chunk unload): draw it again next scan.
            attachments.remove(player.uniqueId)
            return
        }
        val target = followLocation(player)
        val at = display.location
        val sameWorld = at.world?.uid == player.world.uid
        if (BackCosmeticRules.mustRespawn(sameWorld, target.x - at.x, target.y - at.y, target.z - at.z)) {
            val item = plugin.itemManager.getItem(attachment.itemId)
            detach(player.uniqueId)
            if (item != null) attach(player, item)
            return
        }
        if (at.x != target.x || at.y != target.y || at.z != target.z || at.yaw != target.yaw) display.teleport(target)
        val sneaking = player.isSneaking
        if (sneaking != attachment.sneaking) {
            attachment.sneaking = sneaking
            val back = plugin.itemManager.getItem(attachment.itemId) as? BackCosmeticItem ?: return
            display.interpolationDelay = 0
            display.interpolationDuration = BackCosmeticRules.POSE_BLEND_TICKS
            display.transformation = transformation(player, back, sneaking)
        }
    }

    /** The wearer's feet, turned with their body (not their head). */
    private fun followLocation(player: Player): Location {
        val loc = player.location
        return Location(loc.world, loc.x, loc.y, loc.z, player.bodyYaw, 0f)
    }

    private fun transformation(player: Player, item: BackCosmeticItem, sneaking: Boolean): Transformation {
        val playerScale = player.getAttribute(Attribute.SCALE)?.value ?: 1.0
        val anchor = BackCosmeticRules.anchor(sneaking, playerScale)
        val scale = BackCosmeticRules.modelScale(item.backScale, playerScale).toFloat()
        val tilt = Math.toRadians(anchor.tiltDegrees).toFloat()
        return Transformation(
            Vector3f(anchor.x.toFloat(), anchor.y.toFloat(), anchor.z.toFloat()),
            Quaternionf(AxisAngle4f(tilt, 1f, 0f, 0f)),
            Vector3f(scale, scale, scale),
            Quaternionf(),
        )
    }

    // ── Visibility ─────────────────────────────────────────────────────

    private fun syncViewers(wearer: Player, attachment: Attachment, online: Collection<Player>) {
        val display = Bukkit.getEntity(attachment.display) ?: return
        val want = mutableSetOf<UUID>()
        for (viewer in online) {
            val sees = BackCosmeticRules.viewerSees(
                settings,
                viewerIsWearer = viewer.uniqueId == wearer.uniqueId,
                bedrock = BedrockUtil.isBedrockPlayer(viewer),
                packLoaded = viewer.uniqueId in packLoaded,
                canSeeWearer = viewer.canSee(wearer),
                sameWorld = viewer.world.uid == wearer.world.uid,
            )
            if (sees) want += viewer.uniqueId
        }
        for (id in attachment.shown - want) Bukkit.getPlayer(id)?.hideEntity(plugin, display)
        for (id in want - attachment.shown) Bukkit.getPlayer(id)?.showEntity(plugin, display)
        attachment.shown = want
    }

    // ── Events ─────────────────────────────────────────────────────────

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
        // The next 5-tick scan shows or hides every model for them.
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        packLoaded.remove(event.player.uniqueId)
        detach(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        detach(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        // Respawned in the new world by the next scan (if still allowed there).
        detach(event.player.uniqueId)
    }

    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        // Displays are never saved; if one ever was (a crash mid-write), it never stacks up.
        if (running) removeStrays(event.entities)
    }

    // ── Strays ─────────────────────────────────────────────────────────

    private fun isOurs(entity: Entity): Boolean =
        entity is ItemDisplay && BackCosmeticRules.TAG in entity.scoreboardTags &&
            entity.persistentDataContainer.has(ownerKey, PersistentDataType.STRING)

    private fun isTracked(entity: Entity): Boolean = attachments.values.any { it.display == entity.uniqueId }

    private fun removeStrays(entities: Collection<Entity>) {
        for (entity in entities) if (isOurs(entity) && !isTracked(entity)) entity.remove()
    }

    private fun removeStraysEverywhere() {
        for (world in Bukkit.getWorlds()) removeStrays(world.getEntitiesByClass(ItemDisplay::class.java))
    }
}
