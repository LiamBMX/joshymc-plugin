package com.liam.joshymc.link

import org.bukkit.Bukkit
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Adds or removes one LuckPerms parent group through the LuckPerms API, by UUID (issue #1213).
 *
 * JoshyMC has no compile-time LuckPerms dependency, so the API is reached through the
 * service LuckPerms registers with Bukkit and called reflectively. Only one global,
 * permanent `group.<name>` inheritance node is ever added or removed; every other parent
 * group, the primary group and all permission nodes are left alone. Works for offline
 * players: `modifyUser` loads the user, applies the change and saves it.
 */
class LuckPermsGroups private constructor(private val api: Any, apiClass: Class<*>) {

    enum class Change { ADDED, REMOVED, UNCHANGED }

    private val loader: ClassLoader = apiClass.classLoader
    private fun type(name: String): Class<*> = Class.forName(name, true, loader)

    private val nodeClass = type("net.luckperms.api.node.Node")
    private val userManager = apiClass.getMethod("getUserManager").invoke(api)
    private val groupManager = apiClass.getMethod("getGroupManager").invoke(api)
    private val userManagerClass = type("net.luckperms.api.model.user.UserManager")
    private val groupManagerClass = type("net.luckperms.api.model.group.GroupManager")
    private val modifyUser = userManagerClass.getMethod("modifyUser", UUID::class.java, Consumer::class.java)
    private val getUser = userManagerClass.getMethod("getUser", UUID::class.java)
    private val loadGroup = groupManagerClass.getMethod("loadGroup", String::class.java)
    private val createAndLoadGroup = groupManagerClass.getMethod("createAndLoadGroup", String::class.java)
    private val data = type("net.luckperms.api.model.PermissionHolder").getMethod("data")
    private val nodeMapClass = type("net.luckperms.api.model.data.NodeMap")
    private val add = nodeMapClass.getMethod("add", nodeClass)
    private val remove = nodeMapClass.getMethod("remove", nodeClass)
    private val predicateClass = type("net.luckperms.api.node.NodeEqualityPredicate")
    private val contains = nodeMapClass.getMethod("contains", nodeClass, predicateClass)
    private val exact = predicateClass.getField("EXACT").get(null)
    private val asBoolean = type("net.luckperms.api.util.Tristate").getMethod("asBoolean")
    private val wasSuccessful = type("net.luckperms.api.model.data.DataMutateResult").getMethod("wasSuccessful")
    private val inheritanceBuilder = type("net.luckperms.api.node.types.InheritanceNode").getMethod("builder", String::class.java)
    private val build = type("net.luckperms.api.node.NodeBuilder").getMethod("build")

    /** The global, permanent `group.<name>` node, the only node this class touches. */
    private fun groupNode(group: String): Any = build.invoke(inheritanceBuilder.invoke(null, group))

    /**
     * True/false when LuckPerms has [uuid] loaded (online players), null when it doesn't.
     * Reads memory only, so it's safe on the main thread.
     */
    fun hasGroup(uuid: UUID, group: String): Boolean? {
        val user = getUser.invoke(userManager, uuid) ?: return null
        return asBoolean.invoke(contains.invoke(data.invoke(user), groupNode(group), exact)) as Boolean
    }

    /**
     * Makes [uuid] have (or not have) [group] as a parent and saves the user. Completes
     * once LuckPerms has saved, or exceptionally when loading/saving failed.
     */
    fun setGroup(uuid: UUID, group: String, present: Boolean): CompletableFuture<Change> {
        val node = groupNode(group)
        var change = Change.UNCHANGED
        val action = Consumer<Any> { user ->
            val nodes = data.invoke(user)
            val result = if (present) add.invoke(nodes, node) else remove.invoke(nodes, node)
            if (wasSuccessful.invoke(result) as Boolean) change = if (present) Change.ADDED else Change.REMOVED
        }
        @Suppress("UNCHECKED_CAST")
        val saved = modifyUser.invoke(userManager, uuid, action) as CompletableFuture<Void>
        return saved.thenApply { change }
    }

    /** Loads [group], creating an empty one (no weight, prefix or parents) if it's missing. Completes with true when created. */
    fun ensureGroup(group: String): CompletableFuture<Boolean> {
        @Suppress("UNCHECKED_CAST")
        val loaded = loadGroup.invoke(groupManager, group) as CompletableFuture<Optional<*>>
        return loaded.thenCompose { existing ->
            if (existing.isPresent) CompletableFuture.completedFuture(false)
            else (createAndLoadGroup.invoke(groupManager, group) as CompletableFuture<*>).thenApply { true }
        }
    }

    companion object {
        private const val API_CLASS = "net.luckperms.api.LuckPerms"

        @Volatile private var cached: LuckPermsGroups? = null

        /**
         * The bridge to the running LuckPerms, or null when LuckPerms isn't enabled. Throws
         * if LuckPerms is there but its API doesn't match (logged by the caller).
         */
        fun current(): LuckPermsGroups? {
            if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return null
            val service = Bukkit.getServicesManager().knownServices.firstOrNull { it.name == API_CLASS } ?: return null
            val provider = Bukkit.getServicesManager().getRegistration(service)?.provider ?: return null
            cached?.let { if (it.api === provider) return it }
            return LuckPermsGroups(provider, service).also { cached = it }
        }
    }
}
