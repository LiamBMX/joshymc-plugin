package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.PurchaseDeliveryStore
import com.liam.joshymc.manager.PurchaseFulfillmentManager.FulfillResult
import com.liam.joshymc.manager.PurchaseDeliveryStore.State
import com.liam.joshymc.manager.PurchaseRewards
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.text.SimpleDateFormat
import java.util.Date

/**
 * `/purchase` (issue #1175): the store fulfillment entry point and its recovery tools.
 *
 * - `fulfill <player|uuid> <type> <args...> [reference]`: console only. This is what the
 *   store's package commands should call. See [PurchaseRewards.Kind] for the types.
 * - `list [open|pending|failed|interrupted|unresolved|rejected|completed|all|<player>]`,
 *   `info <id>`: `joshymc.purchase.admin`.
 * - `retry <id> [confirm]`, `cancel <id>`, `assign <id> <player|uuid>`: operators and
 *   the console only. None of them can grant a COMPLETED row again.
 */
class PurchaseCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val category = CommunicationsManager.Category.ADMIN
    private val manager get() = plugin.purchaseFulfillmentManager

    private fun isConsole(sender: CommandSender) = sender is ConsoleCommandSender || sender is RemoteConsoleCommandSender
    private fun canManage(sender: CommandSender) = isConsole(sender) || (sender is Player && sender.isOp && sender.hasPermission(PERMISSION))
    private fun canView(sender: CommandSender) = isConsole(sender) || sender.hasPermission(PERMISSION)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.getOrNull(0)?.lowercase()
        val rest = args.drop(1)
        when (sub) {
            "fulfill" -> fulfill(sender, rest)
            "list" -> if (canView(sender)) list(sender, rest) else Msg.noPermission(sender, category)
            "info" -> if (canView(sender)) info(sender, rest) else Msg.noPermission(sender, category)
            "retry" -> if (canManage(sender)) retry(sender, rest) else Msg.noPermission(sender, category)
            "cancel" -> if (canManage(sender)) cancel(sender, rest) else Msg.noPermission(sender, category)
            "assign" -> if (canManage(sender)) assign(sender, rest) else Msg.noPermission(sender, category)
            else -> if (canView(sender)) usage(sender) else Msg.noPermission(sender, category)
        }
        return true
    }

    private fun usage(sender: CommandSender) {
        Msg.usage(sender, "/purchase fulfill <player|uuid> <type> <args...> [reference]  (console only)", category)
        Msg.usage(sender, "/purchase list [open|pending|failed|interrupted|unresolved|rejected|completed|all|<player>]", category)
        Msg.usage(sender, "/purchase info <id> | retry <id> [confirm] | cancel <id> | assign <id> <player|uuid>", category)
        Msg.reply(sender, Component.text("Types: ${PurchaseRewards.Kind.entries.joinToString(", ") { it.usage }}", NamedTextColor.GRAY), category)
    }

    private fun fulfill(sender: CommandSender, args: List<String>) {
        // Only the console (where the store plugin runs its commands) may grant purchases.
        if (!isConsole(sender)) {
            plugin.logger.warning("[Purchases] ${sender.name} tried to run /purchase fulfill ${args.joinToString(" ")}; refused (console only).")
            Msg.error(sender, "/purchase fulfill can only be run from the server console.", category)
            return
        }
        if (args.size < 2) {
            Msg.usage(sender, "/purchase fulfill <player|uuid> <type> <args...> [reference]", category)
            return
        }
        when (val result = manager.fulfill(args[0], args.drop(1), sender.name)) {
            is FulfillResult.Recorded -> {
                val d = result.delivery
                val outcome = when (d.state) {
                    State.COMPLETED -> "delivered"
                    State.PENDING -> when {
                        d.kind?.delivery == PurchaseRewards.Delivery.CURRENCY -> "queued until ${d.recipientName} first joins this server"
                        d.remaining < d.quantity -> "${d.quantity - d.remaining} delivered, ${d.remaining} queued until ${d.recipientName} has inventory space"
                        else -> "queued until ${d.recipientName} is online with inventory space"
                    }
                    State.PROCESSING -> "waiting for LuckPerms to confirm"
                    else -> d.state.name.lowercase() + (d.lastError?.let { ": $it" } ?: "")
                }
                Msg.success(sender, "Purchase #${d.id} (${d.describe()} for ${d.recipientName}): $outcome.", category)
            }
            is FulfillResult.Duplicate -> Msg.reply(sender, Component.text(
                "Duplicate ignored: reference ${result.existing.externalRef} is already delivery #${result.existing.id} (${result.existing.state.name}).",
                NamedTextColor.YELLOW
            ), category)
            is FulfillResult.Unresolved -> Msg.error(sender,
                "Purchase #${result.delivery.id} is held: '${result.delivery.recipientName}' has never joined. Use /purchase assign ${result.delivery.id} <player|uuid>.", category)
            is FulfillResult.Rejected -> Msg.error(sender,
                "Purchase refused: ${result.reason}" + (result.id?.let { " Logged as #$it." } ?: ""), category)
            is FulfillResult.DatabaseError -> Msg.error(sender,
                "Purchase NOT recorded (database error: ${result.message}). Nothing was granted; grant it by hand.", category)
        }
    }

    private fun list(sender: CommandSender, args: List<String>) {
        val filter = args.getOrNull(0)?.lowercase() ?: "open"
        val states: Collection<State>? = when (filter) {
            "open" -> OPEN_STATES
            "all" -> null
            else -> State.entries.firstOrNull { it.name.equals(filter, ignoreCase = true) }?.let { listOf(it) }
        }
        val recipient = if (states == null && filter != "all") {
            manager.resolvePlayer(args[0])?.first ?: run {
                Msg.playerNotFound(sender, args[0], category)
                return
            }
        } else null

        val rows = manager.store.list(states, recipient, LIST_LIMIT)
        if (rows.isEmpty()) {
            Msg.reply(sender, Component.text("No store deliveries match '$filter'.", NamedTextColor.GRAY), category)
            return
        }
        Msg.reply(sender, Component.text("Store deliveries ($filter, newest ${rows.size}):", NamedTextColor.GOLD), category)
        for (d in rows) {
            Msg.reply(sender, Component.text("#${d.id} ", NamedTextColor.WHITE)
                .append(Component.text(d.state.name, stateColor(d.state)))
                .append(Component.text(" ${d.describe()}", NamedTextColor.AQUA))
                .append(Component.text(if (d.remaining in 1 until d.quantity) " (${d.remaining} left)" else "", NamedTextColor.YELLOW))
                .append(Component.text(" → ${d.recipientName} · ${DATE.format(Date(d.createdAt))}", NamedTextColor.GRAY))
                .append(Component.text(d.lastError?.let { " · $it" } ?: "", NamedTextColor.RED)), category)
        }
    }

    private fun info(sender: CommandSender, args: List<String>) {
        val d = args.getOrNull(0)?.toIntOrNull()?.let { manager.store.get(it) } ?: run {
            Msg.usage(sender, "/purchase info <id>", category)
            return
        }
        fun line(k: String, v: String) = Msg.reply(sender, Component.text("  $k: ", NamedTextColor.GRAY).append(Component.text(v, NamedTextColor.WHITE)), category)
        Msg.reply(sender, Component.text("Store delivery #${d.id}", NamedTextColor.GOLD), category)
        line("State", d.state.name)
        line("Recipient", "${d.recipientName} (${d.recipientUuid ?: "unresolved"})")
        line("Reward", d.describe())
        line("Remaining", "${d.remaining} of ${d.quantity}")
        line("Reference", d.externalRef ?: "none (no duplicate protection)")
        line("Source", d.source)
        line("Created", DATE.format(Date(d.createdAt)))
        line("Updated", DATE.format(Date(d.updatedAt)))
        d.completedAt?.let { line("Completed", DATE.format(Date(it))) }
        line("Attempts", d.attempts.toString())
        d.lastError?.let { line("Last error", it) }
        if (d.state == State.PENDING && d.recipientUuid != null && plugin.server.getPlayer(d.recipientUuid) == null) {
            line("Waiting for", if (d.kind?.delivery == PurchaseRewards.Delivery.CURRENCY) "the recipient's first join on this server" else "the recipient to come online")
        }
    }

    private fun retry(sender: CommandSender, args: List<String>) {
        val id = args.getOrNull(0)?.toIntOrNull()
        val d = id?.let { manager.store.get(it) } ?: run {
            Msg.usage(sender, "/purchase retry <id> [confirm]", category)
            return
        }
        when (d.state) {
            State.FAILED -> Unit
            State.INTERRUPTED -> if (!args.getOrNull(1).equals("confirm", ignoreCase = true)) {
                Msg.error(sender, "#${d.id} was mid-delivery when the server stopped, so ${d.recipientName} may already have " +
                    "some of its ${d.remaining} item(s). Check their inventory first, then run /purchase retry ${d.id} confirm.", category)
                return
            }
            else -> {
                Msg.error(sender, "#${d.id} is ${d.state.name}; only FAILED or INTERRUPTED deliveries can be retried.", category)
                return
            }
        }
        val after = manager.retry(d.id) ?: run {
            Msg.error(sender, "#${d.id} could not be requeued (its state changed, or it has no recipient).", category)
            return
        }
        audit(sender, "PURCHASE_RETRY", after, "from=${d.state.name} remaining=${d.remaining}")
        Msg.success(sender, "#${d.id} requeued; now ${after.state.name}.", category)
    }

    private fun cancel(sender: CommandSender, args: List<String>) {
        val d = args.getOrNull(0)?.toIntOrNull()?.let { manager.store.get(it) } ?: run {
            Msg.usage(sender, "/purchase cancel <id>", category)
            return
        }
        if (!manager.cancel(d.id, "cancelled by ${sender.name}")) {
            Msg.error(sender, "#${d.id} is ${d.state.name} and can't be cancelled.", category)
            return
        }
        audit(sender, "PURCHASE_CANCEL", d, "from=${d.state.name} remaining=${d.remaining}")
        plugin.logger.info("[Purchases] #${d.id} CANCELLED by ${sender.name} (${d.describe()}, ${d.remaining} undelivered, was ${d.state.name}).")
        Msg.success(sender, "#${d.id} cancelled. ${d.remaining} undelivered unit(s) will not be sent.", category)
    }

    private fun assign(sender: CommandSender, args: List<String>) {
        val d = args.getOrNull(0)?.toIntOrNull()?.let { manager.store.get(it) }
        val target = args.getOrNull(1)
        if (d == null || target == null) {
            Msg.usage(sender, "/purchase assign <id> <player|uuid>", category)
            return
        }
        if (d.state != State.UNRESOLVED) {
            Msg.error(sender, "#${d.id} is ${d.state.name}; only UNRESOLVED deliveries can be assigned.", category)
            return
        }
        val (uuid, name) = manager.resolvePlayer(target) ?: run {
            Msg.playerNotFound(sender, target, category)
            return
        }
        val after = manager.assign(d.id, uuid, name) ?: run {
            Msg.error(sender, "#${d.id} could not be assigned (it changed, or the same reference is already recorded for $name).", category)
            return
        }
        audit(sender, "PURCHASE_ASSIGN", after, "store_name=${d.recipientName}")
        plugin.logger.info("[Purchases] #${d.id} assigned by ${sender.name} to $name ($uuid) (store name '${d.recipientName}').")
        Msg.success(sender, "#${d.id} assigned to $name; now ${after.state.name}.", category)
    }

    private fun audit(sender: CommandSender, action: String, d: PurchaseDeliveryStore.Delivery, extra: String) {
        try {
            plugin.adminManager.logAction(
                (sender as? Player)?.uniqueId, sender.name, action, d.recipientUuid, d.recipientName,
                "delivery=${d.id} reward=${d.describe()} ref=${d.externalRef ?: "none"} $extra"
            )
        } catch (e: Exception) {
            plugin.logger.warning("[Purchases] Could not write admin log for $action #${d.id}: ${e.message}")
        }
    }

    private fun stateColor(state: State) = when (state) {
        State.COMPLETED -> NamedTextColor.GREEN
        State.PENDING, State.PROCESSING -> NamedTextColor.YELLOW
        State.CANCELLED -> NamedTextColor.DARK_GRAY
        else -> NamedTextColor.RED
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!canView(sender)) return emptyList()
        return when (args.size) {
            1 -> buildList {
                if (isConsole(sender)) add("fulfill")
                addAll(listOf("list", "info"))
                if (canManage(sender)) addAll(listOf("retry", "cancel", "assign"))
            }.filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].equals("list", ignoreCase = true)) {
                (listOf("open", "all") + State.entries.map { it.name.lowercase() }).filter { it.startsWith(args[1].lowercase()) }
            } else emptyList()
            3 -> if (args[0].equals("fulfill", ignoreCase = true)) {
                PurchaseRewards.Kind.entries.map { it.id }.filter { it.startsWith(args[2].lowercase()) }
            } else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        const val PERMISSION = "joshymc.purchase.admin"
        private const val LIST_LIMIT = 20
        private val OPEN_STATES = listOf(State.PENDING, State.PROCESSING, State.FAILED, State.INTERRUPTED, State.UNRESOLVED, State.REJECTED)
        private val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm")
    }
}
