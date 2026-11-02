package com.liam.joshymc.lifesteal.hearts

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.hearts.HeartRules.changesHearts
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The Lifesteal heart system (issue #1341): permanent hearts per player, applied as the player's
 * maximum health, and PvP heart stealing. Runs only where this server hosts Lifesteal
 * (`networkIdentityManager.hostedGamemodes`) and only for players in a Lifesteal world
 * (`gamemodeLockManager.gamemodeOf(world)`); on a Survival server it creates no tables, registers
 * no listener and never touches anyone's health.
 *
 * The database row ([HeartStore]) is the only source of truth. [loaded] holds the hearts of online
 * Lifesteal players whose row has been read this session (the "session"); it is filled on join /
 * entering a Lifesteal world and dropped on quit, so a transfer never runs against data that
 * wasn't loaded, and a reconnect always reads the row again. Every change is written in one
 * synchronous transaction *before* anything is applied to the player, so nothing waits on a
 * delayed save, a disconnect can't lose a change, and shutdown has nothing pending. Those
 * transactions are a few indexed rows on the local SQLite file (the same synchronous pattern the
 * plugin's other one-shot claims use); nothing runs per tick.
 */
class LifestealHeartManager(private val plugin: Joshymc) {

    private val store = HeartStore(plugin.databaseManager)

    /** Effective hearts of online players with a loaded record, by UUID. */
    private val loaded = ConcurrentHashMap<UUID, Int>()

    /** Random per boot, so a death's dedupe key (victim + server tick) is never reused after a restart. */
    private val bootId = UUID.randomUUID().toString().substring(0, 8)

    private var tablesReady = false

    /** Players already told about an unreadable record this session (so it isn't logged every join). */
    private val malformedLogged = ConcurrentHashMap.newKeySet<UUID>()

    @Volatile var limits: HeartRules.Limits? = null
        private set
    @Volatile var cooldownMinutes: Int = 0
        private set

    /** This server runs Lifesteal (fixed per reload). The listener is only registered when true. */
    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes

    /** Hosted, lifesteal.yml valid and tables created: hearts can be read and changed. */
    val active: Boolean get() = hosted && tablesReady && limits != null

    /** Whether the heart system owns max health in [world] (Overload and similar skip it there). */
    fun managesHealthIn(world: World?): Boolean = hosted && plugin.gamemodeLockManager.gamemodeOf(world) == ServerGamemode.LIFESTEAL

    fun isLifesteal(player: Player): Boolean = plugin.gamemodeLockManager.gamemodeOf(player.world) == ServerGamemode.LIFESTEAL

    /** Cached hearts of an online player in Lifesteal, or null. */
    fun heartsOf(uuid: UUID): Int? = loaded[uuid]

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload, after lifestealConfigManager.load(). */
    fun refresh() {
        if (!hosted) {
            if (limits != null || loaded.isNotEmpty()) plugin.logger.info("[Hearts] This server no longer runs Lifesteal; the heart system is off.")
            limits = null
            loaded.clear()
            for (player in Bukkit.getOnlinePlayers()) restoreVanilla(player)
            return
        }
        val settings = plugin.lifestealConfigManager.settings
        if (settings == null) {
            limits = null
            loaded.clear()
            plugin.logger.severe(
                "[Hearts] lifesteal.yml is invalid, so hearts are paused: no heart is granted, stolen or changed and " +
                    "stored hearts are left exactly as they are. Fix lifesteal.yml, then /joshymc reload."
            )
            return
        }
        try {
            store.createTables()
            tablesReady = true
        } catch (e: Exception) {
            tablesReady = false
            limits = null
            plugin.logger.severe("[Hearts] Could not create the heart tables, so hearts are paused: ${e.message}")
            return
        }
        limits = HeartRules.Limits.of(settings.hearts)
        cooldownMinutes = settings.pvp.sameVictimCooldownMinutes
        plugin.logger.info(
            "[Hearts] Heart system on: start ${settings.hearts.starting}, ${settings.hearts.minimum}-${settings.hearts.maximum} hearts, " +
                "same-victim cooldown ${cooldownMinutes}m."
        )
        loaded.clear()
        for (player in Bukkit.getOnlinePlayers()) sync(player)
    }

    fun stop() {
        loaded.clear()
        limits = null
    }

    // ── Sessions ────────────────────────────────────────────

    /**
     * Brings [player] in line with where they are: in a Lifesteal world, load (or create, once)
     * their record and apply it; anywhere else, drop the session and put back the vanilla
     * maximum health if the heart system had set it. Join, respawn, world change and reload.
     */
    fun sync(player: Player) {
        if (!player.isOnline) return
        if (!hosted || !isLifesteal(player)) {
            loaded.remove(player.uniqueId)
            restoreVanilla(player)
            return
        }
        val limits = limits ?: return // Paused (invalid config): leave health exactly as the player has it.
        if (!active) return
        val record = try {
            store.ensure(player.uniqueId, limits.starting, System.currentTimeMillis())
        } catch (e: Exception) {
            loaded.remove(player.uniqueId)
            plugin.logger.severe("[Hearts] Could not load the hearts of ${player.name} (${player.uniqueId}); leaving their health as it is: ${e.message}")
            return
        }
        val stored = record.hearts
        if (stored == null) {
            loaded.remove(player.uniqueId)
            if (malformedLogged.add(player.uniqueId)) {
                plugin.logger.severe(
                    "[Hearts] The stored hearts of ${player.name} (${player.uniqueId}) are unreadable; their health is left as it is and " +
                        "no heart can be stolen from or by them until an admin runs /lifesteal hearts set ${player.name} <hearts>."
                )
            }
            return
        }
        val hearts = limits.clamp(stored)
        if (hearts != stored) {
            plugin.logger.warning(
                "[Hearts] ${player.name} has $stored stored hearts, outside ${limits.minimum}-${limits.maximum}; applying $hearts " +
                    "(the stored value is only replaced by their next heart change)."
            )
        }
        loaded[player.uniqueId] = hearts
        apply(player, hearts)
    }

    fun onQuit(player: Player) {
        loaded.remove(player.uniqueId)
        malformedLogged.remove(player.uniqueId)
    }

    /** Sets the max health base value to [hearts] × 2; modifiers (potions, items) stay on top. */
    private fun apply(player: Player, hearts: Int, heal: Boolean = false) {
        val attribute = player.getAttribute(Attribute.MAX_HEALTH) ?: return
        val base = HeartRules.healthFor(hearts)
        if (attribute.baseValue != base) attribute.baseValue = base
        player.persistentDataContainer.set(appliedKey, PersistentDataType.INTEGER, hearts)
        if (player.isDead) return
        val max = attribute.value
        if (heal) player.health = max
        else if (player.health > max) player.health = max
    }

    /** Only for players whose max health the heart system set (marked in their data). */
    private fun restoreVanilla(player: Player) {
        val pdc = player.persistentDataContainer
        if (!pdc.has(appliedKey, PersistentDataType.INTEGER)) return
        pdc.remove(appliedKey)
        val attribute = player.getAttribute(Attribute.MAX_HEALTH) ?: return
        attribute.baseValue = VANILLA_MAX_HEALTH
        if (!player.isDead && player.health > attribute.value) player.health = attribute.value
    }

    /** After respawning: reapply (or restore) and start at full health, like vanilla. */
    fun onPostRespawn(player: Player) {
        sync(player)
        val hearts = loaded[player.uniqueId] ?: return
        apply(player, hearts, heal = true)
    }

    // ── PvP ─────────────────────────────────────────────────

    fun onDeath(event: PlayerDeathEvent) {
        if (!active) return
        val victim = event.player
        val limits = limits ?: return
        val causing = event.damageSource.causingEntity as? Player
        val killer = causing?.takeIf { it.uniqueId != victim.uniqueId }
        val attribution = HeartRules.attribute(
            HeartRules.Death(
                victim = victim.uniqueId,
                victimInLifesteal = isLifesteal(victim),
                victimLoaded = loaded.containsKey(victim.uniqueId),
                causingPlayer = causing?.uniqueId,
                killerOnline = killer?.isOnline == true,
                killerInLifesteal = killer != null && isLifesteal(killer),
                killerLoaded = killer != null && loaded.containsKey(killer.uniqueId),
                killerIsNpc = killer != null && killer.hasMetadata("NPC"),
            )
        )
        if (attribution == HeartRules.Attribution.NOT_LOADED) {
            plugin.logger.warning("[Hearts] ${killer?.name} killed ${victim.name}, but one of them has no loaded heart record; no hearts changed.")
        }
        if (attribution != HeartRules.Attribution.PVP_KILL || killer == null) return

        val request = HeartStore.PvpRequest(
            dedupeKey = "pvp:$bootId:${victim.uniqueId}:${Bukkit.getCurrentTick()}",
            killer = killer.uniqueId,
            victim = victim.uniqueId,
            now = System.currentTimeMillis(),
            cooldownMillis = HeartRules.cooldownMillis(cooldownMinutes),
            limits = limits,
        )
        val result = try {
            store.transferPvp(request)
        } catch (e: Exception) {
            plugin.logger.severe("[Hearts] Heart transfer ${killer.name} <- ${victim.name} failed and was rolled back; no hearts changed: ${e.message}")
            send(killer, HeartMessages.TRANSFER_FAILED)
            send(victim, HeartMessages.TRANSFER_FAILED)
            return
        }
        when (result) {
            HeartStore.PvpResult.Duplicate -> return
            is HeartStore.PvpResult.Unavailable -> {
                plugin.logger.warning("[Hearts] ${killer.name} killed ${victim.name}, but no hearts changed: ${result.reason}.")
                return
            }
            is HeartStore.PvpResult.Done -> {
                if (result.decision.changesHearts) {
                    loaded[victim.uniqueId] = result.victimAfter
                    apply(victim, result.victimAfter)
                    if (killer.isOnline && loaded.containsKey(killer.uniqueId)) {
                        loaded[killer.uniqueId] = result.killerAfter
                        apply(killer, result.killerAfter)
                    }
                }
                notify(result, killer, victim, limits)
            }
        }
    }

    private fun notify(result: HeartStore.PvpResult.Done, killer: Player, victim: Player, limits: HeartRules.Limits) {
        val names = mapOf("{victim}" to victim.name, "{killer}" to killer.name, "{maximum}" to limits.maximum.toString())
        when (val decision = result.decision) {
            is HeartRules.Decision.Transfer -> {
                send(killer, HeartMessages.KILLER_STOLE, names + ("{hearts}" to result.killerAfter.toString()))
                send(victim, HeartMessages.VICTIM_LOST, names + ("{hearts}" to result.victimAfter.toString()))
            }
            is HeartRules.Decision.VictimLosesOnly -> {
                send(victim, HeartMessages.VICTIM_LOST, names + ("{hearts}" to result.victimAfter.toString()))
            }
            HeartRules.Decision.VictimAtMinimum -> {
                send(killer, HeartMessages.KILLER_VICTIM_LAST_HEART, names)
                send(victim, HeartMessages.VICTIM_LAST_HEART, names)
            }
            HeartRules.Decision.KillerAtMaximum -> {
                send(killer, HeartMessages.KILLER_AT_MAXIMUM, names)
                send(victim, HeartMessages.VICTIM_KILLER_AT_MAXIMUM, names)
            }
            is HeartRules.Decision.Cooldown -> {
                send(killer, HeartMessages.KILLER_COOLDOWN, names + ("{time}" to HeartRules.formatDuration(decision.remainingMillis)))
                send(victim, HeartMessages.VICTIM_COOLDOWN, names)
            }
        }
    }

    // ── Administration ──────────────────────────────────────

    /** Fresh from the database (works for offline players), or null with no record. */
    fun storedRecord(uuid: UUID): HeartStore.Record? = store.load(uuid)

    fun adminChange(uuid: UUID, op: HeartRules.AdminOp, amount: Int, actor: String, actorName: String): HeartStore.AdminResult {
        val limits = limits ?: return HeartStore.AdminResult.Invalid("Hearts are paused: lifesteal.yml is invalid.")
        val result = store.adminChange(uuid, op, amount, limits, actor, actorName, System.currentTimeMillis())
        if (result is HeartStore.AdminResult.Changed) {
            plugin.logger.info("[Hearts] $actorName: ${op.id} $amount for $uuid, ${result.before ?: "unreadable"} -> ${result.after} (transaction ${result.transactionId}).")
            malformedLogged.remove(uuid)
            val online = Bukkit.getPlayer(uuid)
            if (online != null && online.isOnline && isLifesteal(online)) {
                loaded[uuid] = result.after
                apply(online, result.after)
                send(online, HeartMessages.ADMIN_CHANGED_TARGET, mapOf("{hearts}" to result.after.toString()))
            }
        }
        return result
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun send(player: Player, template: String, values: Map<String, String> = emptyMap()) {
        if (!player.isOnline) return
        var component: Component = plugin.commsManager.parseLegacy(template)
        for ((key, value) in values) {
            component = component.replaceText(TextReplacementConfig.builder().matchLiteral(key).replacement(value).build())
        }
        plugin.commsManager.send(player, component)
    }

    companion object {
        /** On the player: the hearts the heart system last applied, so leaving Lifesteal can undo it. */
        val appliedKey = NamespacedKey("joshymc", "lifesteal_hearts_applied")

        /** A vanilla player's max health base value (10 hearts). */
        const val VANILLA_MAX_HEALTH = 20.0
    }
}
