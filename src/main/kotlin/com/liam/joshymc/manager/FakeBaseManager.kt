package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Chest
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.Base64
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Fake Base Selection & /spawnstash — a small cuboid copy/paste tool for staff.
 * Only one template exists at a time; capture happens automatically once both
 * wand positions are set, and /spawnstash pastes it at the player's location.
 *
 * Not copied by design: entities, players, mobs, dropped items.
 * Copied where practical: block material + BlockData, container contents,
 * sign text (both sides). Still missing: banner patterns, mob-head owner
 * profiles, command block commands, and other exotic tile-entity state.
 */
class FakeBaseManager(private val plugin: Joshymc) : Listener {

    private data class CapturedBlock(val x: Int, val y: Int, val z: Int, val data: String)
    private data class CapturedItem(val x: Int, val y: Int, val z: Int, val slot: Int, val itemData: String)
    private data class CapturedSignLine(val x: Int, val y: Int, val z: Int, val lineIndex: Int, val text: String)
    private data class LoadedTemplate(
        val sizeX: Int, val sizeY: Int, val sizeZ: Int,
        val blocks: List<CapturedBlock>,
        val items: List<CapturedItem>,
        val signLines: List<CapturedSignLine>
    )

    sealed class PasteResult {
        object Success : PasteResult()
        object NoTemplate : PasteResult()
        data class Failure(val reason: String) : PasteResult()
    }

    private val wandKey = NamespacedKey(plugin, "fakebase_wand")
    private val selections = mutableMapOf<UUID, Pair<Location?, Location?>>()

    private val gson = GsonComponentSerializer.gson()

    companion object {
        private const val MAX_VOLUME = 50_000
    }

    // ── Lifecycle ──────────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS fake_base_meta (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                size_x INTEGER NOT NULL,
                size_y INTEGER NOT NULL,
                size_z INTEGER NOT NULL,
                block_count INTEGER NOT NULL,
                created_by TEXT,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS fake_base_blocks (
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                block_data TEXT NOT NULL,
                PRIMARY KEY (x, y, z)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS fake_base_items (
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                slot INTEGER NOT NULL,
                item_data TEXT NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS fake_base_signs (
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                line_index INTEGER NOT NULL,
                line_text TEXT NOT NULL
            )
        """.trimIndent())
    }

    // ── Wand ───────────────────────────────────────────────

    fun giveWand(player: Player) {
        val wand = ItemStack(Material.STICK)
        wand.editMeta { meta ->
            meta.displayName(
                Component.text("Fake Base Wand", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.text("Left-click: Set position 1", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click: Set position 2", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ))
            meta.persistentDataContainer.set(wandKey, PersistentDataType.BYTE, 1.toByte())
        }
        player.inventory.addItem(wand)
    }

    fun getSelection(player: Player): Pair<Location?, Location?> {
        return selections[player.uniqueId] ?: Pair(null, null)
    }

    @EventHandler
    fun onWandInteract(event: PlayerInteractEvent) {
        // A right/left click with an item can fire once per hand — only handle the main hand.
        if (event.hand != EquipmentSlot.HAND) return
        val item = event.item ?: return
        if (item.itemMeta?.persistentDataContainer?.has(wandKey, PersistentDataType.BYTE) != true) return

        val player = event.player
        if (!player.hasPermission("joshymc.fakebase.create")) return
        if (event.action != Action.LEFT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_BLOCK) return

        val block = event.clickedBlock ?: return
        event.isCancelled = true

        val uuid = player.uniqueId
        val current = selections.getOrPut(uuid) { Pair(null, null) }

        val updated = if (event.action == Action.LEFT_CLICK_BLOCK) {
            plugin.commsManager.send(player, Component.text("Position 1 selected.", NamedTextColor.GREEN))
            Pair(block.location, current.second)
        } else {
            plugin.commsManager.send(player, Component.text("Position 2 selected.", NamedTextColor.GREEN))
            Pair(current.first, block.location)
        }
        selections[uuid] = updated

        val pos1 = updated.first
        val pos2 = updated.second
        if (pos1 != null && pos2 != null) {
            captureAndSave(player, pos1, pos2)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        selections.remove(event.player.uniqueId)
    }

    // ── Capture ────────────────────────────────────────────

    private fun captureAndSave(player: Player, pos1: Location, pos2: Location) {
        val world = pos1.world
        if (world == null || world != pos2.world) {
            plugin.commsManager.send(player, Component.text("Both positions must be in the same world.", NamedTextColor.RED))
            return
        }

        val minX = min(pos1.blockX, pos2.blockX)
        val minY = min(pos1.blockY, pos2.blockY)
        val minZ = min(pos1.blockZ, pos2.blockZ)
        val maxX = max(pos1.blockX, pos2.blockX)
        val maxY = max(pos1.blockY, pos2.blockY)
        val maxZ = max(pos1.blockZ, pos2.blockZ)

        val sizeX = maxX - minX + 1
        val sizeY = maxY - minY + 1
        val sizeZ = maxZ - minZ + 1
        val volume = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()
        if (volume > MAX_VOLUME) {
            plugin.commsManager.send(player, Component.text("Selection too large (max $MAX_VOLUME blocks).", NamedTextColor.RED))
            return
        }

        val blocks = mutableListOf<CapturedBlock>()
        val items = mutableListOf<CapturedItem>()
        val signLines = mutableListOf<CapturedSignLine>()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val block = world.getBlockAt(x, y, z)
                    val relX = x - minX
                    val relY = y - minY
                    val relZ = z - minZ
                    blocks.add(CapturedBlock(relX, relY, relZ, block.blockData.asString))

                    val state = block.state
                    val inventory = when {
                        state is Chest -> state.blockInventory
                        state is Container -> state.inventory
                        else -> null
                    }
                    inventory?.contents?.forEachIndexed { slot, stack ->
                        if (stack != null && stack.type != Material.AIR) {
                            items.add(CapturedItem(relX, relY, relZ, slot, serializeItem(stack)))
                        }
                    }

                    if (state is Sign) {
                        for (side in Side.values()) {
                            val signSide = state.getSide(side)
                            val offset = if (side == Side.FRONT) 0 else 4
                            for (i in 0 until 4) {
                                signLines.add(CapturedSignLine(relX, relY, relZ, offset + i, gson.serialize(signSide.line(i))))
                            }
                        }
                    }
                }
            }
        }

        plugin.databaseManager.transaction {
            plugin.databaseManager.execute("DELETE FROM fake_base_meta")
            plugin.databaseManager.execute("DELETE FROM fake_base_blocks")
            plugin.databaseManager.execute("DELETE FROM fake_base_items")
            plugin.databaseManager.execute("DELETE FROM fake_base_signs")

            plugin.databaseManager.execute(
                "INSERT INTO fake_base_meta (id, size_x, size_y, size_z, block_count, created_by, updated_at) VALUES (1, ?, ?, ?, ?, ?, ?)",
                sizeX, sizeY, sizeZ, blocks.size, player.name, System.currentTimeMillis()
            )
            for (b in blocks) {
                plugin.databaseManager.execute(
                    "INSERT INTO fake_base_blocks (x, y, z, block_data) VALUES (?, ?, ?, ?)",
                    b.x, b.y, b.z, b.data
                )
            }
            for (i in items) {
                plugin.databaseManager.execute(
                    "INSERT INTO fake_base_items (x, y, z, slot, item_data) VALUES (?, ?, ?, ?, ?)",
                    i.x, i.y, i.z, i.slot, i.itemData
                )
            }
            for (s in signLines) {
                plugin.databaseManager.execute(
                    "INSERT INTO fake_base_signs (x, y, z, line_index, line_text) VALUES (?, ?, ?, ?, ?)",
                    s.x, s.y, s.z, s.lineIndex, s.text
                )
            }
        }

        plugin.commsManager.send(player, Component.text("Fake base template saved.", NamedTextColor.GREEN))
    }

    // ── Paste ──────────────────────────────────────────────

    fun hasTemplate(): Boolean {
        return plugin.databaseManager.queryFirst("SELECT id FROM fake_base_meta WHERE id = 1") { it.getInt("id") } != null
    }

    fun pasteTemplate(player: Player): PasteResult {
        val template = loadTemplate() ?: return PasteResult.NoTemplate

        val parsed = template.blocks.map { cb ->
            val data = try {
                Bukkit.createBlockData(cb.data)
            } catch (e: IllegalArgumentException) {
                null
            }
            Triple(cb.x, cb.y, cb.z) to data
        }
        if (parsed.any { it.second == null }) {
            plugin.logger.warning("[FakeBaseManager] Saved template contains unparseable block data; aborting paste.")
            return PasteResult.Failure("Fake base template is corrupted and could not be spawned.")
        }

        val world = player.world
        val anchor = player.location.block
        val originX = anchor.x
        val originY = anchor.y
        val originZ = anchor.z

        // Force-load every chunk the paste will touch before placing a single block.
        val minChunkX = originX shr 4
        val maxChunkX = (originX + template.sizeX - 1) shr 4
        val minChunkZ = originZ shr 4
        val maxChunkZ = (originZ + template.sizeZ - 1) shr 4
        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                world.getChunkAt(cx, cz)
            }
        }

        for ((coords, data) in parsed) {
            val (rx, ry, rz) = coords
            world.getBlockAt(originX + rx, originY + ry, originZ + rz).setBlockData(data!!, false)
        }

        for ((coords, entries) in template.items.groupBy { Triple(it.x, it.y, it.z) }) {
            val (rx, ry, rz) = coords
            val block = world.getBlockAt(originX + rx, originY + ry, originZ + rz)
            val state = block.state
            val inventory = when {
                state is Chest -> state.blockInventory
                state is Container -> state.inventory
                else -> null
            } ?: continue
            for (entry in entries) {
                inventory.setItem(entry.slot, deserializeItem(entry.itemData))
            }
            state.update(true, false)
        }

        for ((coords, lines) in template.signLines.groupBy { Triple(it.x, it.y, it.z) }) {
            val (rx, ry, rz) = coords
            val block = world.getBlockAt(originX + rx, originY + ry, originZ + rz)
            val state = block.state as? Sign ?: continue
            for (line in lines) {
                val side = if (line.lineIndex < 4) Side.FRONT else Side.BACK
                state.getSide(side).line(line.lineIndex % 4, gson.deserialize(line.text))
            }
            state.update(true, false)
        }

        return PasteResult.Success
    }

    private fun loadTemplate(): LoadedTemplate? {
        val meta = plugin.databaseManager.queryFirst(
            "SELECT size_x, size_y, size_z FROM fake_base_meta WHERE id = 1"
        ) { rs -> Triple(rs.getInt("size_x"), rs.getInt("size_y"), rs.getInt("size_z")) } ?: return null

        val blocks = plugin.databaseManager.query(
            "SELECT x, y, z, block_data FROM fake_base_blocks"
        ) { rs -> CapturedBlock(rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getString("block_data")) }

        val items = plugin.databaseManager.query(
            "SELECT x, y, z, slot, item_data FROM fake_base_items"
        ) { rs -> CapturedItem(rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getInt("slot"), rs.getString("item_data")) }

        val signLines = plugin.databaseManager.query(
            "SELECT x, y, z, line_index, line_text FROM fake_base_signs"
        ) { rs -> CapturedSignLine(rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getInt("line_index"), rs.getString("line_text")) }

        return LoadedTemplate(meta.first, meta.second, meta.third, blocks, items, signLines)
    }

    private fun serializeItem(item: ItemStack): String =
        Base64.getEncoder().encodeToString(item.serializeAsBytes())

    private fun deserializeItem(base64: String): ItemStack =
        ItemStack.deserializeBytes(Base64.getDecoder().decode(base64))
}
