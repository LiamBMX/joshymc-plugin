package com.liam.joshymc.communityevent.inventory

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.EventPermissions
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.staff.AdminAudit
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Instant
import java.util.UUID

/**
 * Issue #1550: `/event inventory list|info|apply|resolve` for staff with
 * [EventInventoryKeeper.PERMISSION] (or `joshymc.event`, or the console). Read-only except apply
 * (REVIEW only, once: the row is claimed before anything changes) and resolve (no items given).
 * Both re-check staff protection and are written to `admin_logs`.
 */
class EventInventoryCommands(private val plugin: Joshymc, private val keeper: EventInventoryKeeper) {

    private val category = CommunicationsManager.Category.EVENT
    private val store get() = keeper.store

    fun canUse(sender: CommandSender): Boolean =
        sender !is Player || sender.hasPermission(EventInventoryKeeper.PERMISSION) || sender.hasPermission(EventPermissions.ADMIN)

    fun usageLines(sender: CommandSender): List<String> = if (!canUse(sender)) emptyList() else listOf(
        "&e/event inventory list &7- Event inventory snapshots needing attention",
        "&e/event inventory info <player> &7- A player's snapshots and history",
        "&e/event inventory apply|resolve <id> confirm &7- Settle a REVIEW snapshot",
    )

    fun tab(sender: CommandSender, args: Array<out String>): List<String> {
        if (!canUse(sender)) return emptyList()
        return when (args.size) {
            2 -> listOf("list", "info", "apply", "resolve").filter { it.startsWith(args[1].lowercase()) }
            3 -> if (args[1].equals("info", true)) plugin.server.onlinePlayers.map { it.name }.filter { it.lowercase().startsWith(args[2].lowercase()) } else emptyList()
            4 -> if (args[1].lowercase() in setOf("apply", "resolve")) listOf("confirm") else emptyList()
            else -> emptyList()
        }
    }

    fun handle(sender: CommandSender, args: Array<out String>) {
        if (!canUse(sender)) { Msg.noPermission(sender, category); return }
        if (!keeper.ready) { Msg.error(sender, "The event inventory tables aren't available (see the console).", category); return }
        try {
            when (args.getOrNull(1)?.lowercase()) {
                "list" -> list(sender)
                "info" -> info(sender, args.getOrNull(2) ?: run { Msg.usage(sender, "/event inventory info <player|uuid>", category); return })
                "apply", "resolve" -> settle(sender, args)
                else -> usageLines(sender).forEach { sender.sendMessage(plugin.commsManager.parseLegacy(it)) }
            }
        } catch (e: Exception) {
            Msg.error(sender, "The event inventory database couldn't be read: ${e.message}", category)
        }
    }

    private fun line(sender: CommandSender, text: String, color: NamedTextColor = NamedTextColor.GRAY) =
        Msg.reply(sender, Component.text(text, color), category)

    private fun time(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).toString() } ?: "-"

    private fun describe(s: EventInventoryStore.Snapshot) =
        "#${s.id} ${s.status} ${s.name ?: "?"} (${s.uuid}) source=${s.source} items=${if (s.hasItems) s.itemCount.toString() else "none"} " +
            "updated=${time(s.updatedAt)}"

    private fun list(sender: CommandSender) {
        val counts = store.countByStatus()
        line(sender, "Event inventory snapshots: " + EventInventoryRules.STATUSES.joinToString(", ") { "$it ${counts[it] ?: 0}" }, NamedTextColor.GOLD)
        val rows = store.unsettled()
        if (rows.isEmpty()) { line(sender, "Nothing needs attention."); return }
        for (s in rows) {
            line(sender, describe(s), if (s.status == EventInventoryRules.REVIEW) NamedTextColor.YELLOW else NamedTextColor.GRAY)
            if (s.status == EventInventoryRules.REVIEW) s.detail?.let { line(sender, "   $it", NamedTextColor.DARK_GRAY) }
        }
    }

    private fun resolveUuid(input: String): Pair<UUID, String>? {
        runCatching { UUID.fromString(input) }.getOrNull()?.let { return it to input }
        val p = plugin.playerResolver.resolve(input) ?: return null
        return p.uniqueId to (p.name ?: input)
    }

    private fun info(sender: CommandSender, input: String) {
        val (uuid, name) = resolveUuid(input) ?: run { Msg.playerNotFound(sender, input, category); return }
        val online = plugin.server.getPlayer(uuid)
        line(sender, "Event inventory of $name ($uuid)", NamedTextColor.GOLD)
        if (online != null) {
            line(sender, "Online in ${online.world.name}; event marker: ${keeper.marker(online)?.let { "#$it" } ?: "none"}")
        } else {
            line(sender, "Offline (the marker in their player file is checked on join).")
        }
        val rows = store.history(uuid)
        if (rows.isEmpty()) line(sender, "No snapshots.")
        for (s in rows) {
            line(sender, describe(s), if (s.status == EventInventoryRules.REVIEW) NamedTextColor.YELLOW else NamedTextColor.GRAY)
            line(sender, "   created=${time(s.createdAt)} restoreStarted=${time(s.restoreStartedAt)} replacedKept=${s.hasReplaced}" +
                (s.markerRef?.let { " marker=#$it" } ?: "") + (s.resolvedBy?.let { " resolvedBy=$it" } ?: ""), NamedTextColor.DARK_GRAY)
            s.detail?.let { line(sender, "   $it", NamedTextColor.DARK_GRAY) }
        }
        val audit = store.audit(uuid, 5)
        if (audit.isNotEmpty()) line(sender, "Recent changes:", NamedTextColor.GOLD)
        for (a in audit) line(sender, " ${time(a.at)} ${a.action} #${a.snapshotId ?: "-"} by ${a.actor}", NamedTextColor.DARK_GRAY)
    }

    private fun settle(sender: CommandSender, args: Array<out String>) {
        val action = args[1].lowercase()
        val id = args.getOrNull(2)?.removePrefix("#")?.toLongOrNull()
        if (id == null || !args.getOrNull(3).equals("confirm", ignoreCase = true)) {
            Msg.usage(sender, "/event inventory $action <id> confirm${if (action == "resolve") " [note]" else ""}", category)
            if (action == "apply") line(sender, "Apply replaces the player's current inventory with the snapshot (what they hold is kept on the row). Check /event inventory info first.", NamedTextColor.YELLOW)
            return
        }
        val snap = store.byId(id) ?: run { Msg.error(sender, "No event inventory snapshot #$id.", category); return }
        val auditAction = if (action == "apply") "EVENT_INVENTORY_APPLY" else "EVENT_INVENTORY_RESOLVE"
        plugin.staffRankManager.protectionProblem(sender, snap.uuid)?.let { problem ->
            AdminAudit.log(plugin, sender, "${auditAction}_REFUSED", snap.uuid, snap.name, "snapshot=$id reason=$problem")
            Msg.error(sender, problem, category)
            return
        }
        val note = args.drop(4).joinToString(" ").ifBlank { "no note" }
        val result = if (action == "apply") keeper.staffApply(sender.name, id) else keeper.staffResolve(sender.name, id, note)
        when (result) {
            is EventInventoryKeeper.StaffResult.Ok -> {
                AdminAudit.log(plugin, sender, auditAction, snap.uuid, snap.name, "snapshot=$id items=${snap.itemCount} note=$note")
                Msg.success(sender, result.message, category)
            }
            is EventInventoryKeeper.StaffResult.Refused -> {
                AdminAudit.log(plugin, sender, "${auditAction}_REFUSED", snap.uuid, snap.name, "snapshot=$id reason=${result.message}")
                Msg.error(sender, result.message, category)
            }
        }
    }
}
