package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player as NmsPlayer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.craftbukkit.CraftServer
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.EnumSet
import java.util.UUID

class NPCManager(private val plugin: Joshymc) : Listener {

    companion object {
        private const val TAG_PREFIX = "joshymc_npc_"
        private const val COOLDOWN_MS = 1000L
    }

    /** NPC id -> entity UUID of the spawned entity */
    private val npcs = mutableMapOf<String, UUID>()

    /** Player UUID -> last interaction timestamp (for cooldown) */
    private val cooldowns = mutableMapOf<UUID, Long>()

    private val legacySerializer = LegacyComponentSerializer.legacyAmpersand()

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS npcs (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                world TEXT NOT NULL,
                x DOUBLE NOT NULL,
                y DOUBLE NOT NULL,
                z DOUBLE NOT NULL,
                yaw REAL NOT NULL,
                pitch REAL NOT NULL,
                command TEXT NOT NULL DEFAULT '',
                skin TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())

        // Add skin column if upgrading from older schema
        try {
            plugin.databaseManager.execute("ALTER TABLE npcs ADD COLUMN skin TEXT NOT NULL DEFAULT ''")
        } catch (_: Exception) {
            // Column already exists
        }

        // Defer the actual NPC respawning until ALL worlds are loaded.
        // The 'spawn' world (and other plugin-managed worlds) are created after
        // NPCManager.start() runs, so loading immediately would skip every NPC
        // in those worlds. A 1-tick delay is enough — by then onEnable has
        // finished and every world is registered.
        plugin.server.scheduler.runTaskLater(plugin, Runnable { loadFromDatabase() }, 1L)
    }

    /** Load every saved NPC from the database and spawn it. Called after worlds load. */
    private fun loadFromDatabase() {
        // Clean up any orphaned NPC entities from previous runs first
        cleanupOrphanedNpcEntities()

        var loaded = 0
        var deferred = 0
        plugin.databaseManager.query("SELECT * FROM npcs") { rs ->
            NPCData(
                id = rs.getString("id"),
                name = rs.getString("name"),
                world = rs.getString("world"),
                x = rs.getDouble("x"),
                y = rs.getDouble("y"),
                z = rs.getDouble("z"),
                yaw = rs.getFloat("yaw"),
                pitch = rs.getFloat("pitch"),
                command = rs.getString("command"),
                skin = rs.getString("skin") ?: ""
            )
        }.forEach { data ->
            val world = Bukkit.getWorld(data.world)
            if (world != null) {
                val loc = Location(world, data.x, data.y, data.z, data.yaw, data.pitch)
                spawnNpc(data.id, data.name, loc, data.skin)
                loaded++
            } else {
                // World still isn't loaded yet — defer further. Try again in 60 ticks.
                deferred++
                val saved = data
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    val w = Bukkit.getWorld(saved.world)
                    if (w != null) {
                        val loc = Location(w, saved.x, saved.y, saved.z, saved.yaw, saved.pitch)
                        spawnNpc(saved.id, saved.name, loc, saved.skin)
                        plugin.logger.info("[NPC] Late-spawned NPC '${saved.id}' in world '${saved.world}'.")
                    } else {
                        plugin.logger.warning("[NPC] World '${saved.world}' is still not loaded — NPC '${saved.id}' was kept in database but not spawned.")
                    }
                }, 60L)
            }
        }

        plugin.logger.info("[NPC] Loaded $loaded NPC(s)" + if (deferred > 0) " ($deferred deferred for late world load)" else "" + ".")
    }

    /** Remove any zombie/entity left over from previous NPC spawns. */
    private fun cleanupOrphanedNpcEntities() {
        var removed = 0
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                if (entity.scoreboardTags.any { it.startsWith(TAG_PREFIX) }) {
                    entity.remove()
                    removed++
                }
            }
        }
        if (removed > 0) plugin.logger.info("[NPC] Cleaned up $removed orphaned NPC entit${if (removed != 1) "ies" else "y"}.")
    }

    fun stop() {
        // Remove all spawned NPC entities
        for ((_, entityUuid) in npcs) {
            Bukkit.getEntity(entityUuid)?.remove()
        }
        npcs.clear()
        cooldowns.clear()
    }

    fun createNPC(id: String, name: String, location: Location) {
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO npcs (id, name, world, x, y, z, yaw, pitch, command, skin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, name, location.world.name, location.x, location.y, location.z, location.yaw, location.pitch, "", ""
        )
        spawnNpc(id, name, location, "")
    }

    fun deleteNPC(id: String): Boolean {
        val entityUuid = npcs.remove(id) ?: return false
        Bukkit.getEntity(entityUuid)?.remove()
        plugin.databaseManager.execute("DELETE FROM npcs WHERE id = ?", id)
        return true
    }

    fun getNPCIds(): List<String> {
        return npcs.keys.toList()
    }

    fun moveNPC(id: String, location: Location): Boolean {
        val entityUuid = npcs[id] ?: return false
        val entity = Bukkit.getEntity(entityUuid) ?: return false

        entity.teleport(location)
        plugin.databaseManager.execute(
            "UPDATE npcs SET world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ? WHERE id = ?",
            location.world.name, location.x, location.y, location.z, location.yaw, location.pitch, id
        )
        return true
    }

    fun setCommand(id: String, command: String): Boolean {
        if (id !in npcs) return false
        plugin.databaseManager.execute("UPDATE npcs SET command = ? WHERE id = ?", command, id)
        return true
    }

    fun setName(id: String, name: String): Boolean {
        val entityUuid = npcs[id] ?: return false
        val entity = Bukkit.getEntity(entityUuid) ?: return false

        entity.customName(legacySerializer.deserialize(name))
        plugin.databaseManager.execute("UPDATE npcs SET name = ? WHERE id = ?", name, id)
        return true
    }

    /**
     * Set the NPC's skin to that of the named player (online or offline).
     * Returns true if the NPC exists and the skin was applied.
     */
    fun setSkin(id: String, playerName: String): Boolean {
        // Find the original NPC data so we can respawn it with the new skin
        val data = plugin.databaseManager.queryFirst(
            "SELECT name, world, x, y, z, yaw, pitch FROM npcs WHERE id = ?", id
        ) { rs ->
            NpcSpawnData(
                name = rs.getString("name"),
                world = rs.getString("world"),
                x = rs.getDouble("x"),
                y = rs.getDouble("y"),
                z = rs.getDouble("z"),
                yaw = rs.getFloat("yaw"),
                pitch = rs.getFloat("pitch")
            )
        } ?: return false

        // Persist the new skin name
        plugin.databaseManager.execute("UPDATE npcs SET skin = ? WHERE id = ?", playerName, id)

        // Despawn and respawn with new skin (NMS player NPC if possible, else zombie+head)
        npcs.remove(id)?.let { Bukkit.getEntity(it)?.remove() }
        playerNpcs.remove(id)

        val world = Bukkit.getWorld(data.world) ?: return false
        val loc = Location(world, data.x, data.y, data.z, data.yaw, data.pitch)
        spawnNpc(id, data.name, loc, playerName)
        return true
    }

    private data class NpcSpawnData(
        val name: String,
        val world: String,
        val x: Double, val y: Double, val z: Double,
        val yaw: Float, val pitch: Float
    )

    // ── Events ──────────────────────────────────────────────

    @EventHandler
    fun onInteract(event: PlayerInteractEntityEvent) {
        val npcId = getNPCId(event.rightClicked) ?: return
        event.isCancelled = true

        val player = event.player
        val now = System.currentTimeMillis()
        val last = cooldowns[player.uniqueId] ?: 0L
        if (now - last < COOLDOWN_MS) return
        cooldowns[player.uniqueId] = now

        val command = plugin.databaseManager.queryFirst(
            "SELECT command FROM npcs WHERE id = ?", npcId
        ) { rs -> rs.getString("command") } ?: return

        if (command.isBlank()) return

        val resolved = command.replace("{player}", player.name)
        player.performCommand(resolved)
    }

    @EventHandler
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (getNPCId(event.entity) != null) {
            event.isCancelled = true
        }
    }

    // ── Internal ────────────────────────────────────────────

    private fun spawnNpc(id: String, name: String, location: Location, skinPlayerName: String) {
        // Remove old entity if one exists
        npcs[id]?.let { Bukkit.getEntity(it)?.remove() }

        // Try real player NPC first via NMS reflection — gives actual player body + skin
        if (skinPlayerName.isNotBlank()) {
            val playerUuid = trySpawnPlayerNpc(id, name, location, skinPlayerName)
            if (playerUuid != null) {
                npcs[id] = playerUuid
                return
            }
            plugin.logger.info("[NPC] Falling back to zombie NPC for '$id' (NMS player spawn unavailable)")
        }

        // Fallback: Zombie with player head (works on any server, no NMS)
        val zombie = location.world.spawn(location, Zombie::class.java) { z ->
            z.customName(legacySerializer.deserialize(name))
            z.isCustomNameVisible = true
            z.setAI(false)
            z.isInvulnerable = true
            z.isSilent = true
            z.setGravity(false)
            z.isPersistent = true
            z.removeWhenFarAway = false
            z.setShouldBurnInDay(false)
            z.canPickupItems = false
            z.isInvisible = false
            z.isBaby = false
            z.addScoreboardTag("${TAG_PREFIX}$id")
        }

        if (skinPlayerName.isNotBlank()) {
            applySkinToZombie(zombie, skinPlayerName)
        } else {
            zombie.equipment?.helmet = ItemStack(Material.PLAYER_HEAD)
        }

        zombie.equipment?.helmetDropChance = 0.0f
        zombie.equipment?.chestplateDropChance = 0.0f
        zombie.equipment?.leggingsDropChance = 0.0f
        zombie.equipment?.bootsDropChance = 0.0f
        zombie.equipment?.itemInMainHandDropChance = 0.0f
        zombie.equipment?.itemInOffHandDropChance = 0.0f

        npcs[id] = zombie.uniqueId
    }

    private fun applySkinToZombie(zombie: Zombie, playerName: String) {
        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { meta ->
            try {
                val offline = Bukkit.getOfflinePlayer(playerName)
                meta.owningPlayer = offline
            } catch (_: Exception) {}
        }
        zombie.equipment?.helmet = head
        zombie.equipment?.helmetDropChance = 0.0f
    }

    /**
     * Spawns a real player NPC using direct NMS access (paperweight-userdev).
     * Returns the entity UUID on success, null on failure.
     */
    private fun trySpawnPlayerNpc(id: String, displayName: String, location: Location, skinPlayerName: String): UUID? {
        return try {
            plugin.logger.info("[NPC] Spawning player NPC '$id' with skin '$skinPlayerName'...")

            // 1. Fetch the target player's profile (with textures) via Paper's PlayerProfile API.
            //    .complete(true) blocks while it queries Mojang's session server if needed.
            val skinProfile = Bukkit.createProfile(skinPlayerName)
            try { skinProfile.complete(true) } catch (e: Exception) {
                plugin.logger.warning("[NPC] Failed to complete skin profile: ${e.message}")
            }

            // 2. Build our NMS GameProfile with a unique UUID + the skin's textures.
            //    We copy the textures property from the Paper profile directly.
            val npcUuid = UUID.randomUUID()
            val profileName = displayName
                .replace(Regex("§[0-9a-fk-or]"), "")
                .replace(Regex("&[0-9a-fk-or]"), "")
                .filter { it.isLetterOrDigit() || it == '_' }
                .take(16)
                .ifEmpty { "NPC" }

            val gameProfile = GameProfile(npcUuid, profileName)
            for (paperProp in skinProfile.properties) {
                val sig = paperProp.signature
                val nmsProp = if (sig != null) {
                    Property(paperProp.name, paperProp.value, sig)
                } else {
                    Property(paperProp.name, paperProp.value)
                }
                gameProfile.properties.put(paperProp.name, nmsProp)
            }
            plugin.logger.info("[NPC]   GameProfile built with ${gameProfile.properties.size()} property(ies)")

            // 3. Get the NMS server and the destination ServerLevel.
            val nmsServer = (Bukkit.getServer() as CraftServer).server
            val nmsLevel = (location.world as CraftWorld).handle as ServerLevel

            // 4. Construct the ServerPlayer.
            val clientInfo = ClientInformation.createDefault()
            val serverPlayer = ServerPlayer(nmsServer, nmsLevel, gameProfile, clientInfo)

            // 5. Position the entity. snapTo / absMoveTo / setPos all work; we
            //    call them all to be safe across point releases.
            serverPlayer.setPos(location.x, location.y, location.z)
            serverPlayer.absSnapTo(location.x, location.y, location.z, location.yaw, location.pitch)
            serverPlayer.yHeadRot = location.yaw

            // 6. Enable all skin layers on the NPC so it's actually visible.
            //    Without this the outer skin layers stay hidden and the NPC
            //    appears invisible to clients.
            applySkinLayerMetadata(serverPlayer)

            // 7. Send spawn packets to all online players in the same world.
            broadcastNpcSpawn(serverPlayer, npcUuid, serverPlayer.id, location)

            // 8. Track for save/restore + re-broadcast on player join.
            playerNpcs[id] = NpcRecord(npcUuid, serverPlayer.id, serverPlayer, displayName, location, skinPlayerName)

            plugin.logger.info("[NPC] Spawned '$id' (uuid=$npcUuid, entityId=${serverPlayer.id})")
            npcUuid
        } catch (e: Exception) {
            plugin.logger.warning("[NPC] Player NPC spawn failed: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /** Tracking data for player NPCs spawned via NMS. */
    private data class NpcRecord(
        val uuid: UUID,
        val entityId: Int,
        val nmsPlayer: ServerPlayer,
        val displayName: String,
        val location: Location,
        val skinName: String
    )
    private val playerNpcs = mutableMapOf<String, NpcRecord>()

    /**
     * Flip the skin-layer customisation byte on the NPC's entity data to 0x7F
     * (all layers enabled — cape, jacket, sleeves, pants legs, hat). Without
     * this, newly-spawned player NPCs render as invisible on the client.
     *
     * Uses reflection to locate Player.DATA_PLAYER_MODE_CUSTOMISATION so we
     * don't have to worry about protected-access or mapping drift.
     */
    private fun applySkinLayerMetadata(serverPlayer: Any) {
        try {
            val nmsPlayerClass = Class.forName("net.minecraft.world.entity.player.Player")
            val accessorField = nmsPlayerClass.declaredFields.firstOrNull { f ->
                f.type.simpleName == "EntityDataAccessor" &&
                    (f.name == "DATA_PLAYER_MODE_CUSTOMISATION" || f.name.contains("CUSTOMISATION", ignoreCase = true))
            } ?: run {
                plugin.logger.warning("[NPC] Couldn't find DATA_PLAYER_MODE_CUSTOMISATION accessor")
                return
            }
            accessorField.isAccessible = true
            val accessor = accessorField.get(null)

            // Walk up the class hierarchy to find the SynchedEntityData field on Entity.
            var cls: Class<*>? = serverPlayer.javaClass
            var entityData: Any? = null
            while (cls != null && entityData == null) {
                val f = cls.declaredFields.firstOrNull { it.type.simpleName == "SynchedEntityData" }
                if (f != null) {
                    f.isAccessible = true
                    entityData = f.get(serverPlayer)
                }
                cls = cls.superclass
            }
            if (entityData == null) {
                plugin.logger.warning("[NPC] Couldn't find SynchedEntityData field on ServerPlayer")
                return
            }

            val setMethod = entityData.javaClass.methods.firstOrNull {
                it.name == "set" && it.parameterCount == 2 &&
                    it.parameterTypes[0].simpleName == "EntityDataAccessor"
            } ?: run {
                plugin.logger.warning("[NPC] Couldn't find SynchedEntityData.set method")
                return
            }
            setMethod.invoke(entityData, accessor, 0x7F.toByte())
        } catch (e: Exception) {
            plugin.logger.warning("[NPC] applySkinLayerMetadata failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Build a ClientboundSetEntityDataPacket carrying the NPC's non-default values. */
    private fun buildEntityDataPacket(serverPlayer: Any, entityId: Int): Any? {
        return try {
            // Grab SynchedEntityData (same walk as above).
            var cls: Class<*>? = serverPlayer.javaClass
            var entityData: Any? = null
            while (cls != null && entityData == null) {
                val f = cls.declaredFields.firstOrNull { it.type.simpleName == "SynchedEntityData" }
                if (f != null) {
                    f.isAccessible = true
                    entityData = f.get(serverPlayer)
                }
                cls = cls.superclass
            }
            if (entityData == null) return null

            // Prefer getNonDefaultValues() (used on initial spawn), fall back to packDirty().
            val values = try {
                entityData.javaClass.getMethod("getNonDefaultValues").invoke(entityData)
            } catch (_: NoSuchMethodException) {
                entityData.javaClass.getMethod("packDirty").invoke(entityData)
            } ?: return null

            val metaPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket")
            val ctor = metaPacketClass.constructors.firstOrNull {
                it.parameterCount == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return null
            ctor.newInstance(entityId, values)
        } catch (e: Exception) {
            plugin.logger.warning("[NPC] buildEntityDataPacket failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Send spawn packets (PlayerInfoUpdate ADD + AddEntity) to nearby online players,
     * then schedule a packet to remove from the tab list a few ticks later (so the
     * entity stays but the name doesn't show in the player list).
     */
    /** Try a list of method names on the target until one works. Returns null if all fail. */
    private fun invokeAny(target: Any, vararg methodNames: String): Any? {
        for (name in methodNames) {
            try {
                return target.javaClass.getMethod(name).invoke(target)
            } catch (_: NoSuchMethodException) {
                // Try next
            } catch (_: Exception) {
                return null
            }
        }
        // Walk superclasses for any of the names
        var cls: Class<*>? = target.javaClass.superclass
        while (cls != null) {
            for (name in methodNames) {
                try {
                    val m = cls.getMethod(name)
                    return m.invoke(target)
                } catch (_: Exception) {}
            }
            cls = cls.superclass
        }
        return null
    }

    /** Reflectively get the `connection` field from a ServerPlayer instance. */
    private fun getConnection(serverPlayer: Any): Any? {
        var cls: Class<*>? = serverPlayer.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField("connection")
                f.isAccessible = true
                return f.get(serverPlayer)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }

    private fun sendPacket(connection: Any, packet: Any) {
        // Find any send(packet) method on the connection
        val sendMethod = connection.javaClass.methods.firstOrNull {
            it.name == "send" && it.parameterCount == 1
        } ?: return
        sendMethod.invoke(connection, packet)
    }

    /**
     * Send spawn packets to all players in the world.
     *
     * Uses the EXPLICIT 11-arg ClientboundAddEntityPacket constructor so the
     * packet contains exactly the entity ID, UUID, and position we want — not
     * whatever the half-initialized ServerPlayer contains.
     *
     * The entity ID is allocated from a high-range counter to avoid collisions
     * with real entities.
     */
    private fun broadcastNpcSpawn(serverPlayer: Any, npcUuid: UUID, entityId: Int, location: Location) {
        try {
            val world = location.world

            val craftPlayerClass = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer")
            val getHandle = craftPlayerClass.getMethod("getHandle")

            // ── PlayerInfoUpdatePacket ─────────────────────────
            val infoUpdatePacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket")
            val infoActionEnum = infoUpdatePacketClass.classes.firstOrNull { it.simpleName == "Action" }
                ?: Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket\$Action")

            val addPlayerAction = infoActionEnum.enumConstants.first { it.toString() == "ADD_PLAYER" }
            val updateListedAction = infoActionEnum.enumConstants.firstOrNull { it.toString() == "UPDATE_LISTED" }

            val enumSetClass = Class.forName("java.util.EnumSet")
            val actionSet = if (updateListedAction != null) {
                val noneOf = enumSetClass.getMethod("noneOf", Class::class.java).invoke(null, infoActionEnum)
                noneOf.javaClass.getMethod("add", Any::class.java).invoke(noneOf, addPlayerAction)
                noneOf.javaClass.getMethod("add", Any::class.java).invoke(noneOf, updateListedAction)
                noneOf
            } else {
                enumSetClass.getMethod("of", Enum::class.java).invoke(null, addPlayerAction)
            }

            val updatePacketCtor = infoUpdatePacketClass.constructors.firstOrNull { ctor ->
                ctor.parameterCount == 2 && ctor.parameterTypes[0].simpleName == "EnumSet"
            } ?: run {
                plugin.logger.warning("[NPC] No PlayerInfoUpdatePacket constructor found")
                return
            }

            val infoPacket = updatePacketCtor.newInstance(actionSet, listOf(serverPlayer))

            // ── AddEntityPacket (explicit constructor) ────────
            // Constructor signature in 1.21:
            // (int entityId, UUID uuid, double x, double y, double z,
            //  float pitch, float yaw, EntityType<?> type, int data, Vec3 deltaMovement, double yHead)
            val addEntityPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket")
            val entityTypeClass = Class.forName("net.minecraft.world.entity.EntityType")
            val vec3Class = Class.forName("net.minecraft.world.phys.Vec3")

            val playerEntityType = try {
                entityTypeClass.getField("PLAYER").get(null)
            } catch (e: Exception) {
                plugin.logger.warning("[NPC] Couldn't access EntityType.PLAYER: ${e.message}")
                return
            }
            val vec3Zero = try {
                vec3Class.getField("ZERO").get(null)
            } catch (e: Exception) {
                // Fallback: build via constructor
                vec3Class.getConstructor(Double::class.java, Double::class.java, Double::class.java)
                    .newInstance(0.0, 0.0, 0.0)
            }

            val explicitCtor = addEntityPacketClass.constructors.firstOrNull { ctor ->
                val pt = ctor.parameterTypes
                pt.size == 11 &&
                    pt[0] == Int::class.javaPrimitiveType &&
                    pt[1] == UUID::class.java
            }

            val addPacket: Any = if (explicitCtor != null) {
                explicitCtor.newInstance(
                    entityId,
                    npcUuid,
                    location.x,
                    location.y,
                    location.z,
                    location.pitch,
                    location.yaw,
                    playerEntityType,
                    0,
                    vec3Zero,
                    location.yaw.toDouble()
                )
            } else {
                plugin.logger.warning("[NPC] No 11-arg AddEntity constructor — falling back to 1-arg")
                val ctor1 = addEntityPacketClass.constructors.firstOrNull { it.parameterCount == 1 }
                    ?: run { plugin.logger.warning("[NPC] No AddEntity constructor at all"); return }
                ctor1.newInstance(serverPlayer)
            }

            // ── Metadata packet (skin layers, etc.) ──────────
            // Must be sent after AddEntity so the entity exists on the client.
            val metaPacket = buildEntityDataPacket(serverPlayer, entityId)

            // ── Send packets to all players in the world ────
            var sentTo = 0
            for (online in world.players) {
                val handle = getHandle.invoke(online)
                val connection = getConnection(handle) ?: continue
                sendPacket(connection, infoPacket)
                sendPacket(connection, addPacket)
                if (metaPacket != null) sendPacket(connection, metaPacket)
                sentTo++
            }
            plugin.logger.info("[NPC] Sent NPC spawn packets to $sentTo player(s) [entityId=$entityId pos=${location.blockX},${location.blockY},${location.blockZ}]")

            // ── After 30 ticks, remove from tab list ─────────
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                try {
                    val removePacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket")
                    val removeCtor = removePacketClass.constructors.first { it.parameterCount == 1 }
                    val removePacket = removeCtor.newInstance(listOf(npcUuid))
                    for (online in world.players) {
                        val handle = getHandle.invoke(online)
                        val connection = getConnection(handle) ?: continue
                        sendPacket(connection, removePacket)
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("[NPC] Failed to remove NPC from tab list: ${e.message}")
                }
            }, 30L)
        } catch (e: Exception) {
            plugin.logger.warning("[NPC] broadcastNpcSpawn failed: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }

    /** Re-send spawn packets when a player joins, so they see existing NPCs. */
    @EventHandler
    fun onJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
        // Delay so the player is fully connected
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            for (record in playerNpcs.values) {
                if (record.location.world == event.player.world) {
                    broadcastNpcSpawn(record.nmsPlayer, record.uuid, record.entityId, record.location)
                }
            }
        }, 20L)
    }

    private fun getNPCId(entity: Entity): String? {
        return entity.scoreboardTags
            .firstOrNull { it.startsWith(TAG_PREFIX) }
            ?.removePrefix(TAG_PREFIX)
    }

    private data class NPCData(
        val id: String,
        val name: String,
        val world: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val command: String,
        val skin: String
    )
}
