package com.liam.joshymc.link

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Keeps the LuckPerms Discord verification group (default `discord`) in step with JoshyMC's
 * own Discord links (issue #1213). [LinkSessions] is the only authority: the group is
 * added when a player is linked and removed when they aren't, never the other way round.
 *
 * Every sync reads the link state when it runs, not when it was requested, and only one
 * sync per player is in flight; a request that arrives meanwhile reruns it afterwards. So
 * a slow add can't land after a later unlink: the final group always matches the latest link.
 *
 * Triggers: a confirmed link (and the player who lost that Discord account), /unlink,
 * joining (only when the group doesn't match), and online players on startup/reload.
 * Linking only happens in-game, and there's no Discord-side unlink, so nothing else changes
 * a link.
 */
class DiscordGroupSync(private val plugin: Joshymc, private val isLinked: (UUID) -> Boolean) {

    @Volatile var enabled = true
        private set
    @Volatile var group = DEFAULT_GROUP
        private set

    private class Running(var rerun: Boolean = false, var notify: Boolean = false)

    private val lock = Any()
    private val running = HashMap<UUID, Running>()
    @Volatile private var warnedUnavailable = false

    fun reload() {
        val section = plugin.config.getConfigurationSection("discord.linking.verified-group")
        enabled = section?.getBoolean("enabled", true) ?: true
        group = section?.getString("group")?.trim()?.lowercase()?.takeIf { GROUP_NAME.matches(it) } ?: run {
            if (section?.getString("group") != null) {
                plugin.logger.warning("[Link] discord.linking.verified-group.group is not a valid LuckPerms group name; using '$DEFAULT_GROUP'.")
            }
            DEFAULT_GROUP
        }
        warnedUnavailable = false
        // After every plugin has enabled, so LuckPerms is up whatever the load order.
        Bukkit.getScheduler().runTask(plugin, Runnable { checkSetup() })
    }

    /** Startup/reload: make sure the group exists, then repair everyone online. */
    private fun checkSetup() {
        if (!enabled) return
        val luckPerms = luckPerms() ?: return
        val group = group
        luckPerms.ensureGroup(group).whenComplete { created, error ->
            when {
                error != null -> plugin.logger.warning("[Link] Could not load LuckPerms group '$group': ${error.message}")
                created -> plugin.logger.info("[Link] Created the empty LuckPerms group '$group' for Discord-linked players; give it permissions/prefix in LuckPerms as you like.")
                else -> plugin.logger.info("[Link] Discord-linked players get the LuckPerms group '$group'.")
            }
        }
        for (player in Bukkit.getOnlinePlayers()) reconcile(player.uniqueId)
    }

    /** Join/startup repair: syncs only when LuckPerms' loaded data disagrees with the link (or isn't loaded). */
    fun reconcile(uuid: UUID) {
        if (!enabled) return
        val luckPerms = luckPerms() ?: return
        val has = try {
            luckPerms.hasGroup(uuid, group)
        } catch (e: Exception) {
            null
        }
        if (has == null || has != isLinked(uuid)) sync(uuid)
    }

    /**
     * Brings [uuid]'s group in line with its current link. With [notify], the player (if
     * online) is told once LuckPerms has saved the change, never before.
     */
    fun sync(uuid: UUID, notify: Boolean = false) {
        if (!enabled) return
        val start = synchronized(lock) {
            val current = running[uuid]
            if (current != null) {
                current.rerun = true
                current.notify = current.notify || notify
                false
            } else {
                running[uuid] = Running(notify = notify)
                true
            }
        }
        if (start) run(uuid)
    }

    private fun run(uuid: UUID) {
        val group = group
        val luckPerms = luckPerms()
        if (luckPerms == null) {
            finish(uuid)
            return
        }
        val linked = isLinked(uuid)
        val future = try {
            luckPerms.setGroup(uuid, group, linked)
        } catch (e: Exception) {
            plugin.logger.warning("[Link] LuckPerms API call failed for $uuid ($group): ${e.message}")
            finish(uuid)
            return
        }
        future.whenComplete { change, error ->
            if (error != null) {
                plugin.logger.warning("[Link] LuckPerms could not ${if (linked) "add" else "remove"} '$group' for $uuid: ${error.message}. It will be retried when they next join.")
            } else if (change != LuckPermsGroups.Change.UNCHANGED) {
                plugin.logger.info("[Link] ${if (linked) "Added" else "Removed"} LuckPerms group '$group' ${if (linked) "to" else "from"} $uuid.")
            }
            val notify = synchronized(lock) {
                val state = running[uuid]
                if (state != null && state.rerun) {
                    state.rerun = false
                    null
                } else {
                    running.remove(uuid)
                    state?.notify ?: false
                }
            }
            when {
                // The link changed while this was saving: apply the latest state instead.
                notify == null -> run(uuid)
                notify && error == null -> tell(uuid, linked)
            }
        }
    }

    private fun finish(uuid: UUID) = synchronized(lock) { running.remove(uuid) }

    private fun tell(uuid: UUID, linked: Boolean) {
        if (!plugin.isEnabled) return
        Bukkit.getScheduler().runTask(plugin, Runnable {
            // Only if nothing changed since; a newer sync reports its own result.
            if (isLinked(uuid) != linked) return@Runnable
            val player = Bukkit.getPlayer(uuid) ?: return@Runnable
            val message = if (linked) "You've received the Discord verification rank." else "Your Discord verification rank has been removed."
            plugin.commsManager.send(player, Component.text(message, NamedTextColor.GREEN))
        })
    }

    private fun luckPerms(): LuckPermsGroups? {
        val bridge = try {
            LuckPermsGroups.current()
        } catch (e: Exception) {
            warnOnce("[Link] LuckPerms is installed but its API could not be used (${e.javaClass.simpleName}: ${e.message}); the Discord verification group is not being synced.")
            return null
        }
        if (bridge == null) warnOnce("[Link] LuckPerms is not enabled; the Discord verification group is not being synced. Links themselves still work.")
        return bridge
    }

    private fun warnOnce(message: String) {
        if (warnedUnavailable) return
        warnedUnavailable = true
        plugin.logger.warning(message)
    }

    companion object {
        const val DEFAULT_GROUP = "discord"
        private val GROUP_NAME = Regex("[a-z0-9_\\-]{1,36}")
    }
}
