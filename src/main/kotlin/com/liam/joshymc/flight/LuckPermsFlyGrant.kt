package com.liam.joshymc.flight

import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Writes Fly Voucher flight into LuckPerms (issue #1408): only the player's own global
 * `joshymc.fly` nodes, by UUID. The plan comes from the Bukkit-free [FlyVoucherGrants].
 *
 * No compile-time LuckPerms dependency, same as [LuckPermsFlight] and
 * `link/LuckPermsGroups`: the API service is called reflectively. Writes go through
 * `UserManager.modifyUser`, which loads the user, applies the change and saves it; the
 * future completes only once LuckPerms has saved. Ranks, parent groups and every other node
 * are never touched, and no wildcard is ever written.
 */
class LuckPermsFlyGrant private constructor(private val api: Any, apiClass: Class<*>) {

    /** What a write did. [plan] is decided inside LuckPerms' modification, against the user's nodes at that moment. */
    data class Outcome(val plan: FlyVoucherGrants.Plan, val written: Boolean)

    private val loader: ClassLoader = apiClass.classLoader
    private fun type(name: String): Class<*> = Class.forName(name, true, loader)

    private val userManager = apiClass.getMethod("getUserManager").invoke(api)
    private val userManagerClass = type("net.luckperms.api.model.user.UserManager")
    private val modifyUser = userManagerClass.getMethod("modifyUser", UUID::class.java, Consumer::class.java)
    private val getUser = userManagerClass.getMethod("getUser", UUID::class.java)
    private val data = type("net.luckperms.api.model.PermissionHolder").getMethod("data")
    private val nodeClass = type("net.luckperms.api.node.Node")
    private val nodeMapClass = type("net.luckperms.api.model.data.NodeMap")
    private val toCollection = nodeMapClass.getMethod("toCollection")
    private val add = nodeMapClass.getMethod("add", nodeClass)
    private val remove = nodeMapClass.getMethod("remove", nodeClass)
    private val wasSuccessful = type("net.luckperms.api.model.data.DataMutateResult").getMethod("wasSuccessful")
    private val getKey = nodeClass.getMethod("getKey")
    private val getValue = nodeClass.getMethod("getValue")
    private val getExpiry = nodeClass.getMethod("getExpiry")
    private val getContexts = nodeClass.getMethod("getContexts")
    private val contextIsEmpty = type("net.luckperms.api.context.ContextSet").getMethod("isEmpty")
    private val builder = nodeClass.getMethod("builder", String::class.java)
    private val builderClass = type("net.luckperms.api.node.NodeBuilder")
    private val builderValue = builderClass.getMethod("value", java.lang.Boolean.TYPE)
    private val builderExpiry = builderClass.getMethod("expiry", java.lang.Long.TYPE)
    private val build = builderClass.getMethod("build")

    /** The holder's own global `joshymc.fly` nodes, as LuckPerms objects paired with their [FlyVoucherGrants.FlyNode]. */
    private fun flyNodes(user: Any): List<Pair<Any, FlyVoucherGrants.FlyNode>> {
        val nodes = toCollection.invoke(data.invoke(user)) as Collection<*>
        return nodes.filterNotNull().mapNotNull { node ->
            if (!(getKey.invoke(node) as String).equals(FlyVoucherGrants.NODE, ignoreCase = true)) return@mapNotNull null
            if (!(contextIsEmpty.invoke(getContexts.invoke(node)) as Boolean)) return@mapNotNull null
            val expiry = getExpiry.invoke(node) as java.time.Instant?
            node to FlyVoucherGrants.FlyNode(getValue.invoke(node) as Boolean, expiry?.epochSecond)
        }
    }

    /**
     * The player's own global `joshymc.fly` nodes, or null when LuckPerms hasn't loaded them
     * (online players are always loaded). Reads memory only, so it's safe on the main thread.
     */
    fun loadedNodes(uuid: UUID): List<FlyVoucherGrants.FlyNode>? {
        val user = getUser.invoke(userManager, uuid) ?: return null
        return flyNodes(user).map { it.second }
    }

    /** Adds [addSeconds] of flight on top of what's left (one voucher). */
    fun extend(uuid: UUID, addSeconds: Long, nowSec: Long, carryOverUntil: Long? = null): CompletableFuture<Outcome> =
        modify(uuid) { nodes -> FlyVoucherGrants.extend(nodes, addSeconds, nowSec, carryOverUntil) }

    /** Makes sure flight lasts until at least [untilSec] (moving an old JoshyMC balance in). */
    fun ensureUntil(uuid: UUID, untilSec: Long, nowSec: Long): CompletableFuture<Outcome> =
        modify(uuid) { nodes -> FlyVoucherGrants.until(nodes, untilSec, nowSec) }

    private fun modify(uuid: UUID, decide: (List<FlyVoucherGrants.FlyNode>) -> FlyVoucherGrants.Plan): CompletableFuture<Outcome> {
        var outcome: Outcome? = null
        val action = Consumer<Any> { user ->
            val existing = flyNodes(user)
            val plan = decide(existing.map { it.second })
            if (plan !is FlyVoucherGrants.Plan.Grant) {
                outcome = Outcome(plan, written = false)
                return@Consumer
            }
            val map = data.invoke(user)
            for ((node, fly) in existing) {
                if (FlyVoucherGrants.replaced(fly)) remove.invoke(map, node)
            }
            val node = build.invoke(builderExpiry.invoke(builderValue.invoke(builder.invoke(null, FlyVoucherGrants.NODE), true), plan.expiresAt))
            val added = wasSuccessful.invoke(add.invoke(map, node)) as Boolean
            check(added) { "LuckPerms refused to add the temporary ${FlyVoucherGrants.NODE} node" }
            outcome = Outcome(plan, written = true)
        }
        @Suppress("UNCHECKED_CAST")
        val saved = modifyUser.invoke(userManager, uuid, action) as CompletableFuture<Void>
        return saved.thenApply { outcome ?: error("LuckPerms didn't run the flight change") }
    }

    companion object {
        private const val API_CLASS = "net.luckperms.api.LuckPerms"

        @Volatile private var cached: LuckPermsFlyGrant? = null

        /** The writer for the running LuckPerms, or null when LuckPerms isn't enabled. Throws if its API doesn't match. */
        fun current(): LuckPermsFlyGrant? {
            if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return null
            val service = Bukkit.getServicesManager().knownServices.firstOrNull { it.name == API_CLASS } ?: return null
            val provider = Bukkit.getServicesManager().getRegistration(service)?.provider ?: return null
            cached?.let { if (it.api === provider) return it }
            return LuckPermsFlyGrant(provider, service).also { cached = it }
        }
    }
}
