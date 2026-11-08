package com.liam.joshymc.milestone

import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Remembers which blocks players placed (or generators made), so Milestones never count them as
 * natural (issue #1420). Markers live in each chunk's own persistent data (an int array of
 * [PlacedBlockCodec] positions), so they survive restarts, need no database, and vanish with the
 * chunk: a regenerated resource world starts with no markers and its fresh ores count normally.
 *
 * Only blocks some milestone counts are ever marked ([tracked]), which keeps the data small.
 * Loaded chunks keep a decoded copy in memory, dropped when the chunk unloads (bounded by the
 * loaded chunks). Main thread only.
 */
class PlacedBlockTracker(plugin: Plugin, keyName: String = "milestone_placed") {

    /** Issue #1426: Lifesteal Team XP keeps its own markers under another key ([keyName]). */
    /** Issue #1425: the Lifesteal quests keep their own markers (`lifesteal_quest_placed`), so the two never share a cache. */
    /** [keyName]: each user keeps its own markers, so they never share a cache (Lifesteal quests use `lifesteal_quest_placed`, issue #1425; Lifesteal profiles `lifesteal_profile_placed`, issue #1429). */
    private val key = NamespacedKey(plugin, keyName)

    private data class ChunkId(val world: UUID, val x: Int, val z: Int)

    private val cache = HashMap<ChunkId, LinkedHashSet<Int>>()

    /** Material names whose placement is remembered. */
    @Volatile var tracked: Set<String> = emptySet()

    private fun id(chunk: Chunk) = ChunkId(chunk.world.uid, chunk.x, chunk.z)

    private fun markers(chunk: Chunk): LinkedHashSet<Int> = cache.getOrPut(id(chunk)) {
        val stored = chunk.persistentDataContainer.get(key, PersistentDataType.INTEGER_ARRAY)
        LinkedHashSet<Int>().apply { stored?.forEach { add(it) } }
    }

    private fun write(chunk: Chunk, set: LinkedHashSet<Int>) {
        val pdc = chunk.persistentDataContainer
        if (set.isEmpty()) pdc.remove(key) else pdc.set(key, PersistentDataType.INTEGER_ARRAY, set.toIntArray())
    }

    private fun pack(block: Block) = PlacedBlockCodec.pack(block.x, block.y, block.z)

    fun isPlaced(block: Block): Boolean = pack(block) in markers(block.chunk)

    fun mark(block: Block) = mark(block, block.type.name)

    /** Marks [block] when [materialName] is tracked. */
    fun mark(block: Block, materialName: String) {
        if (materialName !in tracked) return
        val chunk = block.chunk
        val set = markers(chunk)
        if (!set.add(pack(block))) return
        while (set.size > PlacedBlockCodec.MAX_PER_CHUNK) {
            val oldest = set.iterator()
            oldest.next()
            oldest.remove()
        }
        write(chunk, set)
    }

    fun unmark(block: Block) {
        val chunk = block.chunk
        val set = markers(chunk)
        if (set.remove(pack(block))) write(chunk, set)
    }

    fun forget(chunk: Chunk) {
        cache.remove(id(chunk))
    }

    fun forgetWorld(world: UUID) {
        cache.keys.removeIf { it.world == world }
    }

    fun clear() = cache.clear()
}
