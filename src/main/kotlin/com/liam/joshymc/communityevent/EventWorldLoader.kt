package com.liam.joshymc.communityevent

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.WorldCreator
import java.io.File

/**
 * Loads the configured event world (issue #1245) so hosts never have to import it by hand.
 *
 * Only ever loads a world that already exists: a world that's already loaded (by the server or a
 * world plugin such as Multiverse) is reused as-is, an unloaded one is loaded from its folder with
 * the plain `WorldCreator(name)` that `/world import` and the pvp world use, so level.dat keeps its
 * seed, gamerules, difficulty and spawn. A missing world is reported, never created.
 * Loading a world loads no extra chunks beyond what the server keeps loaded for any world.
 */
object EventWorldLoader {

    sealed interface Result {
        /** [reused] is true when the world was already loaded before JoshyMC looked. */
        data class Loaded(val world: World, val reused: Boolean) : Result
        data class Unavailable(val reason: String) : Result
    }

    fun load(name: String): Result {
        Bukkit.getWorld(name)?.let { return Result.Loaded(it, reused = true) }

        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) {
            return Result.Unavailable("'$name' isn't a valid world folder name")
        }
        val folder = File(Bukkit.getWorldContainer(), name)
        if (!folder.isDirectory) {
            return Result.Unavailable("no world folder '$name' in ${Bukkit.getWorldContainer().absolutePath}")
        }
        if (!File(folder, "level.dat").isFile) {
            return Result.Unavailable("folder '${folder.path}' has no level.dat, so it isn't a world")
        }

        val world = try {
            WorldCreator(name).createWorld()
        } catch (e: Exception) {
            return Result.Unavailable("the server refused to load it: ${e.message ?: e.javaClass.simpleName}")
        }
        return if (world != null) Result.Loaded(world, reused = false)
        else Result.Unavailable("the server refused to load it (see the errors above)")
    }
}
