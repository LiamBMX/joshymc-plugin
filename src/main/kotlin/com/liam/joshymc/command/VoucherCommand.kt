package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/voucher` — configures the virtual voucher packages listed inside the
 * Credit Shop (see [com.liam.joshymc.manager.VoucherManager]). Gated behind
 * `joshymc.vouchers`; redeeming a virtual voucher requires no permission and
 * only ever happens by clicking it in `/cshop` — this command never redeems
 * those. `/voucher give` is the one exception: it hands out physical money
 * voucher items (see [com.liam.joshymc.manager.PhysicalVoucherManager]),
 * still gated behind `joshymc.vouchers` so only admins can create them.
 */
class VoucherCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.vouchers")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "create" -> handleCreate(sender, args)
            "delete" -> handleDelete(sender, args)
            "enable" -> handleSetEnabled(sender, args, true)
            "disable" -> handleSetEnabled(sender, args, false)
            "setname" -> handleSetName(sender, args)
            "setcategory" -> handleSetCategory(sender, args)
            "setprice" -> handleSetPrice(sender, args)
            "setrank" -> handleSetRank(sender, args)
            "setcommand" -> handleSetCommand(sender, args)
            "setdescription" -> handleSetDescription(sender, args)
            "seticon" -> handleSetIcon(sender, args)
            "list" -> handleList(sender, args)
            "info" -> handleInfo(sender, args)
            "reload" -> handleReload(sender)
            "give" -> handleGive(sender, args)
            else -> sendUsage(sender)
        }

        return true
    }

    private fun handleCreate(sender: CommandSender, args: Array<out String>) {
        if (args.size < 5) {
            sender.sendMessage(Component.text("Usage: /voucher create <id> <price> <category> <rewardCommand...>", NamedTextColor.RED))
            return
        }

        val id = args[1].lowercase()
        val price = args[2].toDoubleOrNull()
        if (price == null || price <= 0) {
            sender.sendMessage(Component.text("Invalid price.", NamedTextColor.RED))
            return
        }
        val category = args[3]
        val rewardCommand = args.drop(4).joinToString(" ")

        val voucher = plugin.voucherManager.createVoucher(id, price, category, rewardCommand)
        if (voucher == null) {
            sender.sendMessage(Component.text("A voucher with id '$id' already exists.", NamedTextColor.RED))
            return
        }

        sender.sendMessage(
            Component.text("Created voucher ", NamedTextColor.GREEN)
                .append(Component.text(voucher.id, NamedTextColor.WHITE))
                .append(Component.text(" in ", NamedTextColor.GREEN))
                .append(Component.text(category, NamedTextColor.WHITE))
                .append(Component.text(" for ${plugin.creditsManager.format(price)} credits.", NamedTextColor.GREEN))
        )
        sender.sendMessage(Component.text("Optionally set a display name, rank link, description, or icon with /voucher setname|setrank|setdescription|seticon.", NamedTextColor.GRAY))
    }

    private fun handleDelete(sender: CommandSender, args: Array<out String>) {
        val id = args.getOrNull(1)?.lowercase()
        if (id == null) {
            sender.sendMessage(Component.text("Usage: /voucher delete <id>", NamedTextColor.RED))
            return
        }
        if (plugin.voucherManager.deleteVoucher(id)) {
            sender.sendMessage(Component.text("Deleted voucher $id.", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("No voucher found with id $id.", NamedTextColor.RED))
        }
    }

    private fun handleSetEnabled(sender: CommandSender, args: Array<out String>, enabled: Boolean) {
        val id = args.getOrNull(1)?.lowercase()
        if (id == null) {
            sender.sendMessage(Component.text("Usage: /voucher ${args[0].lowercase()} <id>", NamedTextColor.RED))
            return
        }
        if (plugin.voucherManager.setEnabled(id, enabled)) {
            sender.sendMessage(Component.text("Voucher $id is now ${if (enabled) "enabled" else "disabled"}.", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("No voucher found with id $id.", NamedTextColor.RED))
        }
    }

    private fun handleSetName(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /voucher setname <id> <name...>", NamedTextColor.RED))
            return
        }
        val id = args[1].lowercase()
        val name = args.drop(2).joinToString(" ")
        if (plugin.voucherManager.setName(id, name)) {
            sender.sendMessage(Component.text("Updated display name for $id.", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("No voucher found with id $id.", NamedTextColor.RED))
        }
    }

    private fun handleSetCategory(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /voucher setcategory <id> <category...>", NamedTextColor.RED))
            return
        }
        val id = args[1].lowercase()
        val category = args.drop(2).joinToString(" ")
        if (plugin.voucherManager.setCategory(id, category)) {
            sender.sendMessage(Component.text("Moved $id to $category.", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("No voucher found with id $id.", NamedTextColor.RED))
        }
    }

    private fun handleSetPrice(sender: CommandSender, args: Array<out String>) {
        val id = args.getOrNull(1)?.lowercase()
        val price = args.getOrNull(2)?.toDoubleOrNull()
        if (id == null || price == null || price <= 0) {
            sender.sendMessage(Component.text("Usage: /voucher setprice <id> <price>", NamedTextColor.RED))
            return
        }
        if (plugin.voucherManager.setPrice(id, price)) {
            sender.sendMessage(Component.text("Updated price for $id to ${plugin.creditsManager.format(price)} credits.", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("No voucher found with id $id.", NamedTextColor.RED))
        }
    }

    private fun handleSetRank(sender: CommandSender, args: Array<out String>) {
        val id = args.getOrNull(1)?.lowercase()
        val rankArg = args.getOrNull(2)?.lowercase()
        if (id == null || rankArg == null) {
            sender.sendMessage(Component.text("Usage: /voucher setrank <id> <rankId|none>", NamedTextColor.RED))
            return
        }
        if (plugin.voucherManager.getVoucher(id) == null) {
            sender.sendMessage(Component.text("No voucher found with id $id.", NamedTextColor.RED))
            return
        }

        val rankId = if (rankArg == "none") null else rankArg
        if (rankId != null && plugin.rankManager.getRank(rankId) == null) {
            sender.sendMessage(Component.text("Unknown rank: $rankId. Use /rank list to see available ranks.", NamedTextColor.RED))
            return
        }

        plugin.voucherManager.setRank(id, rankId)
        sender.sendMessage(
            if (rankId == null) Component.text("Unlinked $id from any rank.", NamedTextColor.GREEN)
            else Component.text("Linked $id to the $rankId rank (ownership/downgrade checks now apply).", NamedTextColor.GREEN)
        )
    }

    private fun handleSetCommand(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /voucher setcommand <id> <command...>", NamedTextColor.RED))
            return
        }
        val id = args[1].lowercase()
        val rewardCommand = args.drop(2).joinToString(" ")
        if (plugin.voucherManager.setRewardCommand(id, rewardCommand)) {
            sender.sendMessage(Component.text("Updated reward command for $id.", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("No voucher found with id $id.", NamedTextColor.RED))
        }
    }

    private fun handleSetDescription(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /voucher setdescription <id> <description...>", NamedTextColor.RED))
            return
        }
        val id = args[1].lowercase()
        val description = args.drop(2).joinToString(" ")
        if (plugin.voucherManager.setDescription(id, description)) {
            sender.sendMessage(Component.text("Updated description for $id.", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("No voucher found with id $id.", NamedTextColor.RED))
        }
    }

    private fun handleSetIcon(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only (hold the icon item).", NamedTextColor.RED))
            return
        }
        val id = args.getOrNull(1)?.lowercase()
        if (id == null) {
            sender.sendMessage(Component.text("Usage: /voucher seticon <id> (hold the item)", NamedTextColor.RED))
            return
        }
        val held = sender.inventory.itemInMainHand
        if (held.type == Material.AIR) {
            sender.sendMessage(Component.text("Hold the item you want to use as the icon.", NamedTextColor.RED))
            return
        }
        if (plugin.voucherManager.setIcon(id, held.type)) {
            sender.sendMessage(Component.text("Updated icon for $id to ${held.type.name}.", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("No voucher found with id $id.", NamedTextColor.RED))
        }
    }

    private fun handleList(sender: CommandSender, args: Array<out String>) {
        val vouchers = if (args.size >= 2) {
            val categoryId = plugin.creditShopManager.slugify(args[1])
            plugin.voucherManager.getAllVouchers().filter { it.categoryId == categoryId }
        } else {
            plugin.voucherManager.getAllVouchers()
        }

        if (vouchers.isEmpty()) {
            sender.sendMessage(Component.text("No vouchers found.", NamedTextColor.RED))
            return
        }

        for (voucher in vouchers) {
            val state = if (voucher.enabled) Component.text("enabled", NamedTextColor.GREEN) else Component.text("disabled", NamedTextColor.RED)
            sender.sendMessage(
                Component.text("${voucher.id} ", NamedTextColor.WHITE)
                    .append(Component.text("(${voucher.categoryId}) - ${plugin.creditsManager.format(voucher.price)} credits - ", NamedTextColor.GRAY))
                    .append(state)
            )
        }
    }

    private fun handleInfo(sender: CommandSender, args: Array<out String>) {
        val id = args.getOrNull(1)?.lowercase()
        val voucher = id?.let { plugin.voucherManager.getVoucher(it) }
        if (voucher == null) {
            sender.sendMessage(Component.text("Usage: /voucher info <id>", NamedTextColor.RED))
            return
        }

        sender.sendMessage(Component.text("Voucher: ${voucher.id}", NamedTextColor.LIGHT_PURPLE))
        sender.sendMessage(Component.text("  Name: ${voucher.name}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  Category: ${voucher.categoryId}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  Price: ${plugin.creditsManager.format(voucher.price)} credits", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  Rank: ${voucher.rankId ?: "none"}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  Command: ${voucher.rewardCommand}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  Enabled: ${voucher.enabled}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  Description: ${voucher.description}", NamedTextColor.GRAY))
    }

    private fun handleGive(sender: CommandSender, args: Array<out String>) {
        val targetName = args.getOrNull(1)
        val voucherId = args.getOrNull(2)?.lowercase()
        if (targetName == null || voucherId == null) {
            sender.sendMessage(Component.text("Usage: /voucher give <player> <voucher-id> [amount]", NamedTextColor.RED))
            return
        }

        val target = Bukkit.getPlayerExact(targetName)
        if (target == null) {
            sender.sendMessage(Component.text("Player '$targetName' is not online.", NamedTextColor.RED))
            return
        }

        val amount = args.getOrNull(3)?.toIntOrNull() ?: 1
        if (amount <= 0) {
            sender.sendMessage(Component.text("Amount must be a positive number.", NamedTextColor.RED))
            return
        }

        val voucher = plugin.physicalVoucherManager.getVoucher(voucherId)
        if (voucher == null) {
            sender.sendMessage(Component.text("No physical voucher found with id $voucherId.", NamedTextColor.RED))
            return
        }

        plugin.physicalVoucherManager.give(target, voucherId, amount)
        val voucherName = plugin.commsManager.parseLegacy(voucher.displayName)
        sender.sendMessage(
            Component.text("Gave ", NamedTextColor.GREEN)
                .append(Component.text("${target.name} ", NamedTextColor.WHITE))
                .append(Component.text("${amount}x ", NamedTextColor.AQUA))
                .append(voucherName)
                .append(Component.text(".", NamedTextColor.GREEN))
        )
        if (sender != target) {
            plugin.commsManager.send(
                target,
                Component.text("You received ", NamedTextColor.GREEN)
                    .append(Component.text("${amount}x ", NamedTextColor.AQUA))
                    .append(voucherName)
                    .append(Component.text(" from an admin.", NamedTextColor.GREEN)),
                com.liam.joshymc.manager.CommunicationsManager.Category.ECONOMY
            )
        }
    }

    private fun handleReload(sender: CommandSender) {
        // Voucher data is read live from the database on every access — there is
        // no in-memory cache to reload, so this just confirms the current state.
        val count = plugin.voucherManager.getAllVouchers().size
        sender.sendMessage(Component.text("Vouchers are loaded live from the database. $count voucher(s) currently configured.", NamedTextColor.GREEN))
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("Usage: /voucher <create|delete|enable|disable|setname|setcategory|setprice|setrank|setcommand|setdescription|seticon|list|info|reload|give> [args...]", NamedTextColor.RED))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.vouchers")) return emptyList()

        val subcommands = listOf(
            "create", "delete", "enable", "disable", "setname", "setcategory", "setprice",
            "setrank", "setcommand", "setdescription", "seticon", "list", "info", "reload", "give"
        )

        return when (args.size) {
            1 -> subcommands.filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "delete", "enable", "disable", "setname", "setcategory", "setprice",
                "setrank", "setcommand", "setdescription", "seticon", "info" ->
                    plugin.voucherManager.getAllVouchers().map { it.id }.filter { it.startsWith(args[1].lowercase()) }
                "list" -> plugin.creditShopManager.getCategories().map { it.id }.filter { it.startsWith(args[1].lowercase()) }
                "give" -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "setrank" -> (plugin.rankManager.getRankIds() + "none").filter { it.startsWith(args[2].lowercase()) }
                "give" -> plugin.physicalVoucherManager.getEnabledVoucherIds().filter { it.startsWith(args[2].lowercase()) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
