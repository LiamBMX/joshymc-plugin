package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.Base64
import java.util.UUID

class TeamManager(private val plugin: Joshymc) : Listener {

    data class TeamInfo(val name: String, val displayName: String, val ownerUuid: String, val createdAt: Long)
    data class TeamMember(val uuid: String, val teamName: String, val role: String, val joinedAt: Long)
    data class BountyInfo(val id: Int, val targetUuid: String, val targetName: String, val placedByUuid: String, val placedByName: String, val amount: Double, val placedAt: Long)

    private val openEchests = mutableMapOf<UUID, String>() // player UUID -> team name

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS teams (
                name TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                owner_uuid TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS team_members (
                uuid TEXT PRIMARY KEY,
                team_name TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'member',
                joined_at INTEGER NOT NULL,
                FOREIGN KEY (team_name) REFERENCES teams(name)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS team_invites (
                uuid TEXT NOT NULL,
                team_name TEXT NOT NULL,
                invited_by TEXT NOT NULL,
                invited_at INTEGER NOT NULL,
                PRIMARY KEY (uuid, team_name)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS bounties (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                target_uuid TEXT NOT NULL,
                target_name TEXT NOT NULL,
                placed_by_uuid TEXT NOT NULL,
                placed_by_name TEXT NOT NULL,
                amount REAL NOT NULL,
                placed_at INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS team_balances (
                team_name TEXT PRIMARY KEY,
                balance REAL DEFAULT 0
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS team_echests (
                team_name TEXT NOT NULL,
                slot INTEGER NOT NULL,
                item TEXT NOT NULL,
                PRIMARY KEY (team_name, slot)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS team_homes (
                team_name TEXT PRIMARY KEY,
                world TEXT NOT NULL,
                x REAL NOT NULL,
                y REAL NOT NULL,
                z REAL NOT NULL,
                yaw REAL NOT NULL,
                pitch REAL NOT NULL
            )
        """.trimIndent())

        plugin.logger.info("[Teams] TeamManager started.")
    }

    // ── Team methods ──

    fun createTeam(name: String, displayName: String, owner: Player): Boolean {
        if (getTeam(name) != null) return false
        if (getPlayerTeam(owner.uniqueId) != null) return false

        val now = System.currentTimeMillis()
        plugin.databaseManager.execute(
            "INSERT INTO teams (name, display_name, owner_uuid, created_at) VALUES (?, ?, ?, ?)",
            name, displayName, owner.uniqueId.toString(), now
        )
        plugin.databaseManager.execute(
            "INSERT INTO team_members (uuid, team_name, role, joined_at) VALUES (?, ?, ?, ?)",
            owner.uniqueId.toString(), name, "owner", now
        )
        return true
    }

    fun deleteTeam(name: String): Boolean {
        val team = getTeam(name) ?: return false
        plugin.databaseManager.execute("DELETE FROM team_invites WHERE team_name = ?", name)
        plugin.databaseManager.execute("DELETE FROM team_members WHERE team_name = ?", name)
        plugin.databaseManager.execute("DELETE FROM team_balances WHERE team_name = ?", name)
        plugin.databaseManager.execute("DELETE FROM team_echests WHERE team_name = ?", name)
        plugin.databaseManager.execute("DELETE FROM team_homes WHERE team_name = ?", name)
        plugin.databaseManager.execute("DELETE FROM teams WHERE name = ?", name)
        return true
    }

    fun getTeam(name: String): TeamInfo? {
        return plugin.databaseManager.queryFirst(
            "SELECT * FROM teams WHERE name = ?", name
        ) { rs ->
            TeamInfo(
                rs.getString("name"),
                rs.getString("display_name"),
                rs.getString("owner_uuid"),
                rs.getLong("created_at")
            )
        }
    }

    fun getAllTeams(): List<TeamInfo> {
        return plugin.databaseManager.query("SELECT * FROM teams ORDER BY name") { rs ->
            TeamInfo(
                rs.getString("name"),
                rs.getString("display_name"),
                rs.getString("owner_uuid"),
                rs.getLong("created_at")
            )
        }
    }

    fun getPlayerTeam(uuid: UUID): String? {
        return plugin.databaseManager.queryFirst(
            "SELECT team_name FROM team_members WHERE uuid = ?", uuid.toString()
        ) { rs -> rs.getString("team_name") }
    }

    fun getTeamMembers(name: String): List<TeamMember> {
        return plugin.databaseManager.query(
            "SELECT * FROM team_members WHERE team_name = ?", name
        ) { rs ->
            TeamMember(
                rs.getString("uuid"),
                rs.getString("team_name"),
                rs.getString("role"),
                rs.getLong("joined_at")
            )
        }
    }

    fun getPlayerRole(uuid: UUID): String? {
        return plugin.databaseManager.queryFirst(
            "SELECT role FROM team_members WHERE uuid = ?", uuid.toString()
        ) { rs -> rs.getString("role") }
    }

    fun invitePlayer(teamName: String, inviterUuid: UUID, targetUuid: UUID) {
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO team_invites (uuid, team_name, invited_by, invited_at) VALUES (?, ?, ?, ?)",
            targetUuid.toString(), teamName, inviterUuid.toString(), System.currentTimeMillis()
        )
    }

    fun getPendingInvites(uuid: UUID): List<String> {
        return plugin.databaseManager.query(
            "SELECT team_name FROM team_invites WHERE uuid = ?", uuid.toString()
        ) { rs -> rs.getString("team_name") }
    }

    fun acceptInvite(uuid: UUID, teamName: String): Boolean {
        val invite = plugin.databaseManager.queryFirst(
            "SELECT * FROM team_invites WHERE uuid = ? AND team_name = ?",
            uuid.toString(), teamName
        ) { true } ?: return false

        if (getPlayerTeam(uuid) != null) return false

        plugin.databaseManager.execute(
            "DELETE FROM team_invites WHERE uuid = ? AND team_name = ?",
            uuid.toString(), teamName
        )
        plugin.databaseManager.execute(
            "INSERT INTO team_members (uuid, team_name, role, joined_at) VALUES (?, ?, ?, ?)",
            uuid.toString(), teamName, "member", System.currentTimeMillis()
        )
        return true
    }

    fun kickMember(teamName: String, uuid: UUID): Boolean {
        val member = plugin.databaseManager.queryFirst(
            "SELECT role FROM team_members WHERE uuid = ? AND team_name = ?",
            uuid.toString(), teamName
        ) { rs -> rs.getString("role") } ?: return false

        if (member == "owner") return false

        plugin.databaseManager.execute(
            "DELETE FROM team_members WHERE uuid = ? AND team_name = ?",
            uuid.toString(), teamName
        )
        return true
    }

    fun leaveTeam(uuid: UUID): Boolean {
        val role = getPlayerRole(uuid) ?: return false
        if (role == "owner") return false

        plugin.databaseManager.execute("DELETE FROM team_members WHERE uuid = ?", uuid.toString())
        return true
    }

    fun promoteToAdmin(teamName: String, uuid: UUID): Boolean {
        val role = plugin.databaseManager.queryFirst(
            "SELECT role FROM team_members WHERE uuid = ? AND team_name = ?",
            uuid.toString(), teamName
        ) { rs -> rs.getString("role") } ?: return false

        if (role != "member") return false

        plugin.databaseManager.execute(
            "UPDATE team_members SET role = 'admin' WHERE uuid = ? AND team_name = ?",
            uuid.toString(), teamName
        )
        return true
    }

    fun demoteToMember(teamName: String, uuid: UUID): Boolean {
        val role = plugin.databaseManager.queryFirst(
            "SELECT role FROM team_members WHERE uuid = ? AND team_name = ?",
            uuid.toString(), teamName
        ) { rs -> rs.getString("role") } ?: return false

        if (role != "admin") return false

        plugin.databaseManager.execute(
            "UPDATE team_members SET role = 'member' WHERE uuid = ? AND team_name = ?",
            uuid.toString(), teamName
        )
        return true
    }

    fun transferOwnership(teamName: String, newOwnerUuid: UUID): Boolean {
        val team = getTeam(teamName) ?: return false

        val targetRole = plugin.databaseManager.queryFirst(
            "SELECT role FROM team_members WHERE uuid = ? AND team_name = ?",
            newOwnerUuid.toString(), teamName
        ) { rs -> rs.getString("role") } ?: return false

        plugin.databaseManager.execute(
            "UPDATE team_members SET role = 'member' WHERE uuid = ? AND team_name = ?",
            team.ownerUuid, teamName
        )
        plugin.databaseManager.execute(
            "UPDATE team_members SET role = 'owner' WHERE uuid = ? AND team_name = ?",
            newOwnerUuid.toString(), teamName
        )
        plugin.databaseManager.execute(
            "UPDATE teams SET owner_uuid = ? WHERE name = ?",
            newOwnerUuid.toString(), teamName
        )
        return true
    }

    fun isTeammate(uuid1: UUID, uuid2: UUID): Boolean {
        val team1 = getPlayerTeam(uuid1) ?: return false
        val team2 = getPlayerTeam(uuid2) ?: return false
        return team1 == team2
    }

    // ── Team home methods ──

    fun setTeamHome(teamName: String, location: Location) {
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO team_homes (team_name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?)",
            teamName, location.world.name, location.x, location.y, location.z, location.yaw, location.pitch
        )
    }

    fun getTeamHome(teamName: String): Location? {
        return plugin.databaseManager.queryFirst(
            "SELECT world, x, y, z, yaw, pitch FROM team_homes WHERE team_name = ?", teamName
        ) { rs ->
            val world = plugin.server.getWorld(rs.getString("world")) ?: return@queryFirst null
            Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getFloat("yaw"), rs.getFloat("pitch"))
        }
    }

    // ── Team balance methods ──

    fun getTeamBalance(teamName: String): Double {
        return plugin.databaseManager.queryFirst(
            "SELECT balance FROM team_balances WHERE team_name = ?", teamName
        ) { rs -> rs.getDouble("balance") } ?: 0.0
    }

    fun depositTeamBalance(teamName: String, amount: Double) {
        val current = getTeamBalance(teamName)
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO team_balances (team_name, balance) VALUES (?, ?)",
            teamName, current + amount
        )
    }

    fun withdrawTeamBalance(teamName: String, amount: Double): Boolean {
        val current = getTeamBalance(teamName)
        if (current < amount) return false
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO team_balances (team_name, balance) VALUES (?, ?)",
            teamName, current - amount
        )
        return true
    }

    fun setTeamBalance(teamName: String, amount: Double) {
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO team_balances (team_name, balance) VALUES (?, ?)",
            teamName, amount
        )
    }

    // ── Team echest methods ──

    fun openTeamEchest(player: Player, teamName: String) {
        val title = Component.text("Team Chest", NamedTextColor.AQUA)
        val inv = Bukkit.createInventory(null, 54, title)

        // Load items from DB
        val items = plugin.databaseManager.query(
            "SELECT slot, item FROM team_echests WHERE team_name = ?", teamName
        ) { rs ->
            val slot = rs.getInt("slot")
            val base64 = rs.getString("item")
            val bytes = Base64.getDecoder().decode(base64)
            val itemStack = ItemStack.deserializeBytes(bytes)
            slot to itemStack
        }

        for ((slot, itemStack) in items) {
            if (slot in 0 until 54) {
                inv.setItem(slot, itemStack)
            }
        }

        // Anti-dupe: clear DB immediately after loading into GUI.
        // The only copy of these items now lives in this inventory.
        // They get written back on close or quit.
        plugin.databaseManager.execute(
            "DELETE FROM team_echests WHERE team_name = ?", teamName
        )

        openEchests[player.uniqueId] = teamName
        player.openInventory(inv)
    }

    fun saveTeamEchest(teamName: String, inventory: Inventory) {
        plugin.databaseManager.transaction {
            plugin.databaseManager.execute(
                "DELETE FROM team_echests WHERE team_name = ?", teamName
            )

            for (slot in 0 until inventory.size) {
                val item = inventory.getItem(slot)
                if (item != null && item.type != Material.AIR) {
                    val bytes = item.serializeAsBytes()
                    val base64 = Base64.getEncoder().encodeToString(bytes)
                    plugin.databaseManager.execute(
                        "INSERT INTO team_echests (team_name, slot, item) VALUES (?, ?, ?)",
                        teamName, slot, base64
                    )
                }
            }
        }
    }

    @EventHandler
    fun onEchestClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val teamName = openEchests.remove(player.uniqueId) ?: return
        saveTeamEchest(teamName, event.inventory)
    }

    @EventHandler
    fun onEchestQuit(event: PlayerQuitEvent) {
        val player = event.player
        val teamName = openEchests.remove(player.uniqueId) ?: return
        val inv = player.openInventory.topInventory
        saveTeamEchest(teamName, inv)
    }

    // ── Bounty methods ──

    fun placeBounty(placedByUuid: UUID, placedByName: String, targetUuid: UUID, targetName: String, amount: Double): Boolean {
        if (!plugin.economyManager.withdraw(placedByUuid, amount)) return false

        plugin.databaseManager.execute(
            "INSERT INTO bounties (target_uuid, target_name, placed_by_uuid, placed_by_name, amount, placed_at) VALUES (?, ?, ?, ?, ?, ?)",
            targetUuid.toString(), targetName, placedByUuid.toString(), placedByName, amount, System.currentTimeMillis()
        )
        return true
    }

    fun getBounties(): List<BountyInfo> {
        return plugin.databaseManager.query("SELECT * FROM bounties ORDER BY amount DESC") { rs ->
            BountyInfo(
                rs.getInt("id"),
                rs.getString("target_uuid"),
                rs.getString("target_name"),
                rs.getString("placed_by_uuid"),
                rs.getString("placed_by_name"),
                rs.getDouble("amount"),
                rs.getLong("placed_at")
            )
        }
    }

    fun getBountiesOnPlayer(uuid: UUID): List<BountyInfo> {
        return plugin.databaseManager.query(
            "SELECT * FROM bounties WHERE target_uuid = ? ORDER BY amount DESC",
            uuid.toString()
        ) { rs ->
            BountyInfo(
                rs.getInt("id"),
                rs.getString("target_uuid"),
                rs.getString("target_name"),
                rs.getString("placed_by_uuid"),
                rs.getString("placed_by_name"),
                rs.getDouble("amount"),
                rs.getLong("placed_at")
            )
        }
    }

    fun getTotalBounty(uuid: UUID): Double {
        return plugin.databaseManager.queryFirst(
            "SELECT COALESCE(SUM(amount), 0) AS total FROM bounties WHERE target_uuid = ?",
            uuid.toString()
        ) { rs -> rs.getDouble("total") } ?: 0.0
    }

    fun claimBounties(killerUuid: UUID, victimUuid: UUID) {
        val bounties = getBountiesOnPlayer(victimUuid)
        if (bounties.isEmpty()) return

        val total = bounties.sumOf { it.amount }
        plugin.economyManager.deposit(killerUuid, total)
        plugin.databaseManager.execute(
            "DELETE FROM bounties WHERE target_uuid = ?",
            victimUuid.toString()
        )

        val killer = Bukkit.getPlayer(killerUuid)
        val victimName = bounties.first().targetName
        if (killer != null) {
            plugin.commsManager.send(
                killer,
                Component.text("You collected ", NamedTextColor.GRAY)
                    .append(Component.text(plugin.economyManager.format(total), NamedTextColor.GREEN))
                    .append(Component.text(" in bounties from ", NamedTextColor.GRAY))
                    .append(Component.text(victimName, NamedTextColor.WHITE)),
                CommunicationsManager.Category.DEFAULT
            )
        }

        // Broadcast bounty claim
        Bukkit.getOnlinePlayers().forEach { p ->
            if (p.uniqueId != killerUuid) {
                plugin.commsManager.send(
                    p,
                    Component.text(killer?.name ?: "Unknown", NamedTextColor.WHITE)
                        .append(Component.text(" claimed ", NamedTextColor.GRAY))
                        .append(Component.text(plugin.economyManager.format(total), NamedTextColor.GREEN))
                        .append(Component.text(" bounty on ", NamedTextColor.GRAY))
                        .append(Component.text(victimName, NamedTextColor.WHITE)),
                    CommunicationsManager.Category.DEFAULT
                )
            }
        }
    }

    fun cancelBounty(id: Int, playerUuid: UUID): Boolean {
        val bounty = plugin.databaseManager.queryFirst(
            "SELECT * FROM bounties WHERE id = ? AND placed_by_uuid = ?",
            id, playerUuid.toString()
        ) { rs ->
            BountyInfo(
                rs.getInt("id"),
                rs.getString("target_uuid"),
                rs.getString("target_name"),
                rs.getString("placed_by_uuid"),
                rs.getString("placed_by_name"),
                rs.getDouble("amount"),
                rs.getLong("placed_at")
            )
        } ?: return false

        plugin.databaseManager.execute("DELETE FROM bounties WHERE id = ?", id)
        plugin.economyManager.deposit(playerUuid, bounty.amount)
        return true
    }

    // ── Event listeners ──

    @EventHandler(priority = EventPriority.LOW)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message())

        if (!plainMessage.startsWith("!")) return

        event.isCancelled = true

        val teamName = getPlayerTeam(player.uniqueId) ?: run {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val msg = plainMessage.removePrefix("!").trim()
        if (msg.isEmpty()) return

        sendTeamMessage(player, teamName, msg)
    }

    fun sendTeamMessage(sender: Player, teamName: String, message: String) {
        val team = getTeam(teamName) ?: return
        val members = getTeamMembers(teamName)

        val formatted = Component.text("[Team] ", NamedTextColor.GREEN)
            .append(Component.text(sender.name, NamedTextColor.WHITE))
            .append(Component.text(": ", NamedTextColor.GRAY))
            .append(Component.text(message, NamedTextColor.WHITE))

        members.forEach { member ->
            val onlinePlayer = Bukkit.getPlayer(UUID.fromString(member.uuid))
            onlinePlayer?.sendMessage(formatted)
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return

        val attacker: Player? = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }

        if (attacker == null || attacker == victim) return

        if (isTeammate(attacker.uniqueId, victim.uniqueId)) {
            event.isCancelled = true
            plugin.commsManager.send(
                attacker,
                Component.text("You cannot hurt your teammate!", NamedTextColor.RED),
                CommunicationsManager.Category.DEFAULT
            )
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val victim = event.player
        val killer = victim.killer ?: return

        val bounties = getBountiesOnPlayer(victim.uniqueId)
        if (bounties.isEmpty()) return

        claimBounties(killer.uniqueId, victim.uniqueId)
    }
}
