package com.liam.joshymc.staff

import com.liam.joshymc.Joshymc
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/**
 * One place for audit rows of sensitive staff actions (issue #1383): currency/item generation,
 * crate keys, vouchers, rank changes, punishment reversals. Writes the existing `admin_logs`
 * table (`/admin logs`) plus a console line. Staff UUID (or `CONSOLE`), target UUID and name,
 * action, values and the timestamp are recorded; never secrets. A failed write is logged and
 * never breaks the action that called it.
 */
object AdminAudit {
    fun log(
        plugin: Joshymc,
        sender: CommandSender,
        action: String,
        targetUuid: UUID? = null,
        targetName: String? = null,
        details: String? = null
    ) {
        val actorUuid = (sender as? Player)?.uniqueId
        val full = listOfNotNull(details, targetUuid?.let { "target_uuid=$it" }).joinToString(" ").ifEmpty { null }
        try {
            plugin.adminManager.logAction(actorUuid, sender.name, action, targetUuid, targetName, full)
        } catch (e: Exception) {
            plugin.logger.warning("[AdminAudit] Could not store $action by ${sender.name}: ${e.message}")
        }
        plugin.logger.info(
            "[AdminAudit] ${sender.name}${actorUuid?.let { " ($it)" } ?: ""} $action" +
                (targetName?.let { " -> $it" } ?: "") + (full?.let { " | $it" } ?: "")
        )
    }
}
