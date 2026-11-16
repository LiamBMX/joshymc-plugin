package com.liam.joshymc.communityevent.inventory

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.Step
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.Then
import com.liam.joshymc.lifesteal.ranked.RankedInventorySafety
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Issue #1550: the event world's separate inventory, crash safe. See [EventInventoryRules] for the
 * protocol (SQLite snapshot rows + a marker in the player's own persistent data, which is saved in
 * the same player-file write as their inventory). Everything here runs on the main thread.
 *
 * Entry: snapshot (cursor and crafting-grid items placed into it, every stack checked to read back
 * exactly) → row stored → *then*, in one tick, inventory emptied + marker set → player saved. A failed
 * or refused snapshot changes nothing and moves the player out of the event world.
 *
 * Exit (world change, quit, shutdown): row → RESTORING (with a copy of what it replaces) → in one tick,
 * items back + marker removed → player saved → RESTORED. A failed step leaves a state the next
 * reconcile (join, world change, 30s retry) settles from the marker; nothing is ever applied twice.
 */
class EventInventoryKeeper(
    private val plugin: Joshymc,
    private val eventWorldName: () -> String,
    /** Event-only items (hosted-event gear, the event wand): what a live event inventory may hold. */
    private val isEventItem: (ItemStack) -> Boolean,
    private val track: (UUID) -> Unit,
    private val untrack: (UUID) -> Unit,
) {
    val store = EventInventoryStore(plugin.databaseManager)
    private val markerKey = NamespacedKey(plugin, "event_inventory_snapshot")
    private val bootId = UUID.randomUUID().toString()
    private val comms get() = plugin.commsManager
    private val category = CommunicationsManager.Category.EVENT

    /** Players being reconciled right now (a world change inside a restore can't start a second one). */
    private val busy: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    /** Players whose last reconcile hit a database error; tried again every 30s and on their next change. */
    private val retry: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    var ready = false
        private set

    fun start() {
        try {
            store.createTables()
            ready = true
        } catch (e: Exception) {
            plugin.logger.severe("[Event] Couldn't create the event inventory tables: ${e.message}. Players can't enter the event world until this is fixed.")
            return
        }
        migrateLegacyYaml()
        // A plugin reload with players online: settle everyone where they stand.
        for (player in Bukkit.getOnlinePlayers()) reconcile(player, inEventWorld(player), "startup")
    }

    private fun inEventWorld(player: Player) = player.world.name == eventWorldName()

    fun marker(player: Player): Long? = player.persistentDataContainer.get(markerKey, PersistentDataType.LONG)

    /**
     * Issue #1577: whether [player]'s inventory is part of an event-inventory operation that isn't
     * settled (being reconciled, waiting for a retry, the player file still holds the event inventory,
     * or an open snapshot row). Read only; a database error counts as unsettled.
     */
    fun unsettled(player: Player): Boolean {
        val uuid = player.uniqueId
        if (uuid in busy || uuid in retry || marker(player) != null) return true
        if (!ready) return false
        return try { store.open(uuid) != null } catch (_: Exception) { true }
    }

    // ── Reconciliation ─────────────────────────────────────

    private enum class StepResult { OK, CONFLICT, FAILED }

    /**
     * Settles [player] for where they are ([inEventWorld]): applies, confirms, marks stale or sends
     * to review whatever the marker and their open row say, then enters / resumes / ejects.
     */
    fun reconcile(player: Player, inEventWorld: Boolean, cause: String) {
        val uuid = player.uniqueId
        if (!player.isOnline && cause != "quit") return
        if (!busy.add(uuid)) return
        try {
            if (!ready) {
                if (inEventWorld) eject(player, "The event inventory system isn't available right now, so you were moved out. Nothing was changed.")
                return
            }
            repeat(3) {
                val state = try {
                    readState(player, inEventWorld)
                } catch (e: Exception) {
                    failed(player, inEventWorld, "read", e)
                    return
                }
                val plan = EventInventoryRules.reconcile(state)
                var conflict = false
                for (step in plan.steps) {
                    when (run(player, step, cause)) {
                        StepResult.OK -> Unit
                        StepResult.CONFLICT -> { conflict = true; break }
                        StepResult.FAILED -> {
                            retry += uuid
                            if (inEventWorld) eject(player, "Your inventory couldn't be handled safely right now, so you were moved out. Nothing was lost; it is tried again automatically.")
                            return
                        }
                    }
                }
                if (conflict) return@repeat
                retry -= uuid
                when (plan.then) {
                    Then.NONE -> Unit
                    Then.ENTER -> enter(player)
                    Then.RESUME -> resume(player, cause)
                    Then.EJECT -> eject(player, "Your saved event inventory needs a staff check before you can use the event world. Nothing was lost; your current items weren't touched.")
                }
                return
            }
            plugin.logger.warning("[Event] ${player.name}'s event inventory kept changing while it was being settled ($cause); tried again later.")
            retry += uuid
            if (inEventWorld) eject(player, "Your inventory couldn't be handled safely right now, so you were moved out. Nothing was lost.")
        } finally {
            busy.remove(uuid)
        }
    }

    private fun failed(player: Player, inEventWorld: Boolean, step: String, e: Exception) {
        plugin.logger.severe("[Event] Couldn't $step ${player.name}'s event inventory snapshot: ${e.message}. Nothing was changed; it is tried again.")
        retry += player.uniqueId
        if (inEventWorld) eject(player, "Your inventory couldn't be handled safely right now, so you were moved out. Nothing was changed.")
    }

    private fun readState(player: Player, inEventWorld: Boolean): EventInventoryRules.State {
        val open = store.open(player.uniqueId)
        val marker = marker(player)
        val markerRow = if (marker != null && marker != open?.id) store.forMarker(player.uniqueId, marker) else null
        return EventInventoryRules.State(open?.row(), marker, markerRow?.row(), inEventWorld, liveEventOnly(player))
    }

    private fun liveEventOnly(player: Player): Boolean {
        val live = player.inventory.contents.toMutableList<ItemStack?>()
        live += player.itemOnCursor
        val top = player.openInventory.topInventory
        if (top is CraftingInventory && top.type.name in RankedInventorySafety.CRAFTING_GRIDS) live += top.matrix
        return live.all { it == null || it.type.isAir || it.amount <= 0 || isEventItem(it) }
    }

    private fun run(player: Player, step: Step, cause: String): StepResult {
        val now = System.currentTimeMillis()
        return try {
            when (step) {
                is Step.MarkStale -> moved(store.move(step.id, step.from, EventInventoryRules.STALE, step.reason, "system:$cause", now))
                is Step.MarkRestored -> moved(store.move(step.id, EventInventoryRules.RESTORING, EventInventoryRules.RESTORED, step.reason, "system:$cause", now))
                is Step.ToReview -> moved(store.move(step.id, step.from, EventInventoryRules.REVIEW, step.reason, "system:$cause", now))
                    .also { if (it == StepResult.OK) notifyReview(player, step.id, step.reason) }
                is Step.OrphanReview -> {
                    val id = store.insertOrphanReview(player.uniqueId, player.name, step.marker, step.reason, now)
                    notifyReview(player, id, step.reason)
                    StepResult.OK
                }
                is Step.Restore -> restore(player, step.id, step.from, "system:$cause", automatic = true)
                is Step.AdoptLegacy -> adoptLegacy(player, step.id, cause)
                is Step.ClearMarker -> {
                    player.persistentDataContainer.remove(markerKey)
                    savePlayer(player)
                    store.audit(player.uniqueId, step.marker, "MARKER_CLEARED", "system:$cause", "staff resolved the snapshot this marker points at", now)
                    StepResult.OK
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("[Event] ${player.name}: event inventory step ${step::class.simpleName} (row ${step.id}) failed: ${e.message}. Nothing more was changed; it is tried again.")
            StepResult.FAILED
        }
    }

    private fun moved(ok: Boolean) = if (ok) StepResult.OK else StepResult.CONFLICT

    // ── Entry ──────────────────────────────────────────────

    private object PaperStacks : RankedInventorySafety.Stacks<ItemStack> {
        override fun similar(a: ItemStack, b: ItemStack): Boolean = a.isSimilar(b)
        override fun amount(stack: ItemStack): Int = stack.amount
        override fun maxStack(stack: ItemStack): Int = stack.maxStackSize
        override fun withAmount(stack: ItemStack, amount: Int): ItemStack = stack.clone().also { it.amount = amount }
    }

    private fun encode(stack: ItemStack): String = Base64.getEncoder().encodeToString(stack.serializeAsBytes())
    private fun decode(b64: String): ItemStack = ItemStack.deserializeBytes(Base64.getDecoder().decode(b64))

    /** Stores the player's whole inventory (cursor and crafting grid included), then switches them to the event inventory. */
    private fun enter(player: Player) {
        val inventory = player.inventory
        val top = player.openInventory.topInventory
        val menuType = top.type.name
        val menuProblem = RankedInventorySafety.openMenuProblem("You", menuType, top.contents.any { it != null && !it.type.isAir && it.amount > 0 })
        val grid = (top as? CraftingInventory)?.takeIf { menuType in RankedInventorySafety.CRAFTING_GRIDS }
        val loose = buildList {
            player.itemOnCursor.takeIf { !it.type.isAir && it.amount > 0 }?.let { add(it.clone()) }
            grid?.matrix?.forEach { s -> if (s != null && !s.type.isAir && s.amount > 0) add(s.clone()) }
        }
        val current = inventory.contents.map { s -> s?.takeIf { !it.type.isAir && it.amount > 0 }?.clone() }
        val placement = RankedInventorySafety.place(current, loose, PaperStacks)
        var mismatches = 0
        val slots = placement.slots.map { stack ->
            stack?.let {
                try {
                    val b64 = encode(it)
                    val back = decode(b64)
                    if (!back.isSimilar(it) || back.amount != it.amount) mismatches++
                    b64
                } catch (e: Exception) {
                    mismatches++
                    null
                }
            }
        }
        EventInventoryRules.entryRefusal(menuProblem, placement.leftover.size, mismatches)?.let { why ->
            plugin.logger.warning("[Event] ${player.name} wasn't switched to the event inventory: $why.")
            eject(player, "You can't enter the event world right now: $why. Your items were not touched.")
            return
        }
        val id = try {
            store.insertActive(player.uniqueId, player.name, slots, inventory.heldItemSlot, player.gameMode.name, bootId, System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.severe("[Event] Couldn't store ${player.name}'s inventory before the event world (${e.message}); they were moved out and nothing was changed.")
            eject(player, "Your inventory couldn't be saved safely, so you can't enter the event world right now. Nothing was changed.")
            return
        }
        // The snapshot is durable. One tick: everything emptied and the marker set, so any player-file
        // save from here on holds both the event inventory and the marker, or neither.
        player.setItemOnCursor(null)
        grid?.let { g ->
            g.matrix = arrayOfNulls(g.matrix.size)
            g.result = null
        }
        inventory.contents = arrayOfNulls(inventory.contents.size)
        player.closeInventory()
        player.persistentDataContainer.set(markerKey, PersistentDataType.LONG, id)
        player.gameMode = GameMode.ADVENTURE
        track(player.uniqueId)
        player.updateInventory()
        savePlayer(player)
        comms.send(player, Component.text("Welcome to the event world! Use /spawn to leave.", NamedTextColor.YELLOW))
    }

    private fun resume(player: Player, cause: String) {
        track(player.uniqueId)
        // After a restart the player loads straight into the event world: same as a fresh entry.
        if (cause == "join" || cause == "startup") player.gameMode = GameMode.ADVENTURE
    }

    /** LEGACY → ACTIVE for a player standing in the event world with only event items. */
    private fun adoptLegacy(player: Player, id: Long, cause: String): StepResult {
        // Marker and save first: if the server dies before the row moves, the row is still LEGACY with
        // the marker = its id, which settles the same way next time (never as "never saved").
        player.persistentDataContainer.set(markerKey, PersistentDataType.LONG, id)
        player.inventory.contents = arrayOfNulls(player.inventory.contents.size)
        player.setItemOnCursor(null)
        player.updateInventory()
        savePlayer(player)
        return moved(store.move(id, EventInventoryRules.LEGACY, EventInventoryRules.ACTIVE, EventInventoryRules.LEGACY_ADOPTED, "system:$cause", System.currentTimeMillis(), bootId = bootId))
    }

    // ── Restore ────────────────────────────────────────────

    /**
     * Puts snapshot [id] back on [player] if it is still [from]. Never applies the same row twice: the
     * conditional move to RESTORING is the claim. A snapshot that can't be read goes to REVIEW with
     * nothing changed.
     */
    private fun restore(player: Player, id: Long, from: String, actor: String, automatic: Boolean): StepResult {
        val snap = store.byId(id) ?: return StepResult.CONFLICT
        if (snap.status != from || snap.uuid != player.uniqueId) return StepResult.CONFLICT
        val contents = try {
            val slots = snap.slots ?: error("the row holds no items")
            slots.map { it?.let(::decode) }.toTypedArray()
        } catch (e: Exception) {
            plugin.logger.severe("[Event] ${player.name}'s event inventory snapshot $id can't be read (${e.message}); nothing was changed, it waits for staff (/event inventory).")
            if (from == EventInventoryRules.REVIEW) throw e
            val moved = store.move(id, from, EventInventoryRules.REVIEW, "${EventInventoryRules.UNREADABLE}: ${e.message}", actor, System.currentTimeMillis())
            if (moved) notifyReview(player, id, EventInventoryRules.UNREADABLE)
            return StepResult.CONFLICT // re-plan: the row is now REVIEW
        }
        val replaced = try {
            (player.inventory.contents.toList() + player.itemOnCursor).map { s -> s?.takeIf { !it.type.isAir && it.amount > 0 }?.let(::encode) }
        } catch (e: Exception) {
            plugin.logger.warning("[Event] Couldn't keep a copy of ${player.name}'s event inventory before restoring: ${e.message}")
            null
        }
        val now = System.currentTimeMillis()
        if (!store.move(id, from, EventInventoryRules.RESTORING, "restore started ($actor)", actor, now, bootId = bootId, replaced = replaced)) return StepResult.CONFLICT

        // One tick: items back and the marker removed together.
        player.setItemOnCursor(null)
        val top = player.openInventory.topInventory
        if (top.type.name in RankedInventorySafety.CRAFTING_GRIDS || top.type.name in RankedInventorySafety.RETURNED_ON_CLOSE) top.clear()
        player.closeInventory()
        val inventory = player.inventory
        if (contents.size == inventory.contents.size) {
            inventory.contents = contents
        } else {
            inventory.contents = arrayOfNulls(inventory.contents.size)
            for ((i, stack) in contents.withIndex()) if (stack != null) inventory.setItem(i, stack)
        }
        if (snap.heldSlot in 0..8) inventory.heldItemSlot = snap.heldSlot
        snap.gamemode?.let { gm -> runCatching { player.gameMode = GameMode.valueOf(gm) } }
        player.persistentDataContainer.remove(markerKey)
        player.updateInventory()
        savePlayer(player)

        val confirmed = try {
            store.move(id, EventInventoryRules.RESTORING, EventInventoryRules.RESTORED, "restored ($actor)", actor, System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.warning("[Event] Restored ${player.name}'s event snapshot $id but couldn't confirm it (${e.message}); it is confirmed on the next check and never applied again.")
            retry += player.uniqueId
            false
        }
        plugin.logger.info("[Event] Restored ${player.name}'s inventory from event snapshot $id ($actor)${if (confirmed) "" else ", confirmation pending"}.")
        if (player.isOnline) {
            comms.send(player, Component.text(if (automatic) "Your inventory has been restored." else "Staff restored your saved inventory.", NamedTextColor.GREEN))
        }
        return StepResult.OK
    }

    private fun savePlayer(player: Player) {
        try {
            player.saveData()
        } catch (e: Exception) {
            // Paper usually only logs a failed save; either way the marker decides on the next load.
            plugin.logger.severe("[Event] Couldn't save ${player.name}'s player data: ${e.message}. Their event inventory marker settles it on their next join.")
        }
    }

    // ── Eject ──────────────────────────────────────────────

    /** Moves [player] out of the event world next tick with their current items untouched. */
    private fun eject(player: Player, message: String) {
        comms.send(player, Component.text(message, NamedTextColor.RED), category)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!player.isOnline || !inEventWorld(player)) return@Runnable
            player.teleport(safeOutside() ?: return@Runnable)
        })
    }

    private fun safeOutside(): Location? {
        val event = eventWorldName()
        runCatching { plugin.warpManager.getSpawn() }.getOrNull()?.takeIf { it.world?.name != event }?.let { return it }
        return Bukkit.getWorlds().firstOrNull { it.name != event }?.spawnLocation
    }

    // ── Periodic + shutdown ────────────────────────────────

    /** Every 30s: players whose last reconcile failed. */
    fun retryFailed() {
        if (retry.isEmpty()) return
        for (uuid in retry.toList()) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null) { retry.remove(uuid); continue }
            reconcile(player, inEventWorld(player), "retry")
        }
    }

    /** Shutdown: players still in the event world get their items back before the server saves them. */
    fun restoreForShutdown(tracked: Collection<UUID>) {
        for (uuid in tracked.toList()) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            untrack(uuid)
            reconcile(player, inEventWorld = false, cause = "shutdown")
        }
    }

    // ── Staff ──────────────────────────────────────────────

    private fun notifyReview(player: Player, id: Long, reason: String) {
        plugin.logger.warning("[Event] ${player.name} (${player.uniqueId}): event inventory snapshot $id needs staff review: $reason. See /event inventory info ${player.name}.")
        val msg = Component.text("[Event] ${player.name}'s event inventory snapshot #$id needs review: /event inventory info ${player.name}", NamedTextColor.GOLD)
        for (staff in Bukkit.getOnlinePlayers()) if (staff.hasPermission(PERMISSION)) comms.send(staff, msg, category)
    }

    sealed interface StaffResult {
        data class Ok(val message: String) : StaffResult
        data class Refused(val message: String) : StaffResult
    }

    /**
     * Staff: apply a REVIEW snapshot to its (online, outside the event world) player. The row is
     * claimed REVIEW → RESTORING first, so it can never be applied twice; what the player held is
     * kept on the row.
     */
    fun staffApply(actor: String, id: Long): StaffResult {
        val snap = store.byId(id) ?: return StaffResult.Refused("No event inventory snapshot #$id.")
        if (snap.status != EventInventoryRules.REVIEW) return StaffResult.Refused("Snapshot #$id is ${snap.status}, not REVIEW; only REVIEW snapshots can be applied.")
        if (!snap.hasItems) return StaffResult.Refused("Snapshot #$id holds no items (it only records a marker); use resolve.")
        val player = Bukkit.getPlayer(snap.uuid) ?: return StaffResult.Refused("${snap.name ?: snap.uuid} must be online to apply it.")
        if (inEventWorld(player)) return StaffResult.Refused("${player.name} is in the event world; apply it once they're out.")
        if (!busy.add(player.uniqueId)) return StaffResult.Refused("${player.name}'s inventory is being handled right now; try again.")
        try {
            return when (restore(player, id, EventInventoryRules.REVIEW, "staff:$actor", automatic = false)) {
                StepResult.OK -> StaffResult.Ok("Applied snapshot #$id to ${player.name}. What they held before is kept on the row.")
                StepResult.CONFLICT -> StaffResult.Refused("Snapshot #$id changed meanwhile; nothing was applied.")
                StepResult.FAILED -> StaffResult.Refused("Couldn't apply snapshot #$id; nothing was changed.")
            }
        } catch (e: Exception) {
            return StaffResult.Refused("Couldn't apply snapshot #$id (${e.message}); nothing was changed.")
        } finally {
            busy.remove(player.uniqueId)
        }
    }

    /** Staff: settle a REVIEW snapshot without applying anything (e.g. the player already has their items). */
    fun staffResolve(actor: String, id: Long, note: String): StaffResult {
        val snap = store.byId(id) ?: return StaffResult.Refused("No event inventory snapshot #$id.")
        if (snap.status != EventInventoryRules.REVIEW) return StaffResult.Refused("Snapshot #$id is ${snap.status}, not REVIEW.")
        val ok = store.move(id, EventInventoryRules.REVIEW, EventInventoryRules.RESOLVED, "resolved by staff without applying: $note", "staff:$actor", System.currentTimeMillis(), resolvedBy = actor)
        if (!ok) return StaffResult.Refused("Snapshot #$id changed meanwhile; nothing was done.")
        // An online player whose marker points here: remove it (offline ones are cleared on join).
        Bukkit.getPlayer(snap.uuid)?.let { p ->
            val m = marker(p)
            if (m != null && (m == id || m == snap.markerRef)) {
                p.persistentDataContainer.remove(markerKey)
                savePlayer(p)
            }
        }
        return StaffResult.Ok("Resolved snapshot #$id without applying it.")
    }

    // ── Legacy event_inventories.yml ───────────────────────

    /**
     * Imports the old YAML snapshots once as LEGACY rows (REVIEW when an item couldn't be read), then
     * renames the file (never deleted). A broken file is left in place and nothing is imported.
     */
    private fun migrateLegacyYaml() {
        val file = File(plugin.dataFolder, "event_inventories.yml")
        if (!file.exists()) return
        val yaml = YamlConfiguration()
        try {
            yaml.load(file)
        } catch (e: Exception) {
            plugin.logger.severe("[Event] event_inventories.yml can't be read (${e.message}); it was left untouched and nothing was imported. Fix it and restart.")
            return
        }
        val entries = mutableListOf<EventInventoryStore.LegacyEntry>()
        var unusable = 0
        for (key in yaml.getKeys(false)) {
            val uuid = runCatching { UUID.fromString(key) }.getOrNull()
            val section = yaml.getConfigurationSection(key)
            if (uuid == null || section == null) { unusable++; continue }
            entries += legacyEntry(uuid, section)
        }
        if (unusable > 0) {
            plugin.logger.severe("[Event] event_inventories.yml has $unusable entries that aren't player snapshots; it was left untouched and nothing was imported.")
            return
        }
        val skipped = mutableListOf<UUID>()
        val added = try {
            store.importLegacy(entries, System.currentTimeMillis(), skipped)
        } catch (e: Exception) {
            plugin.logger.severe("[Event] Couldn't import event_inventories.yml (${e.message}); it was left untouched and is tried again next start.")
            return
        }
        if (skipped.isNotEmpty()) {
            plugin.logger.severe("[Event] event_inventories.yml entries for ${skipped.joinToString()} weren't imported (those players already have an open snapshot); the file was left in place for staff.")
            return
        }
        val target = File(plugin.dataFolder, "event_inventories.yml.migrated-${System.currentTimeMillis()}")
        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            try {
                Files.move(file.toPath(), target.toPath())
            } catch (e: Exception) {
                plugin.logger.warning("[Event] Imported event_inventories.yml but couldn't rename it (${e.message}); re-imports are skipped automatically.")
            }
        }
        plugin.logger.info("[Event] Imported $added snapshot(s) from event_inventories.yml (now ${target.name}); each is settled on that player's next join.")
    }

    private fun legacyEntry(uuid: UUID, section: ConfigurationSection): EventInventoryStore.LegacyEntry {
        val problems = mutableListOf<String>()
        fun read(path: String): ItemStack? {
            if (!section.contains(path)) return null
            val stack = section.getItemStack(path)
            if (stack == null && section.get(path) != null) problems += "$path unreadable"
            return stack?.takeIf { !it.type.isAir && it.amount > 0 }
        }
        val inv = mutableMapOf<Int, ItemStack>()
        section.getConfigurationSection("inv")?.getKeys(false)?.forEach { k ->
            val slot = k.toIntOrNull()
            if (slot == null) { problems += "inv.$k is not a slot"; return@forEach }
            read("inv.$k")?.let { inv[slot] = it }
        }
        val slots = EventInventoryRules.legacySlots(inv, read("armor.helmet"), read("armor.chestplate"), read("armor.leggings"), read("armor.boots"), read("armor.offhand"))
        val encoded = slots.mapIndexed { i, s ->
            s?.let {
                try { encode(it) } catch (e: Exception) { problems += "slot $i can't be serialized (${e.message})"; null }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.joinToString(",") { it ?: "" }.toByteArray())
        val key = "yaml:$uuid:" + digest.joinToString("") { "%02x".format(it) }.take(32)
        return EventInventoryStore.LegacyEntry(uuid, key, encoded, section.getString("gamemode") ?: "SURVIVAL", problems)
    }

    companion object {
        /** Staff recovery: `/event inventory`. A child of `joshymc.event` in plugin.yml; never given to hosts. */
        const val PERMISSION = "joshymc.event.inventory.recover"
    }
}
