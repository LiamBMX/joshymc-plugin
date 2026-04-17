package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Particle.DustOptions
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max
import kotlin.math.min

class ClaimManager(private val plugin: Joshymc) : Listener {

    // ══════════════════════════════════════════════════════════
    //  DATA
    // ══════════════════════════════════════════════════════════

    data class Claim(
        val id: Int,
        val world: String,
        val x1: Int, val z1: Int,
        val x2: Int, val z2: Int,
        val ownerUuid: UUID,
        val teamName: String?,
        val createdAt: Long,
        val trusted: MutableSet<UUID> = mutableSetOf()
    ) {
        val minX get() = min(x1, x2)
        val maxX get() = max(x1, x2)
        val minZ get() = min(z1, z2)
        val maxZ get() = max(z1, z2)
        val area get() = (maxX - minX + 1) * (maxZ - minZ + 1)

        fun contains(loc: Location): Boolean {
            return loc.world?.name == world
                    && loc.blockX in minX..maxX
                    && loc.blockZ in minZ..maxZ
        }

        fun contains(block: Block): Boolean = contains(block.location)
    }

    data class Subclaim(
        val id: Int,
        val world: String,
        val x1: Int, val y1: Int, val z1: Int,
        val x2: Int, val y2: Int, val z2: Int,
        val ownerUuid: UUID,
        val parentClaimId: Int,
        val accessList: MutableSet<UUID> = mutableSetOf()
    ) {
        fun contains(loc: Location): Boolean {
            if (loc.world?.name != world) return false
            val bx = loc.blockX; val by = loc.blockY; val bz = loc.blockZ
            return bx in min(x1, x2)..max(x1, x2)
                    && by in min(y1, y2)..max(y1, y2)
                    && bz in min(z1, z2)..max(z1, z2)
        }

        fun contains(block: Block): Boolean = contains(block.location)
    }

    data class SubclaimSelection(var pos1: Location? = null, var pos2: Location? = null)

    // ══════════════════════════════════════════════════════════
    //  STATE
    // ══════════════════════════════════════════════════════════

    private val claims = mutableListOf<Claim>()
    private val subclaims = mutableListOf<Subclaim>()
    val selections = mutableMapOf<UUID, SubclaimSelection>()

    /** Players with first corner set, waiting for second corner */
    private val pendingCorner1 = mutableMapOf<UUID, Location>()

    val showingParticles = mutableSetOf<UUID>()
    private var particleTask: BukkitTask? = null
    private var blockAccrualTask: BukkitTask? = null

    private var startingBlocks = 500
    private var blocksPerHour = 100
    private var maxTotalBlocks = 50000

    private val claimWandKey = NamespacedKey(plugin, "claim_wand")

    // ══════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════

    fun start() {
        startingBlocks = plugin.config.getInt("claims.starting-blocks", 500)
        blocksPerHour = plugin.config.getInt("claims.blocks-per-hour", 100)
        maxTotalBlocks = plugin.config.getInt("claims.max-blocks", 50000)

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS claims_v2 (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                world TEXT NOT NULL,
                x1 INTEGER NOT NULL, z1 INTEGER NOT NULL,
                x2 INTEGER NOT NULL, z2 INTEGER NOT NULL,
                owner_uuid TEXT NOT NULL,
                team_name TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        // Migrate: drop old chunk-based subclaims table if it has the wrong schema
        try {
            plugin.databaseManager.queryFirst(
                "SELECT parent_claim_id FROM subclaims LIMIT 1"
            ) { it.getInt(1) }
        } catch (_: Exception) {
            plugin.logger.info("[Claims] Migrating subclaims table to new schema...")
            plugin.databaseManager.execute("DROP TABLE IF EXISTS subclaims")
            plugin.databaseManager.execute("DROP TABLE IF EXISTS subclaim_access")
        }

        // Also drop old chunk-based claims table if present
        plugin.databaseManager.execute("DROP TABLE IF EXISTS claims")

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS subclaims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                world TEXT NOT NULL,
                x1 INTEGER NOT NULL, y1 INTEGER NOT NULL, z1 INTEGER NOT NULL,
                x2 INTEGER NOT NULL, y2 INTEGER NOT NULL, z2 INTEGER NOT NULL,
                owner_uuid TEXT NOT NULL,
                parent_claim_id INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS subclaim_access (
                subclaim_id INTEGER NOT NULL,
                player_uuid TEXT NOT NULL,
                PRIMARY KEY (subclaim_id, player_uuid)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS claim_trusted (
                claim_id INTEGER NOT NULL,
                player_uuid TEXT NOT NULL,
                PRIMARY KEY (claim_id, player_uuid)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS claim_blocks (
                uuid TEXT PRIMARY KEY,
                total_blocks INTEGER NOT NULL DEFAULT $startingBlocks
            )
        """.trimIndent())

        loadClaims()
        loadSubclaims()
        startParticleTask()
        startBlockAccrualTask()

        plugin.logger.info("[Claims] Started with ${claims.size} claim(s) and ${subclaims.size} subclaim(s).")
    }

    fun stop() {
        particleTask?.cancel(); particleTask = null
        blockAccrualTask?.cancel(); blockAccrualTask = null
        showingParticles.clear()
    }

    private fun loadClaims() {
        claims.clear()
        claims.addAll(plugin.databaseManager.query("SELECT * FROM claims_v2") { rs ->
            val id = rs.getInt("id")
            val trustedUuids = plugin.databaseManager.query(
                "SELECT player_uuid FROM claim_trusted WHERE claim_id = ?", id
            ) { tr -> UUID.fromString(tr.getString("player_uuid")) }
            Claim(
                id = id, world = rs.getString("world"),
                x1 = rs.getInt("x1"), z1 = rs.getInt("z1"),
                x2 = rs.getInt("x2"), z2 = rs.getInt("z2"),
                ownerUuid = UUID.fromString(rs.getString("owner_uuid")),
                teamName = rs.getString("team_name"),
                createdAt = rs.getLong("created_at"),
                trusted = trustedUuids.toMutableSet()
            )
        })
    }

    private fun loadSubclaims() {
        subclaims.clear()
        subclaims.addAll(plugin.databaseManager.query("SELECT * FROM subclaims") { rs ->
            val id = rs.getInt("id")
            val access = plugin.databaseManager.query(
                "SELECT player_uuid FROM subclaim_access WHERE subclaim_id = ?", id
            ) { a -> UUID.fromString(a.getString("player_uuid")) }
            Subclaim(
                id = id, world = rs.getString("world"),
                x1 = rs.getInt("x1"), y1 = rs.getInt("y1"), z1 = rs.getInt("z1"),
                x2 = rs.getInt("x2"), y2 = rs.getInt("y2"), z2 = rs.getInt("z2"),
                ownerUuid = UUID.fromString(rs.getString("owner_uuid")),
                parentClaimId = rs.getInt("parent_claim_id"),
                accessList = access.toMutableSet()
            )
        })
    }

    // ══════════════════════════════════════════════════════════
    //  CLAIM BLOCKS
    // ══════════════════════════════════════════════════════════

    fun getTotalBlocks(uuid: UUID): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT total_blocks FROM claim_blocks WHERE uuid = ?", uuid.toString()
        ) { it.getInt("total_blocks") } ?: startingBlocks
    }

    fun getUsedBlocks(uuid: UUID): Int {
        return claims.filter { it.ownerUuid == uuid }.sumOf { it.area }
    }

    fun getAvailableBlocks(uuid: UUID): Int {
        return getTotalBlocks(uuid) - getUsedBlocks(uuid)
    }

    fun addBlocks(uuid: UUID, amount: Int) {
        val current = getTotalBlocks(uuid)
        val newTotal = (current + amount).coerceAtMost(maxTotalBlocks)
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO claim_blocks (uuid, total_blocks) VALUES (?, ?)",
            uuid.toString(), newTotal
        )
    }

    fun setBlocks(uuid: UUID, amount: Int) {
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO claim_blocks (uuid, total_blocks) VALUES (?, ?)",
            uuid.toString(), amount.coerceAtMost(maxTotalBlocks)
        )
    }

    private fun startBlockAccrualTask() {
        if (blocksPerHour <= 0) return
        // Run every 5 minutes, give 1/12th of hourly rate
        val blocksPerInterval = (blocksPerHour / 12.0).toInt().coerceAtLeast(1)
        blockAccrualTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                addBlocks(player.uniqueId, blocksPerInterval)
            }
        }, 6000L, 6000L) // Every 5 minutes
    }

    // ══════════════════════════════════════════════════════════
    //  CLAIM OPERATIONS
    // ══════════════════════════════════════════════════════════

    fun getClaimAt(location: Location): Claim? {
        return claims.firstOrNull { it.contains(location) }
    }

    fun getClaimById(id: Int): Claim? = claims.find { it.id == id }

    fun getClaimsByPlayer(uuid: UUID): List<Claim> = claims.filter { it.ownerUuid == uuid }

    fun getClaimsByTeam(teamName: String): List<Claim> = claims.filter { it.teamName == teamName }

    /**
     * Create a claim between two corners. Returns the new claim or null on failure.
     */
    private val blockedWorlds = setOf("resource", "spawn", "afk")

    fun createClaim(player: Player, pos1: Location, pos2: Location): ClaimCreateResult {
        val worldName = pos1.world?.name ?: return ClaimCreateResult.Failure("Invalid world.")
        if (worldName in blockedWorlds) return ClaimCreateResult.Failure("You cannot claim land in this world.")
        if (pos1.world?.name != pos2.world?.name) return ClaimCreateResult.Failure("Corners must be in the same world.")

        val minX = min(pos1.blockX, pos2.blockX)
        val maxX = max(pos1.blockX, pos2.blockX)
        val minZ = min(pos1.blockZ, pos2.blockZ)
        val maxZ = max(pos1.blockZ, pos2.blockZ)
        val area = (maxX - minX + 1) * (maxZ - minZ + 1)

        if (area < 4) return ClaimCreateResult.Failure("Claim must be at least 2x2.")
        if (area > 10000) return ClaimCreateResult.Failure("Claim too large (max 10,000 blocks per claim).")

        val available = getAvailableBlocks(player.uniqueId)
        if (area > available) return ClaimCreateResult.Failure("Not enough claim blocks. Need $area, have $available. Earn more by playing!")

        // Check overlap with existing claims
        for (existing in claims) {
            if (existing.world != worldName) continue
            if (overlaps(minX, minZ, maxX, maxZ, existing.minX, existing.minZ, existing.maxX, existing.maxZ)) {
                if (existing.ownerUuid == player.uniqueId) {
                    return ClaimCreateResult.Failure("Overlaps with your existing claim.")
                }
                return ClaimCreateResult.Failure("Overlaps with ${Bukkit.getOfflinePlayer(existing.ownerUuid).name ?: "someone"}'s claim.")
            }
        }

        plugin.databaseManager.execute(
            "INSERT INTO claims_v2 (world, x1, z1, x2, z2, owner_uuid, team_name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            worldName, minX, minZ, maxX, maxZ, player.uniqueId.toString(), null, System.currentTimeMillis()
        )

        val id = plugin.databaseManager.queryFirst("SELECT last_insert_rowid() as id") { it.getInt("id") } ?: 0
        val claim = Claim(id, worldName, minX, minZ, maxX, maxZ, player.uniqueId, null, System.currentTimeMillis())
        claims.add(claim)

        // Save terrain snapshot before any modifications
        saveTerrainSnapshot(claim)

        return ClaimCreateResult.Success(claim)
    }

    fun deleteClaim(player: Player, claim: Claim): Boolean {
        if (claim.ownerUuid != player.uniqueId && !player.hasPermission("joshymc.claim.admin")) return false

        // Remove subclaims in this claim
        val subs = subclaims.filter { it.parentClaimId == claim.id }
        for (sc in subs) {
            plugin.databaseManager.execute("DELETE FROM subclaim_access WHERE subclaim_id = ?", sc.id)
            plugin.databaseManager.execute("DELETE FROM subclaims WHERE id = ?", sc.id)
        }
        subclaims.removeAll(subs.toSet())

        // Remove trusted players
        plugin.databaseManager.execute("DELETE FROM claim_trusted WHERE claim_id = ?", claim.id)

        // Restore terrain from snapshot
        restoreTerrainSnapshot(claim)

        plugin.databaseManager.execute("DELETE FROM claims_v2 WHERE id = ?", claim.id)
        claims.remove(claim)
        return true
    }

    // ══════════════════════════════════════════════════════════
    //  TERRAIN SNAPSHOTS — save on claim, restore on unclaim
    // ══════════════════════════════════════════════════════════

    private fun getSnapshotDir(): File {
        val dir = File(plugin.dataFolder, "snapshots")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getSnapshotFile(claim: Claim): File {
        return File(getSnapshotDir(), "claim_${claim.id}.gz")
    }

    /**
     * Save the terrain within a claim's bounds as a compressed text file.
     * Format: one line per block — "x,y,z,blockDataString"
     */
    private fun saveTerrainSnapshot(claim: Claim) {
        val world = Bukkit.getWorld(claim.world) ?: return
        val file = getSnapshotFile(claim)

        try {
            GZIPOutputStream(FileOutputStream(file)).bufferedWriter().use { writer ->
                for (x in claim.minX..claim.maxX) {
                    for (z in claim.minZ..claim.maxZ) {
                        val minY = world.minHeight
                        val maxY = world.maxHeight
                        for (y in minY until maxY) {
                            val block = world.getBlockAt(x, y, z)
                            if (block.type != Material.AIR) {
                                writer.write("$x,$y,$z,${block.blockData.asString}")
                                writer.newLine()
                            }
                        }
                    }
                }
            }
            plugin.logger.info("[Claims] Saved terrain snapshot for claim #${claim.id} (${file.length() / 1024}KB)")
        } catch (e: Exception) {
            plugin.logger.warning("[Claims] Failed to save terrain snapshot for claim #${claim.id}: ${e.message}")
        }
    }

    /**
     * Restore terrain from a saved snapshot. Clears all blocks first, then places saved blocks.
     */
    private fun restoreTerrainSnapshot(claim: Claim) {
        val world = Bukkit.getWorld(claim.world) ?: return
        val file = getSnapshotFile(claim)

        if (!file.exists()) {
            plugin.logger.warning("[Claims] No snapshot found for claim #${claim.id}, skipping restore.")
            return
        }

        try {
            // First, clear the entire claim area to air
            for (x in claim.minX..claim.maxX) {
                for (z in claim.minZ..claim.maxZ) {
                    for (y in world.maxHeight - 1 downTo world.minHeight) {
                        val block = world.getBlockAt(x, y, z)
                        if (block.type != Material.AIR && block.type != Material.BEDROCK) {
                            block.setType(Material.AIR, false)
                        }
                    }
                }
            }

            // Then restore from snapshot
            var blocksRestored = 0
            GZIPInputStream(FileInputStream(file)).bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val parts = line.split(",", limit = 4)
                    if (parts.size == 4) {
                        val x = parts[0].toIntOrNull() ?: return@forEachLine
                        val y = parts[1].toIntOrNull() ?: return@forEachLine
                        val z = parts[2].toIntOrNull() ?: return@forEachLine
                        val dataString = parts[3]

                        try {
                            val block = world.getBlockAt(x, y, z)
                            val blockData = Bukkit.createBlockData(dataString)
                            block.setBlockData(blockData, false)
                            blocksRestored++
                        } catch (_: Exception) {
                            // Skip invalid block data
                        }
                    }
                }
            }

            // Delete the snapshot file
            file.delete()
            plugin.logger.info("[Claims] Restored terrain for claim #${claim.id} ($blocksRestored blocks)")
        } catch (e: Exception) {
            plugin.logger.warning("[Claims] Failed to restore terrain for claim #${claim.id}: ${e.message}")
        }
    }

    fun assignToTeam(player: Player, claim: Claim): Boolean {
        if (claim.ownerUuid != player.uniqueId) return false
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId) ?: return false
        plugin.databaseManager.execute("UPDATE claims_v2 SET team_name = ? WHERE id = ?", teamName, claim.id)
        val idx = claims.indexOf(claim)
        if (idx >= 0) claims[idx] = claim.copy(teamName = teamName)
        return true
    }

    fun assignToPersonal(player: Player, claim: Claim): Boolean {
        if (claim.ownerUuid != player.uniqueId) return false
        plugin.databaseManager.execute("UPDATE claims_v2 SET team_name = NULL WHERE id = ?", claim.id)
        val idx = claims.indexOf(claim)
        if (idx >= 0) claims[idx] = claim.copy(teamName = null)
        return true
    }

    // ══════════════════════════════════════════════════════════
    //  TRUST SYSTEM
    // ══════════════════════════════════════════════════════════

    fun trustPlayer(claim: Claim, targetUuid: UUID): Boolean {
        if (claim.trusted.contains(targetUuid)) return false
        plugin.databaseManager.execute(
            "INSERT OR IGNORE INTO claim_trusted (claim_id, player_uuid) VALUES (?, ?)",
            claim.id, targetUuid.toString()
        )
        claim.trusted.add(targetUuid)
        return true
    }

    fun untrustPlayer(claim: Claim, targetUuid: UUID): Boolean {
        if (!claim.trusted.contains(targetUuid)) return false
        plugin.databaseManager.execute(
            "DELETE FROM claim_trusted WHERE claim_id = ? AND player_uuid = ?",
            claim.id, targetUuid.toString()
        )
        claim.trusted.remove(targetUuid)
        return true
    }

    fun getTrustedPlayers(claim: Claim): Set<UUID> = claim.trusted

    sealed class ClaimCreateResult {
        data class Success(val claim: Claim) : ClaimCreateResult()
        data class Failure(val reason: String) : ClaimCreateResult()
    }

    private fun overlaps(ax1: Int, az1: Int, ax2: Int, az2: Int, bx1: Int, bz1: Int, bx2: Int, bz2: Int): Boolean {
        return ax1 <= bx2 && ax2 >= bx1 && az1 <= bz2 && az2 >= bz1
    }

    // ══════════════════════════════════════════════════════════
    //  SUBCLAIM OPERATIONS
    // ══════════════════════════════════════════════════════════

    fun createSubclaim(player: Player): SubclaimResult {
        val sel = selections[player.uniqueId] ?: return SubclaimResult.NO_SELECTION
        val pos1 = sel.pos1 ?: return SubclaimResult.NO_SELECTION
        val pos2 = sel.pos2 ?: return SubclaimResult.NO_SELECTION
        if (pos1.world?.name != pos2.world?.name) return SubclaimResult.DIFFERENT_WORLDS

        val worldName = pos1.world!!.name
        val claim = getClaimAt(pos1) ?: return SubclaimResult.NOT_IN_CLAIM
        if (!canManageClaim(player, claim)) return SubclaimResult.NO_PERMISSION

        plugin.databaseManager.execute(
            "INSERT INTO subclaims (world, x1, y1, z1, x2, y2, z2, owner_uuid, parent_claim_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            worldName, pos1.blockX, pos1.blockY, pos1.blockZ, pos2.blockX, pos2.blockY, pos2.blockZ,
            player.uniqueId.toString(), claim.id
        )

        val id = plugin.databaseManager.queryFirst("SELECT last_insert_rowid() as id") { it.getInt("id") } ?: return SubclaimResult.DB_ERROR
        subclaims.add(Subclaim(id, worldName, pos1.blockX, pos1.blockY, pos1.blockZ, pos2.blockX, pos2.blockY, pos2.blockZ, player.uniqueId, claim.id))
        selections.remove(player.uniqueId)
        return SubclaimResult.SUCCESS
    }

    fun deleteSubclaim(id: Int, player: Player): Boolean {
        val sc = subclaims.find { it.id == id } ?: return false
        val parentClaim = getClaimById(sc.parentClaimId)
        if (sc.ownerUuid != player.uniqueId && !player.hasPermission("joshymc.claim.admin")) {
            if (parentClaim == null || !canManageClaim(player, parentClaim)) return false
        }
        plugin.databaseManager.execute("DELETE FROM subclaim_access WHERE subclaim_id = ?", id)
        plugin.databaseManager.execute("DELETE FROM subclaims WHERE id = ?", id)
        subclaims.removeAll { it.id == id }
        return true
    }

    fun addSubclaimAccess(subclaimId: Int, targetUuid: UUID): Boolean {
        val sc = subclaims.find { it.id == subclaimId } ?: return false
        if (sc.accessList.contains(targetUuid)) return false
        plugin.databaseManager.execute("INSERT OR IGNORE INTO subclaim_access (subclaim_id, player_uuid) VALUES (?, ?)", subclaimId, targetUuid.toString())
        sc.accessList.add(targetUuid)
        return true
    }

    fun removeSubclaimAccess(subclaimId: Int, targetUuid: UUID): Boolean {
        val sc = subclaims.find { it.id == subclaimId } ?: return false
        plugin.databaseManager.execute("DELETE FROM subclaim_access WHERE subclaim_id = ? AND player_uuid = ?", subclaimId, targetUuid.toString())
        sc.accessList.remove(targetUuid)
        return true
    }

    fun getSubclaimsInClaim(claim: Claim): List<Subclaim> = subclaims.filter { it.parentClaimId == claim.id }
    fun getSubclaimAt(location: Location): Subclaim? = subclaims.firstOrNull { it.contains(location) }
    fun getSubclaim(id: Int): Subclaim? = subclaims.find { it.id == id }

    enum class SubclaimResult { SUCCESS, NO_SELECTION, DIFFERENT_WORLDS, NOT_IN_CLAIM, NO_PERMISSION, DB_ERROR }

    // ══════════════════════════════════════════════════════════
    //  PERMISSION CHECKING
    // ══════════════════════════════════════════════════════════

    fun canAccess(player: Player, location: Location): Boolean {
        if (player.hasPermission("joshymc.claim.bypass")) return true
        val claim = getClaimAt(location) ?: return true // unclaimed = open

        val subclaim = getSubclaimAt(location)
        if (subclaim != null) {
            if (subclaim.accessList.contains(player.uniqueId)) return true
            if (subclaim.ownerUuid == player.uniqueId) return true
        }

        if (claim.ownerUuid == player.uniqueId) return true

        // Trusted players have full access
        if (claim.trusted.contains(player.uniqueId)) return true

        if (claim.teamName != null) {
            val playerTeam = plugin.teamManager.getPlayerTeam(player.uniqueId)
            if (playerTeam == claim.teamName) return true
        }

        return false
    }

    fun canManageClaim(player: Player, claim: Claim): Boolean {
        if (player.hasPermission("joshymc.claim.admin")) return true
        if (claim.ownerUuid == player.uniqueId) return true
        if (claim.teamName != null) {
            val playerTeam = plugin.teamManager.getPlayerTeam(player.uniqueId)
            if (playerTeam == claim.teamName) {
                val role = plugin.teamManager.getPlayerRole(player.uniqueId)
                return role == "owner" || role == "admin"
            }
        }
        return false
    }

    // ══════════════════════════════════════════════════════════
    //  GOLDEN SHOVEL — claim creation tool
    // ══════════════════════════════════════════════════════════

    fun isClaimWand(player: Player): Boolean {
        val item = player.inventory.itemInMainHand
        return item.type == Material.GOLDEN_SHOVEL
    }

    @EventHandler
    fun onShovelInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        if (!isClaimWand(player)) return
        if (!player.hasPermission("joshymc.claim")) return

        if (player.world.name in blockedWorlds) {
            plugin.commsManager.send(player, Component.text("You cannot claim land in this world.", NamedTextColor.RED))
            return
        }

        event.isCancelled = true
        val block = event.clickedBlock ?: return
        val loc = block.location

        val corner1 = pendingCorner1[player.uniqueId]

        if (corner1 == null) {
            // First click — set corner 1
            pendingCorner1[player.uniqueId] = loc
            player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f)
            plugin.commsManager.send(player,
                Component.text("Corner 1 set at ${loc.blockX}, ${loc.blockZ}. ", NamedTextColor.GREEN)
                    .append(Component.text("Right-click another block to set corner 2.", NamedTextColor.GRAY))
            )
            // Show corner pillar
            showCornerPillar(player, loc, Color.LIME)
        } else {
            // Second click — create claim
            pendingCorner1.remove(player.uniqueId)

            if (corner1.world?.name != loc.world?.name) {
                plugin.commsManager.send(player, Component.text("Both corners must be in the same world.", NamedTextColor.RED))
                return
            }

            val result = createClaim(player, corner1, loc)
            when (result) {
                is ClaimCreateResult.Success -> {
                    val claim = result.claim
                    player.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f)
                    plugin.commsManager.send(player,
                        Component.text("Claim created! ", NamedTextColor.GREEN)
                            .append(Component.text("${claim.area} blocks", NamedTextColor.GOLD))
                            .append(Component.text(" (${claim.maxX - claim.minX + 1}x${claim.maxZ - claim.minZ + 1}). ", NamedTextColor.GREEN))
                            .append(Component.text("${getAvailableBlocks(player.uniqueId)} blocks remaining.", NamedTextColor.GRAY))
                    )
                    // Show the full border
                    showClaimBorder(player, claim, Color.LIME)
                }
                is ClaimCreateResult.Failure -> {
                    player.playSound(loc, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
                    plugin.commsManager.send(player, Component.text(result.reason, NamedTextColor.RED))
                }
            }
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        // Ensure player has claim blocks entry
        val uuid = event.player.uniqueId
        plugin.databaseManager.execute(
            "INSERT OR IGNORE INTO claim_blocks (uuid, total_blocks) VALUES (?, ?)",
            uuid.toString(), startingBlocks
        )
    }

    // ══════════════════════════════════════════════════════════
    //  PARTICLE VISUALIZATION — highly visible borders
    // ══════════════════════════════════════════════════════════

    fun toggleParticles(player: Player): Boolean {
        val uuid = player.uniqueId
        return if (showingParticles.contains(uuid)) {
            showingParticles.remove(uuid); false
        } else {
            showingParticles.add(uuid); true
        }
    }

    private fun startParticleTask() {
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (uuid in showingParticles.toSet()) {
                val player = Bukkit.getPlayer(uuid) ?: continue
                val playerTeam = plugin.teamManager.getPlayerTeam(player.uniqueId)

                for (claim in claims) {
                    if (claim.world != player.world.name) continue
                    // Only show nearby claims (within 64 blocks)
                    val cx = (claim.minX + claim.maxX) / 2.0
                    val cz = (claim.minZ + claim.maxZ) / 2.0
                    if (player.location.x < cx - 80 || player.location.x > cx + 80) continue
                    if (player.location.z < cz - 80 || player.location.z > cz + 80) continue

                    val color = when {
                        claim.ownerUuid == player.uniqueId -> Color.LIME
                        claim.teamName != null && claim.teamName == playerTeam -> Color.AQUA
                        else -> Color.RED
                    }
                    showClaimBorder(player, claim, color)
                }
            }
        }, 15L, 15L) // Every 15 ticks
    }

    /** Draw a bright border at ground level for a claim */
    private fun showClaimBorder(player: Player, claim: Claim, color: Color) {
        val world = player.world
        val dust = DustOptions(color, 1.5f) // Big bright particles
        val cornerDust = DustOptions(Color.YELLOW, 2.0f) // Extra bright corners

        val minX = claim.minX; val maxX = claim.maxX + 1 // +1 because claim includes the max block
        val minZ = claim.minZ; val maxZ = claim.maxZ + 1

        // Draw edges at ground level with 0.5 block spacing (dense)
        // North edge (minZ)
        var x = minX.toDouble()
        while (x <= maxX.toDouble()) {
            val y = world.getHighestBlockYAt(x.toInt(), minZ) + 1.0
            player.spawnParticle(Particle.DUST, x, y, minZ.toDouble(), 1, dust)
            player.spawnParticle(Particle.DUST, x, y + 0.5, minZ.toDouble(), 1, dust)
            x += 0.5
        }
        // South edge (maxZ)
        x = minX.toDouble()
        while (x <= maxX.toDouble()) {
            val y = world.getHighestBlockYAt(x.toInt(), maxZ) + 1.0
            player.spawnParticle(Particle.DUST, x, y, maxZ.toDouble(), 1, dust)
            player.spawnParticle(Particle.DUST, x, y + 0.5, maxZ.toDouble(), 1, dust)
            x += 0.5
        }
        // West edge (minX)
        var z = minZ.toDouble()
        while (z <= maxZ.toDouble()) {
            val y = world.getHighestBlockYAt(minX, z.toInt()) + 1.0
            player.spawnParticle(Particle.DUST, minX.toDouble(), y, z, 1, dust)
            player.spawnParticle(Particle.DUST, minX.toDouble(), y + 0.5, z, 1, dust)
            z += 0.5
        }
        // East edge (maxX)
        z = minZ.toDouble()
        while (z <= maxZ.toDouble()) {
            val y = world.getHighestBlockYAt(maxX, z.toInt()) + 1.0
            player.spawnParticle(Particle.DUST, maxX.toDouble(), y, z, 1, dust)
            player.spawnParticle(Particle.DUST, maxX.toDouble(), y + 0.5, z, 1, dust)
            z += 0.5
        }

        // Bright corner pillars (5 blocks tall)
        val corners = listOf(
            minX.toDouble() to minZ.toDouble(),
            maxX.toDouble() to minZ.toDouble(),
            minX.toDouble() to maxZ.toDouble(),
            maxX.toDouble() to maxZ.toDouble()
        )
        for ((cx, cz) in corners) {
            val baseY = world.getHighestBlockYAt(cx.toInt(), cz.toInt()) + 1.0
            for (dy in 0..5) {
                player.spawnParticle(Particle.DUST, cx, baseY + dy, cz, 2, cornerDust)
            }
        }
    }

    /** Show a single corner pillar when placing first corner */
    private fun showCornerPillar(player: Player, loc: Location, color: Color) {
        val dust = DustOptions(color, 2.0f)
        for (dy in 0..7) {
            player.spawnParticle(Particle.DUST, loc.blockX + 0.5, loc.blockY + 1.0 + dy, loc.blockZ + 0.5, 3, dust)
        }
    }

    // ══════════════════════════════════════════════════════════
    //  CLAIM MAP
    // ══════════════════════════════════════════════════════════

    fun buildClaimMap(player: Player): List<String> {
        val loc = player.location
        val worldName = loc.world.name
        val playerTeam = plugin.teamManager.getPlayerTeam(player.uniqueId)
        val radius = 40 // blocks
        val lines = mutableListOf<String>()

        lines.add("&7--- Claim Map (40 block radius) ---")
        for (dz in -4..4) {
            val sb = StringBuilder("  ")
            for (dx in -8..8) {
                val checkX = loc.blockX + dx * 5
                val checkZ = loc.blockZ + dz * 5
                val checkLoc = Location(loc.world, checkX.toDouble(), 0.0, checkZ.toDouble())
                val claim = getClaimAt(checkLoc)
                val char = when {
                    dx == 0 && dz == 0 && claim == null -> "&e+"
                    dx == 0 && dz == 0 -> "&a+"
                    claim == null -> "&8-"
                    claim.ownerUuid == player.uniqueId -> "&a#"
                    claim.teamName != null && claim.teamName == playerTeam -> "&b#"
                    else -> "&c#"
                }
                sb.append(char)
            }
            lines.add(sb.toString())
        }
        lines.add("&a# &7yours  &b# &7team  &c# &7other  &8- &7free  &e+ &7you")
        return lines
    }
}
