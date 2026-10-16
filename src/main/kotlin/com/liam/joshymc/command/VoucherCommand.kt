package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.ChatTagManager
import com.liam.joshymc.manager.DonorTiers
import com.liam.joshymc.util.Msg
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
            Msg.noPermission(sender)
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
            Msg.error(sender, "Invalid price.")
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
            Msg.error(sender, "A voucher with id '$id' already exists.")
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
            Msg.error(sender, "Could not create Chat Tag voucher '$id' — the id is invalid or already in use.")
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
        Msg.usage(sender, "/voucher create <id> <price> <category> <rewardCommand...>")
        Msg.error(sender, "   or: /voucher create <id> <chat tag display...> (e.g. &5[Haunted])")
    }

    private fun handleDelete(sender: CommandSender, args: Array<out String>) {
        val id = args.getOrNull(1)?.lowercase()
        if (id == null) {
            Msg.usage(sender, "/voucher delete <id>")
            return
        }
        if (plugin.voucherManager.deleteVoucher(id)) {
            Msg.success(sender, "Deleted voucher $id.")
        } else {
            Msg.error(sender, "No voucher found with id $id.")
        }
    }

    private fun handleTag(sender: CommandSender, args: Array<out String>) {
        when (args.getOrNull(1)?.lowercase()) {
            "delete" -> handleTagDelete(sender, args)
            "debug" -> handleTagDebug(sender, args)
            else -> Msg.usage(sender, "/voucher tag <delete|debug> <name>")
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
            Msg.usage(sender, "/voucher tag debug <name> [player]")
            return
        }

        val target: Player? = args.getOrNull(3)?.let { Bukkit.getPlayerExact(it) } ?: (sender as? Player)
        if (target == null) {
            Msg.usage(sender, "/voucher tag debug <name> <player> (console must specify a player).")
            return
        }

        val tag = plugin.chatTagManager.getTag(id)
        if (tag == null) {
            Msg.error(sender, "No Chat Tag with id '$id' is currently loaded.")
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
            Msg.usage(sender, "/voucher tag delete <name>")
            return
        }

        val tag = plugin.chatTagManager.getTag(id)
        if (tag == null) {
            Msg.error(sender, "Custom Chat Tag '$id' does not exist.")
            return
        }
        if (!plugin.chatTagManager.isVoucherTag(tag)) {
            Msg.error(sender, "That Chat Tag cannot be deleted with this command.")
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
                Msg.success(sender, "Deleted custom Chat Tag '$id' and its voucher.")
            ChatTagManager.DeleteVoucherTagResult.NOT_VOUCHER_TAG ->
                Msg.error(sender, "That Chat Tag cannot be deleted with this command.")
            ChatTagManager.DeleteVoucherTagResult.NOT_FOUND ->
                Msg.error(sender, "Custom Chat Tag '$id' does not exist.")
        }
    }

    private fun handleSetEnabled(sender: CommandSender, args: Array<out String>, enabled: Boolean) {
        val id = args.getOrNull(1)?.lowercase()
        if (id == null) {
            Msg.usage(sender, "/voucher ${args[0].lowercase()} <id>")
            return
        }
        if (plugin.voucherManager.setEnabled(id, enabled)) {
            Msg.success(sender, "Voucher $id is now ${if (enabled) "enabled" else "disabled"}.")
        } else {
            Msg.error(sender, "No voucher found with id $id.")
        }
    }

    private fun handleSetName(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            Msg.usage(sender, "/voucher setname <id> <name...>")
            return
        }
        val id = args[1].lowercase()
        val name = args.drop(2).joinToString(" ")
        if (plugin.voucherManager.setName(id, name)) {
            Msg.success(sender, "Updated display name for $id.")
        } else {
            Msg.error(sender, "No voucher found with id $id.")
        }
    }

    private fun handleSetCategory(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            Msg.usage(sender, "/voucher setcategory <id> <category...>")
            return
        }
        val id = args[1].lowercase()
        val category = args.drop(2).joinToString(" ")
        if (plugin.voucherManager.setCategory(id, category)) {
            Msg.success(sender, "Moved $id to $category.")
        } else {
            Msg.error(sender, "No voucher found with id $id.")
        }
    }

    private fun handleSetPrice(sender: CommandSender, args: Array<out String>) {
        val id = args.getOrNull(1)?.lowercase()
        val price = args.getOrNull(2)?.toDoubleOrNull()
        if (id == null || price == null || price <= 0) {
            Msg.usage(sender, "/voucher setprice <id> <price>")
            return
        }
        if (plugin.voucherManager.setPrice(id, price)) {
            Msg.success(sender, "Updated price for $id to ${plugin.creditsManager.format(price)} credits.")
        } else {
            Msg.error(sender, "No voucher found with id $id.")
        }
    }

    private fun handleSetRank(sender: CommandSender, args: Array<out String>) {
        val id = args.getOrNull(1)?.lowercase()
        val rankArg = args.getOrNull(2)?.lowercase()
        if (id == null || rankArg == null) {
            Msg.usage(sender, "/voucher setrank <id> <rankId|none>")
            return
        }
        if (plugin.voucherManager.getVoucher(id) == null) {
            Msg.error(sender, "No voucher found with id $id.")
            return
        }

        // Only donor tiers can be sold: staff ranks and arbitrary LuckPerms groups are refused (issue #1173).
        val rankId = if (rankArg == "none") null else DonorTiers.normalize(rankArg)
        if (rankArg != "none" && rankId == null) {
            Msg.error(sender, "$rankArg is not a donor tier. Rank packages can only sell: ${DonorTiers.ORDER.joinToString(", ")}.")
            return
        }

        plugin.voucherManager.setRank(id, rankId)
        if (rankId == null) {
            Msg.success(sender, "Unlinked $id from any rank.")
        } else {
            Msg.success(sender, "Linked $id to the ${DonorTiers.displayName(rankId)} donor tier. Ownership/downgrade checks apply, and the tier is granted and confirmed in LuckPerms directly; the reward command is not run for rank packages.")
        }
    }

    private fun handleSetCommand(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            Msg.usage(sender, "/voucher setcommand <id> <command...>")
            return
        }
        val id = args[1].lowercase()
        val rewardCommand = args.drop(2).joinToString(" ")
        if (plugin.voucherManager.setRewardCommand(id, rewardCommand)) {
            Msg.success(sender, "Updated reward command for $id.")
        } else {
            Msg.error(sender, "No voucher found with id $id.")
        }
    }

    private fun handleSetDescription(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            Msg.usage(sender, "/voucher setdescription <id> <description...>")
            return
        }
        val id = args[1].lowercase()
        val description = args.drop(2).joinToString(" ")
        if (plugin.voucherManager.setDescription(id, description)) {
            Msg.success(sender, "Updated description for $id.")
        } else {
            Msg.error(sender, "No voucher found with id $id.")
        }
    }

    private fun handleSetIcon(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            Msg.error(sender, "Players only (hold the icon item).")
            return
        }
        val id = args.getOrNull(1)?.lowercase()
        if (id == null) {
            Msg.usage(sender, "/voucher seticon <id> (hold the item)")
            return
        }
        val held = sender.inventory.itemInMainHand
        if (held.type == Material.AIR) {
            Msg.error(sender, "Hold the item you want to use as the icon.")
            return
        }
        if (plugin.voucherManager.setIcon(id, held.type)) {
            Msg.success(sender, "Updated icon for $id to ${held.type.name}.")
        } else {
            Msg.error(sender, "No voucher found with id $id.")
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
            Msg.error(sender, "No vouchers found.")
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
            Msg.usage(sender, "/voucher info <id>")
            return
        }

        sender.sendMessage(Component.text("Voucher: ${voucher.id}", NamedTextColor.LIGHT_PURPLE))
        sender.sendMessage(Component.text("  Name: ${voucher.name}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  Category: ${voucher.categoryId}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  Price: ${plugin.creditsManager.format(voucher.price)} credits", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  Rank: ${voucher.rankId ?: "none"}", NamedTextColor.GRAY))
        val tier = plugin.voucherManager.rankPackageTier(voucher)
        val commandNote = when {
            voucher.rankId != null && tier == null -> " (refused: '${voucher.rankId}' is not a donor tier)"
            tier != null -> " (not run: donor tier ${DonorTiers.displayName(tier)} is granted directly)"
            else -> ""
        }
        sender.sendMessage(Component.text("  Command: ${voucher.rewardCommand}$commandNote", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  Enabled: ${voucher.enabled}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  Description: ${voucher.description}", NamedTextColor.GRAY))
    }

    private fun handleGive(sender: CommandSender, args: Array<out String>) {
        val targetName = args.getOrNull(1)
        val voucherId = args.getOrNull(2)?.lowercase()
        if (targetName == null || voucherId == null) {
            Msg.usage(sender, "/voucher give <player> <voucher-id> [amount]")
            return
        }

        val target = Bukkit.getPlayerExact(targetName)
        if (target == null) {
            Msg.error(sender, "Player '$targetName' is not online.")
            return
        }

        val amount = args.getOrNull(3)?.toIntOrNull() ?: 1
        if (amount <= 0) {
            Msg.error(sender, "Amount must be a positive number.")
            return
        }

        if (voucherId.startsWith(CHAT_TAG_VOUCHER_PREFIX)) {
            handleGiveChatTagVoucher(sender, target, voucherId.removePrefix(CHAT_TAG_VOUCHER_PREFIX), amount)
            return
        }

        val voucher = plugin.physicalVoucherManager.getVoucher(voucherId)
        if (voucher == null) {
            Msg.error(sender, "No physical voucher found with id $voucherId.")
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
            Msg.error(sender, "No Chat Tag voucher found with id $CHAT_TAG_VOUCHER_PREFIX$tagId.")
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
        Msg.success(sender, "Vouchers are loaded live from the database. $count voucher(s) currently configured.")
    }

    private fun sendUsage(sender: CommandSender) {
        Msg.usage(sender, "/voucher <create|delete|tag|enable|disable|setname|setcategory|setprice|setrank|setcommand|setdescription|seticon|list|info|reload|give> [args...]")
        Msg.error(sender, "  /voucher tag <delete|debug> <name> [player]")
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
                "setrank" -> (DonorTiers.ORDER + "none").filter { it.startsWith(args[2].lowercase()) }
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
