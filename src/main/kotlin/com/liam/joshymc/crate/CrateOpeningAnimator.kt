package com.liam.joshymc.crate

import com.liam.joshymc.Joshymc
import com.liam.joshymc.crate.CrateOpeningAnimation.Cue
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Plays the physical crate opening animation (issue #1503) for one player at a time per player,
 * driven by the Bukkit-free [CrateOpeningAnimation].
 *
 * **Player-specific.** Everything is shown to the opener only: particles and sounds go through
 * `player.spawnParticle` / `player.playSound`, and when the opener sees the crate's custom model
 * the permanent display is hidden *for them* while a temporary, non-persistent, hidden-by-default
 * copy (same item, yaw, scale, offset and brightness) is shown only to them and animated. Other
 * players keep seeing the untouched permanent model and can open the same crate at the same time
 * with their own copy. The permanent display, its hitbox and the crate block are never changed.
 *
 * **Cosmetic only.** [play] runs `onReveal` exactly once (when the opening finishes, or as soon
 * as anything interrupts it: moving away, changing world, chunk unload, a failed tick, a stuck
 * session). Quit and [stop] (reload/shutdown) clean up without the reveal; the crate manager
 * already holds the drawn reward in `crate_pending_rewards` and settles it its usual way.
 *
 * Players without the pack, Bedrock players and crates with no (or a missing) model get the
 * same particles and sounds around the vanilla block. One repeating task runs only while at
 * least one animation is playing.
 */
class CrateOpeningAnimator(private val plugin: Joshymc, private val models: CrateModelDisplays) : Listener {

    companion object {
        const val TAG = "joshymc_crate_anim"
        private const val CONFIG_PATH = "crates.opening-animation"
    }

    private class Session(
        val player: UUID,
        val world: String,
        val x: Int, val y: Int, val z: Int,
        val crateId: String,
        val effective: CrateOpeningAnimation.Effective,
        val profile: CrateOpeningAnimation.Profile,
        /** Front direction of the crate (unit x/z), for the lock/emblem accent. */
        val frontX: Double, val frontZ: Double,
        val onReveal: () -> Unit,
    ) {
        var tick = 0
        var revealed = false
        var temp: UUID? = null
        var hidden: UUID? = null
        var baseLift = 0f
        var baseScale = 1f
        var baseRight: Quaternionf = Quaternionf()
        val locationKey get() = CrateModels.locationKey(world, x, y, z)
        val cx get() = x + 0.5
        val cz get() = z + 0.5
    }

    private data class ParticleSpec(val particle: Particle, val data: Any?)

    var settings: CrateOpeningAnimation.Settings = CrateOpeningAnimation.DEFAULTS
        private set

    private val book = CrateOpeningAnimation.Book<Session>()
    private var task: BukkitTask? = null
    /** Particle name -> resolved particle (null = unusable), so each bad name is logged once. */
    private val particleCache = mutableMapOf<String, ParticleSpec?>()

    /** Number of animations playing right now (for `/crate models` and the checks). */
    val playing: Int get() = book.size

    fun start() {
        settings = CrateOpeningAnimation.parse(sectionSource(plugin.config.getConfigurationSection(CONFIG_PATH)))
        particleCache.clear()
        for (problem in settings.problems) plugin.logger.warning("[Crates] $CONFIG_PATH.$problem")
        for (profile in settings.profiles.values) {
            for (name in listOf(profile.ambient, profile.accent, profile.burst)) resolveParticle(name, profile.id)
        }
    }

    /** Reload/shutdown: removes every temporary copy and re-shows the permanent models. No reveal runs. */
    fun stop() {
        task?.cancel()
        task = null
        for (s in book.clear()) cleanup(s)
    }

    /** Quit: the animation stops silently (the crate manager keeps any drawn reward pending). */
    fun cancel(player: UUID) {
        val s = book.of(player) ?: return
        book.end(player.toString(), s)
        cleanup(s)
    }

    fun isPlaying(player: UUID): Boolean = book.of(player.toString()) != null

    private fun CrateOpeningAnimation.Book<Session>.of(player: UUID) = of(player.toString())

    fun enabledFor(crateId: String): Boolean = settings.effective(crateId).enabled

    /** The profile [crateId] opens with right now (this month's theme while its monthly model shows). */
    fun profileFor(crateId: String): CrateOpeningAnimation.Profile {
        val month = try {
            // Issue #1520: the crate `monthly.crate-ids` maps to this month opens with its theme too.
            if (models.resolve(crateId).source == CrateModels.ModelSource.MONTHLY || models.isActiveMappedCrate(crateId)) CrateModels.monthId(models.activeMonth) else null
        } catch (_: Exception) { null }
        return CrateOpeningAnimation.resolveProfile(settings, crateId, month)
    }

    /**
     * Starts [player]'s animation at the crate [block]. Returns false (and runs nothing) when
     * animations are off for this crate or it couldn't start; the caller then opens the reward
     * GUI itself, exactly as before. When it returns true, [onReveal] runs exactly once later
     * unless the player quits or the plugin stops first.
     */
    fun play(player: Player, block: Block, crateId: String, onReveal: () -> Unit): Boolean {
        val effective = settings.effective(crateId)
        if (!effective.enabled) return false
        return try {
            book.of(player.uniqueId)?.let { old -> book.end(player.uniqueId.toString(), old); cleanup(old) }
            val perm = if (models.showsModelsTo(player)) models.displayAt(block.world.name, block.x, block.y, block.z) else null
            val (fx, fz) = front(perm, block)
            val s = Session(player.uniqueId, block.world.name, block.x, block.y, block.z, crateId, effective,
                profileFor(crateId), fx, fz, onReveal)
            if (perm != null) spawnCopy(player, perm, s)
            book.begin(player.uniqueId.toString(), s.locationKey, s)
            ensureTask()
            true
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Opening animation could not start for ${player.name}: ${e.message}")
            false
        }
    }

    /** Mass opening: one short burst and sound instead of an animation per key. */
    fun burst(player: Player, block: Block, crateId: String) {
        if (settings.massOpen == CrateOpeningAnimation.MassOpen.NONE) return
        val effective = settings.effective(crateId)
        if (!effective.enabled) return
        try {
            val profile = profileFor(crateId)
            val at = Location(block.world, block.x + 0.5, block.y + 1.0, block.z + 0.5)
            spawn(player, profile.burst, profile, at, CrateOpeningAnimation.burstCount(effective.particleIntensity * 0.6), 0.35, 0.25, 0.35, 0.05)
            sound(player, profile.openSound, at, effective.soundVolume, 1.2f)
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Mass-open burst failed for ${player.name}: ${e.message}")
        }
    }

    // ── Tick loop ──────────────────────────────────────────────────────

    private fun ensureTask() {
        if (task != null) return
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickAll() }, 1L, 1L)
    }

    private fun tickAll() {
        for (s in book.all()) {
            try {
                step(s)
            } catch (e: Exception) {
                plugin.logger.warning("[Crates] Opening animation failed mid-way, skipping to the reward: ${e.message}")
                finish(s, reveal = true)
            }
        }
        if (book.size == 0) {
            task?.cancel()
            task = null
        }
    }

    private fun step(s: Session) {
        val player = Bukkit.getPlayer(s.player)
        if (player == null || !player.isOnline) { finish(s, reveal = false); return }
        val e = s.effective
        val world = player.world
        val sameWorld = world.name == s.world
        val distanceSq = if (sameWorld) player.location.distanceSquared(Location(world, s.cx, s.y + 0.5, s.cz)) else Double.MAX_VALUE
        val chunkLoaded = sameWorld && world.isChunkLoaded(s.x shr 4, s.z shr 4)
        val deadline = CrateOpeningAnimation.deadline(e.durationTicks, e.closeTicks)
        if (CrateOpeningAnimation.interruption(sameWorld, distanceSq, settings.maxDistance, chunkLoaded, s.tick, deadline) != null) {
            finish(s, reveal = true)
            return
        }

        val t = s.tick
        val center = Location(world, s.cx, s.y + 0.6, s.cz)
        val accentAt = Location(world, s.cx + s.frontX * 0.52, s.y + 0.55, s.cz + s.frontZ * 0.52)
        val p = s.profile

        for (cue in CrateOpeningAnimation.cues(t, e.durationTicks, e.closeTicks)) {
            when (cue) {
                Cue.ACTIVATE -> {
                    sound(player, p.activateSound, center, e.soundVolume, 1.0f)
                    spawn(player, p.ambient, p, center, (4 * e.particleIntensity).toInt(), 0.45, 0.35, 0.45, 0.01)
                }
                Cue.BUILD -> sound(player, p.buildSound, center, e.soundVolume * 0.6, CrateOpeningAnimation.buildPitch(t, e.durationTicks))
                Cue.OPEN -> {
                    sound(player, p.openSound, center, e.soundVolume, 1.0f)
                    spawn(player, p.burst, p, center.clone().add(0.0, 0.4, 0.0), CrateOpeningAnimation.burstCount(e.particleIntensity), 0.3, 0.3, 0.3, 0.08)
                }
                Cue.REVEAL -> {
                    sound(player, p.revealSound, center, e.soundVolume * 0.7, 1.0f)
                    reveal(s)
                }
                Cue.CLOSE -> sound(player, p.closeSound, center, e.soundVolume * 0.6, 1.0f)
                Cue.DONE -> { finish(s, reveal = true); return }
            }
        }
        if (s.revealed && s.temp == null) {
            // Fallback (no model copy): nothing left to show after the reveal.
            finish(s, reveal = true)
            return
        }

        val ambient = CrateOpeningAnimation.ambientCount(t, e.durationTicks, e.particleIntensity)
        if (ambient > 0) spawn(player, p.ambient, p, center, ambient, 0.45, 0.35, 0.45, 0.01)
        val accent = CrateOpeningAnimation.accentCount(t, e.durationTicks, e.particleIntensity)
        if (accent > 0) spawn(player, p.accent, p, accentAt, accent, 0.06, 0.06, 0.06, 0.0)

        if (t % CrateOpeningAnimation.POSE_INTERVAL == 0) applyPose(player, s)
        s.tick++
    }

    private fun reveal(s: Session) {
        if (s.revealed) return
        s.revealed = true
        try {
            s.onReveal()
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Crate reveal failed after the opening animation: ${e.message}")
        }
    }

    /** Ends [s] once: optionally reveals (if it hadn't yet), then removes its copy and re-shows the model. */
    private fun finish(s: Session, reveal: Boolean) {
        if (!book.end(s.player.toString(), s)) return
        cleanup(s)
        if (reveal) reveal(s)
    }

    private fun cleanup(s: Session) {
        s.temp?.let { Bukkit.getEntity(it)?.remove() }
        s.temp = null
        val player = Bukkit.getPlayer(s.player) ?: return
        val perm = models.displayAt(s.world, s.x, s.y, s.z)
        if (perm != null && models.showsModelsTo(player)) player.showEntity(plugin, perm)
        s.hidden?.let { id -> if (id != perm?.uniqueId) Bukkit.getEntity(id)?.let { player.showEntity(plugin, it) } }
        s.hidden = null
    }

    // ── Model copy ─────────────────────────────────────────────────────

    private fun spawnCopy(player: Player, perm: ItemDisplay, s: Session) {
        val base = perm.transformation
        s.baseLift = base.translation.y
        s.baseScale = base.scale.x
        s.baseRight = Quaternionf(base.rightRotation)
        val copy = try {
            perm.world.spawn(perm.location, ItemDisplay::class.java) { d ->
                d.isPersistent = false
                d.isVisibleByDefault = false
                d.setRotation(perm.location.yaw, perm.location.pitch)
                d.setItemStack(perm.itemStack.clone())
                d.itemDisplayTransform = perm.itemDisplayTransform
                d.transformation = base
                perm.brightness?.let { d.brightness = it }
                d.glowColorOverride = Color.fromRGB(s.profile.color and 0xFFFFFF)
                d.addScoreboardTag(TAG)
            }
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Could not spawn the opening animation model (particles only): ${e.message}")
            null
        } ?: return
        s.temp = copy.uniqueId
        player.showEntity(plugin, copy)
        player.hideEntity(plugin, perm)
        s.hidden = perm.uniqueId
    }

    private fun applyPose(player: Player, s: Session) {
        val id = s.temp ?: return
        val copy = Bukkit.getEntity(id) as? ItemDisplay
        if (copy == null || !copy.isValid || !models.showsModelsTo(player)) {
            // The copy vanished (chunk unload, another plugin) or the opener's pack was
            // discarded: drop the copy and continue with particles only.
            s.temp = null
            cleanup(s)
            return
        }
        // The permanent model may have been redrawn (pack status, /crate models refresh): keep it hidden.
        val perm = models.displayAt(s.world, s.x, s.y, s.z)
        if (perm != null && perm.uniqueId != s.hidden) {
            s.hidden?.let { old -> Bukkit.getEntity(old)?.let { player.showEntity(plugin, it) } }
            player.hideEntity(plugin, perm)
            s.hidden = perm.uniqueId
        } else if (perm != null) {
            player.hideEntity(plugin, perm)
        }
        val e = s.effective
        val pose = CrateOpeningAnimation.pose(s.tick, e.durationTicks, e.closeTicks, e.movementIntensity, s.baseScale.toDouble())
        val scale = (s.baseScale * pose.scale).toFloat()
        copy.interpolationDelay = 0
        copy.interpolationDuration = CrateOpeningAnimation.POSE_INTERVAL
        copy.transformation = Transformation(
            Vector3f(0f, (s.baseLift + pose.lift).toFloat(), 0f),
            Quaternionf().rotateX(Math.toRadians(pose.pitch).toFloat()).rotateZ(Math.toRadians(pose.roll).toFloat()),
            Vector3f(scale, scale, scale),
            s.baseRight,
        )
        copy.isGlowing = pose.glow
    }

    /** Front of the crate as a unit x/z vector: the model's yaw, else the block's facing, else south. */
    private fun front(perm: ItemDisplay?, block: Block): Pair<Double, Double> {
        if (perm != null) {
            val yaw = Math.toRadians(perm.location.yaw.toDouble())
            return -sin(yaw) to cos(yaw)
        }
        val face = (block.blockData as? Directional)?.facing?.takeIf { it in BlockFace.entries.take(4) } ?: BlockFace.SOUTH
        return face.modX.toDouble() to face.modZ.toDouble()
    }

    // ── Effects ────────────────────────────────────────────────────────

    private fun resolveParticle(name: String, profileId: String): ParticleSpec? = particleCache.getOrPut(name) {
        val particle = try { Particle.valueOf(name) } catch (_: Exception) { null }
        val spec = when {
            particle == null -> null
            particle.dataType == Void::class.java -> ParticleSpec(particle, null)
            particle.dataType == Particle.DustOptions::class.java -> ParticleSpec(particle, Unit)
            particle.dataType == Color::class.java -> ParticleSpec(particle, Unit)
            else -> null
        }
        if (spec == null) {
            plugin.logger.warning("[Crates] $CONFIG_PATH.profiles.$profileId: particle '$name' doesn't exist or needs data it can't be given; using END_ROD.")
        }
        spec
    }

    private fun spawn(player: Player, name: String, profile: CrateOpeningAnimation.Profile, at: Location, count: Int,
                      ox: Double, oy: Double, oz: Double, speed: Double) {
        if (count <= 0) return
        val spec = resolveParticle(name, profile.id) ?: ParticleSpec(Particle.END_ROD, null)
        val color = Color.fromRGB(profile.color and 0xFFFFFF)
        val data: Any? = when (spec.particle.dataType) {
            Particle.DustOptions::class.java -> Particle.DustOptions(color, 1.0f)
            Color::class.java -> color
            else -> null
        }
        player.spawnParticle(spec.particle, at.x, at.y, at.z, count, ox, oy, oz, speed, data)
    }

    private fun sound(player: Player, key: String?, at: Location, volume: Double, pitch: Float) {
        if (key == null || volume <= 0.0) return
        val k = try { Key.key(key) } catch (_: Exception) { return }
        player.playSound(Sound.sound(k, Sound.Source.BLOCK, volume.toFloat(), pitch), at.x, at.y, at.z)
    }

    /** Copies are never saved, but if one ever was (crash mid-write), it is removed when it loads. */
    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        for (entity in event.entities) if (entity is ItemDisplay && TAG in entity.scoreboardTags) entity.remove()
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
