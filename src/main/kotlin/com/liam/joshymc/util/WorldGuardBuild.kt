package com.liam.joshymc.util

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.logging.Logger

/**
 * Asks WorldGuard whether a player may build at a location (issue #1286), for actions that
 * fire no block event WorldGuard would see, such as taking down furniture (an entity).
 *
 * JoshyMC has no compile-time WorldGuard dependency, so its API is reached reflectively:
 * `WorldGuard.getInstance().platform.regionContainer.createQuery().testBuild(...)`, with
 * WorldGuard's own region bypass honoured.
 */
object WorldGuardBuild {

    enum class Result {
        ALLOWED,
        DENIED,
        /** WorldGuard isn't installed or enabled. */
        ABSENT,
        /** WorldGuard is installed but its API couldn't be reached (logged once). */
        UNKNOWN,
    }

    private class Api(private val loader: ClassLoader) {
        private fun type(name: String): Class<*> = Class.forName(name, true, loader)

        private val adapter = type("com.sk89q.worldedit.bukkit.BukkitAdapter")
        private val adaptLocation: Method = adapter.getMethod("adapt", Location::class.java)
        private val adaptWorld: Method = adapter.getMethod("adapt", World::class.java)
        private val plugin: Any = type("com.sk89q.worldguard.bukkit.WorldGuardPlugin").getMethod("inst").invoke(null)
        private val wrapPlayer: Method = plugin.javaClass.getMethod("wrapPlayer", Player::class.java)
        private val platformClass = type("com.sk89q.worldguard.internal.platform.WorldGuardPlatform")
        private val platform: Any = type("com.sk89q.worldguard.WorldGuard").let { wg ->
            wg.getMethod("getPlatform").invoke(wg.getMethod("getInstance").invoke(null))
        }
        private val regionContainer: Any = platformClass.getMethod("getRegionContainer").invoke(platform)
        private val createQuery: Method =
            type("com.sk89q.worldguard.protection.regions.RegionContainer").getMethod("createQuery")
        private val sessionManager: Any = platformClass.getMethod("getSessionManager").invoke(platform)
        private val hasBypass: Method = type("com.sk89q.worldguard.session.SessionManager").getMethod(
            "hasBypass", type("com.sk89q.worldguard.LocalPlayer"), type("com.sk89q.worldedit.world.World"))
        private val stateFlag = type("com.sk89q.worldguard.protection.flags.StateFlag")
        private val noFlags: Any = java.lang.reflect.Array.newInstance(stateFlag, 0)
        private val testBuild: Method = type("com.sk89q.worldguard.protection.regions.RegionQuery").methods.first {
            it.name == "testBuild" && it.parameterCount == 3 &&
                it.parameterTypes[0].name == "com.sk89q.worldedit.util.Location" &&
                it.parameterTypes[2].isArray && it.parameterTypes[2].componentType == stateFlag
        }

        fun canBuild(player: Player, location: Location): Boolean {
            val world = location.world ?: return true
            val local = wrapPlayer.invoke(plugin, player)
            if (hasBypass.invoke(sessionManager, local, adaptWorld.invoke(null, world)) as Boolean) return true
            val query = createQuery.invoke(regionContainer)
            return testBuild.invoke(query, adaptLocation.invoke(null, location), local, noFlags) as Boolean
        }
    }

    private var api: Api? = null
    private var failed = false

    fun canBuild(player: Player, location: Location, logger: Logger): Result {
        val worldGuard = Bukkit.getPluginManager().getPlugin("WorldGuard")
        if (worldGuard == null || !worldGuard.isEnabled) return Result.ABSENT
        if (failed) return Result.UNKNOWN
        return try {
            val current = api ?: Api(worldGuard.javaClass.classLoader).also { api = it }
            if (current.canBuild(player, location)) Result.ALLOWED else Result.DENIED
        } catch (e: Exception) {
            failed = true
            logger.warning(
                "Couldn't reach the WorldGuard API to check build rights " +
                    "(${e.javaClass.simpleName}: ${e.cause?.message ?: e.message}); " +
                    "only owners and admins can take down furniture until the next restart"
            )
            Result.UNKNOWN
        }
    }
}
