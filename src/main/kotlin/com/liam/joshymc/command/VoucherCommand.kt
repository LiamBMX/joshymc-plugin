package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.ChatTagManager
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
            "tag" -> handleTag(sender, args)
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
        if (args.size < 3) {
            sendCreateUsage(sender)
            return
        }

        val id = args[1].lowercase()
        val price = args[2].toDoubleOrNull()

        // `/voucher create <id> <chat tag display...>` — args[2] isn't a price, so
        // this is a physical Chat Tag voucher definition instead (issue #597).
        if (price == null) {
            handleCreateChatTagVoucher(sender, id, args.drop(2).joinToString(" "))
            return
        }

        if (price <= 0) {
            sender.sendMessage(Component.text("Invalid price.", NamedTextColor.RED))
            return
        }
        if (args.size < 5) {
            sendCreateUsage(sender)
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

    private fun handleCreateChatTagVoucher(sender: CommandSender, id: String, display: String) {
        if (display.isBlank()) {
            sendCreateUsage(sender)
            return
        }

        val tag = plugin.chatTagManager.createVoucherTag(id, display)
        if (tag == null) {
            sender.sendMessage(Component.text("Could not create Chat Tag voucher '$id' — the id is invalid or already in use.", NamedTextColor.RED))
            return
        }

        val tagDisplay = plugin.commsManager.parseLegacy(tag.display.trim())
        sender.sendMessage(
            Component.text("Created Chat Tag voucher ", NamedTextColor.GREEN)
                .append(Component.text("$CHAT_TAG_VOUCHER_PREFIX${tag.id} ", NamedTextColor.WHITE))
                .append(Component.text("for tag ", NamedTextColor.GREEN))
                .append(tagDisplay)
        )
        sender.sendMessage(Component.text("Give it with /voucher give <player> $CHAT_TAG_VOUCHER_PREFIX${tag.id} <amount>", NamedTextColor.GRAY))
    }

    private fun sendCreateUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("Usage: /voucher create <id> <price> <category> <rewardCommand...>", NamedTextColor.RED))
        sender.sendMessage(Component.text("   or: /voucher create <id> <chat tag display...> (e.g. &5[Haunted])", NamedTextColor.RED))
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

    private fun handleTag(sender: CommandSender, args: Array<out String>) {
        when (args.getOrNull(1)?.lowercase()) {
            "delete" -> handleTagDelete(sender, args)
            "debug" -> handleTagDebug(sender, args)
            else -> sender.sendMessage(Component.text("Usage: /voucher tag <delete|debug> <name>", NamedTextColor.RED))
        }
    }

    /**
     * Diagnostic for issue #624 — prints every value [ChatTagManager.canUse] consults for
     * a Special Chat Tag so a live "owned but can't equip" report can be root-caused from
     * a single command instead of re-reading source. Optional [player] arg lets staff check
     * someone else (or an offline UUID isn't needed here since the target must be online to
     * read live permission state).
     */
    private fun handleTagDebug(sender: CommandSender, args: Array<out String>) {
        val id = args.getOrNull(2)?.lowercase()
        if (id == null) {
            sender.sendMessage(Component.text("Usage: /voucher tag debug <name> [player]", NamedTextColor.RED))
            return
        }

        val target: Player? = args.getOrNull(3)?.let { Bukkit.getPlayerExact(it) } ?: (sender as? Player)
        if (target == null) {
            sender.sendMessage(Component.text("Usage: /voucher tag debug <name> <player> (console must specify a player).", NamedTextColor.RED))
            return
        }

        val tag = plugin.chatTagManager.getTag(id)
        if (tag == null) {
            sender.sendMessage(Component.text("No Chat Tag with id '$id' is currently loaded.", NamedTextColor.RED))
            return
        }

        val hasPerm = tag.permission?.let { target.hasPermission(it) } ?: false
        val hasUnlocked = plugin.chatTagManager.hasUnlocked(target.uniqueId, tag.id)
        val canUse = plugin.chatTagManager.canUse(target, tag)

        sender.sendMessage(Component.text("── Chat Tag debug: $id ──", NamedTextColor.LIGHT_PURPLE))
        sender.sendMessage(Component.text("  player: ${target.name} (${target.uniqueId})", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  tag.id: ${tag.id}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  tag.category: ${tag.category} (isVoucherTag=${plugin.chatTagManager.isVoucherTag(tag)})", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  tag.permission: ${tag.permission ?: "none"}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  hasPermission(tag.permission): $hasPerm", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  hasUnlocked (chat_tag_unlocks row): $hasUnlocked", NamedTextColor.GRAY))
        sender.sendMessage(
            Component.text("  canUse() result: ", NamedTextColor.GRAY)
                .append(Component.text(canUse, if (canUse) NamedTextColor.GREEN else NamedTextColor.RED))
        )
        plugin.logger.info("[ChatTags] /voucher tag debug $id for ${target.name}: tag.permission=${tag.permission}, hasPermission=$hasPerm, hasUnlocked=$hasUnlocked, canUse=$canUse")
    }

    private fun handleTagDelete(sender: CommandSender, args: Array<out String>) {
        val id = args.getOrNull(2)?.lowercase()
        if (id == null) {
            sender.sendMessage(Component.text("Usage: /voucher tag delete <name>", NamedTextColor.RED))
            return
        }

        val tag = plugin.chatTagManager.getTag(id)
        if (tag == null) {
            sender.sendMessage(Component.text("Custom Chat Tag '$id' does not exist.", NamedTextColor.RED))
            return
        }
        if (!plugin.chatTagManager.isVoucherTag(tag)) {
            sender.sendMessage(Component.text("That Chat Tag cannot be deleted with this command.", NamedTextColor.RED))
            return
        }

        val confirmed = args.getOrNull(3)?.equals("confirm", ignoreCase = true) == true
        if (!confirmed) {
            val tagDisplay = plugin.commsManager.parseLegacy(tag.display.trim())
            sender.sendMessage(
                Component.text("Are you sure you want to delete ", NamedTextColor.YELLOW)
                    .append(tagDisplay)
                    .append(Component.text("? Run ", NamedTextColor.YELLOW))
                    .append(Component.text("/voucher tag delete $id confirm", NamedTextColor.WHITE))
                    .append(Component.text(" to confirm.", NamedTextColor.YELLOW))
            )
            return
        }

        when (plugin.chatTagManager.deleteVoucherTag(id)) {
            ChatTagManager.DeleteVoucherTagResult.DELETED ->
                sender.sendMessage(Component.text("Deleted custom Chat Tag '$id' and its voucher.", NamedTextColor.GREEN))
            ChatTagManager.DeleteVoucherTagResult.NOT_VOUCHER_TAG ->
                sender.sendMessage(Component.text("That Chat Tag cannot be deleted with this command.", NamedTextColor.RED))
            ChatTagManager.DeleteVoucherTagResult.NOT_FOUND ->
                sender.sendMessage(Component.text("Custom Chat Tag '$id' does not exist.", NamedTextColor.RED))
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

        if (voucherId.startsWith(CHAT_TAG_VOUCHER_PREFIX)) {
            handleGiveChatTagVoucher(sender, target, voucherId.removePrefix(CHAT_TAG_VOUCHER_PREFIX), amount)
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

    private fun handleGiveChatTagVoucher(sender: CommandSender, target: Player, tagId: String, amount: Int) {
        val tag = plugin.chatTagManager.getVoucherTag(tagId)
        if (tag == null) {
            sender.sendMessage(Component.text("No Chat Tag voucher found with id $CHAT_TAG_VOUCHER_PREFIX$tagId.", NamedTextColor.RED))
            return
        }

        plugin.chatTagVoucherManager.give(target, tag.id, amount)
        val tagDisplay = plugin.commsManager.parseLegacy(tag.display.trim())
        sender.sendMessage(
            Component.text("Gave ", NamedTextColor.GREEN)
                .append(Component.text("${target.name} ", NamedTextColor.WHITE))
                .append(Component.text("${amount}x ", NamedTextColor.AQUA))
                .append(tagDisplay)
                .append(Component.text(" Chat Tag Voucher(s).", NamedTextColor.GREEN))
        )
        if (sender != target) {
            plugin.commsManager.send(
                target,
                Component.text("You received ", NamedTextColor.GREEN)
                    .append(Component.text("${amount}x ", NamedTextColor.AQUA))
                    .append(tagDisplay)
                    .append(Component.text(" Chat Tag Voucher(s) from an admin.", NamedTextColor.GREEN)),
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
        sender.sendMessage(Component.text("Usage: /voucher <create|delete|tag|enable|disable|setname|setcategory|setprice|setrank|setcommand|setdescription|seticon|list|info|reload|give> [args...]", NamedTextColor.RED))
        sender.sendMessage(Component.text("  /voucher tag <delete|debug> <name> [player]", NamedTextColor.RED))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.vouchers")) return emptyList()

        val subcommands = listOf(
            "create", "delete", "tag", "enable", "disable", "setname", "setcategory", "setprice",
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
                "tag" -> listOf("delete", "debug").filter { it.startsWith(args[1].lowercase()) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "setrank" -> (plugin.rankManager.getRankIds() + "none").filter { it.startsWith(args[2].lowercase()) }
                "give" -> (plugin.physicalVoucherManager.getEnabledVoucherIds() +
                        plugin.chatTagManager.getVoucherTagIds().map { "$CHAT_TAG_VOUCHER_PREFIX$it" })
                    .filter { it.startsWith(args[2].lowercase()) }
                "tag" -> when (args[1].lowercase()) {
                    "delete" -> plugin.chatTagManager.getVoucherTagIds().filter { it.startsWith(args[2].lowercase()) }
                    "debug" -> plugin.chatTagManager.getAllTags().map { it.id }.filter { it.startsWith(args[2].lowercase()) }
                    else -> emptyList()
                }
                else -> emptyList()
            }
            4 -> when (args[0].lowercase()) {
                "tag" -> when (args[1].lowercase()) {
                    "delete" -> listOf("confirm").filter { it.startsWith(args[3].lowercase()) }
                    "debug" -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[3], ignoreCase = true) }
                    else -> emptyList()
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    companion object {
        private const val CHAT_TAG_VOUCHER_PREFIX = "chattag_"
    }
}
