package com.liam.joshymc.flight

import org.bukkit.Bukkit
import java.time.Instant
import java.util.UUID

/**
 * Reads, never writes, where a player's fly node comes from in LuckPerms (issue #1303).
 *
 * No compile-time LuckPerms dependency, same as `link/LuckPermsGroups`: the API service is
 * called reflectively. Only loaded users and groups are read (memory only, main-thread safe).
 */
class LuckPermsFlight private constructor(private val api: Any, apiClass: Class<*>) {

    private val loader: ClassLoader = apiClass.classLoader
    private fun type(name: String): Class<*> = Class.forName(name, true, loader)

    private val userManager = apiClass.getMethod("getUserManager").invoke(api)
    private val groupManager = apiClass.getMethod("getGroupManager").invoke(api)
    private val getUser = type("net.luckperms.api.model.user.UserManager").getMethod("getUser", UUID::class.java)
    private val getGroup = type("net.luckperms.api.model.group.GroupManager").getMethod("getGroup", String::class.java)
    private val getNodes = type("net.luckperms.api.model.PermissionHolder").getMethod("getNodes")
    private val nodeClass = type("net.luckperms.api.node.Node")
    private val getKey = nodeClass.getMethod("getKey")
    private val getValue = nodeClass.getMethod("getValue")
    private val getExpiry = nodeClass.getMethod("getExpiry")
    private val hasExpired = nodeClass.getMethod("hasExpired")

    /**
     * The expiry of every path from [uuid] to a granted fly node, through its own nodes and
     * its parent groups (a path expires with its earliest timed link; null = never). Null when
     * LuckPerms hasn't loaded the user.
     */
    fun flyGrants(uuid: UUID): List<Instant?>? {
        val user = getUser.invoke(userManager, uuid) ?: return null
        val grants = mutableListOf<Instant?>()
        walk(user, null, mutableListOf(), grants)
        return grants
    }

    private fun walk(holder: Any, expiry: Instant?, path: MutableList<String>, out: MutableList<Instant?>) {
        if (path.size > MAX_DEPTH) return
        for (node in getNodes.invoke(holder) as Collection<*>) {
            if (node == null || hasExpired.invoke(node) as Boolean || !(getValue.invoke(node) as Boolean)) continue
            val key = (getKey.invoke(node) as String).lowercase()
            val until = FlightTime.earlier(expiry, getExpiry.invoke(node) as Instant?)
            if (key in FlightTime.FLY_NODES) {
                out += until
            } else if (key.startsWith(GROUP_PREFIX)) {
                val name = key.removePrefix(GROUP_PREFIX)
                if (name in path) continue
                val group = getGroup.invoke(groupManager, name) ?: continue
                path += name
                walk(group, until, path, out)
                path.removeAt(path.size - 1)
            }
        }
    }

    companion object {
        private const val API_CLASS = "net.luckperms.api.LuckPerms"
        private const val GROUP_PREFIX = "group."
        private const val MAX_DEPTH = 16

        @Volatile private var cached: LuckPermsFlight? = null

        /** The reader for the running LuckPerms, or null when LuckPerms isn't enabled. */
        fun current(): LuckPermsFlight? {
            if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return null
            val service = Bukkit.getServicesManager().knownServices.firstOrNull { it.name == API_CLASS } ?: return null
            val provider = Bukkit.getServicesManager().getRegistration(service)?.provider ?: return null
            cached?.let { if (it.api === provider) return it }
            return LuckPermsFlight(provider, service).also { cached = it }
        }
    }
}
