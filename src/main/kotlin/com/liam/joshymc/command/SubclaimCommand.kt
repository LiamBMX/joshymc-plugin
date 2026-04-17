package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.ClaimManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import kotlin.math.abs

class SubclaimCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter, Listener {

    private val wandKey = NamespacedKey(plugin, "claim_wand")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.claim")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "wand" -> handleWand(sender)
            "create" -> handleCreate(sender)
            "add" -> handleAdd(sender, args)
            "remove" -> handleRemove(sender, args)
            "delete" -> handleDelete(sender, args)
            "list" -> handleList(sender)
            "help" -> sendHelp(sender)
            else -> sendHelp(sender)
        }
        return true
    }

    private fun handleWand(player: Player) {
        val wand = ItemStack(Material.GOLDEN_SHOVEL).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("Claim Wand", NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(
                    listOf(
                        Component.text("Left-click: set pos1 / Right-click: set pos2", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                )
                meta.setEnchantmentGlintOverride(true)
                meta.persistentDataContainer.set(wandKey, PersistentDataType.INTEGER, 1)
            }
        }
        player.inventory.addItem(wand)
        plugin.commsManager.send(player, Component.text("Claim wand given.", NamedTextColor.GREEN))
    }

    private fun handleCreate(player: Player) {
        val result = plugin.claimManager.createSubclaim(player)

        when (result) {
            ClaimManager.SubclaimResult.SUCCESS -> {
                val sel = plugin.claimManager.selections[player.uniqueId]
                // Selection is cleared on success, so calculate dimensions from the subclaim we just created
                val claim = plugin.claimManager.getClaimAt(player.location)
                if (claim != null) {
                    val subclaims = plugin.claimManager.getSubclaimsInClaim(claim)
                    val newest = subclaims.lastOrNull()
                    if (newest != null) {
                        val dx = abs(newest.x2 - newest.x1) + 1
                        val dy = abs(newest.y2 - newest.y1) + 1
                        val dz = abs(newest.z2 - newest.z1) + 1
                        plugin.commsManager.send(
                            player,
                            Component.text("Subclaim #${newest.id} created (${dx}x${dy}x${dz}).", NamedTextColor.GREEN)
                        )
                        return
                    }
                }
                plugin.commsManager.send(player, Component.text("Subclaim created.", NamedTextColor.GREEN))
            }
            ClaimManager.SubclaimResult.NO_SELECTION -> {
                plugin.commsManager.send(player, Component.text("Select two positions with the claim wand first.", NamedTextColor.RED))
            }
            ClaimManager.SubclaimResult.DIFFERENT_WORLDS -> {
                plugin.commsManager.send(player, Component.text("Both positions must be in the same world.", NamedTextColor.RED))
            }
            ClaimManager.SubclaimResult.NOT_IN_CLAIM -> {
                plugin.commsManager.send(player, Component.text("Selection must be within a claimed chunk.", NamedTextColor.RED))
            }
            ClaimManager.SubclaimResult.NO_PERMISSION -> {
                plugin.commsManager.send(player, Component.text("You do not have permission to create subclaims here.", NamedTextColor.RED))
            }
            ClaimManager.SubclaimResult.DB_ERROR -> {
                plugin.commsManager.send(player, Component.text("Database error while creating subclaim.", NamedTextColor.RED))
            }
        }
    }

    private fun handleAdd(player: Player, args: Array<out String>) {
        if (args.size < 3) {
            plugin.commsManager.send(player, Component.text("Usage: /subclaim add <id> <player>", NamedTextColor.RED))
            return
        }

        val id = args[1].toIntOrNull()
        if (id == null) {
            plugin.commsManager.send(player, Component.text("Invalid subclaim ID.", NamedTextColor.RED))
            return
        }

        val subclaim = plugin.claimManager.getSubclaim(id)
        if (subclaim == null) {
            plugin.commsManager.send(player, Component.text("Subclaim #$id not found.", NamedTextColor.RED))
            return
        }

        // Check permission
        if (!canManageSubclaim(player, subclaim)) {
            plugin.commsManager.send(player, Component.text("You do not have permission to manage this subclaim.", NamedTextColor.RED))
            return
        }

        val target = Bukkit.getOfflinePlayer(args[2])
        if (!target.hasPlayedBefore() && !target.isOnline) {
            plugin.commsManager.send(player, Component.text("Player not found: ${args[2]}", NamedTextColor.RED))
            return
        }

        val added = plugin.claimManager.addSubclaimAccess(id, target.uniqueId)
        if (added) {
            plugin.commsManager.send(
                player,
                Component.text("Added ${target.name ?: args[2]} to subclaim #$id.", NamedTextColor.GREEN)
            )
        } else {
            plugin.commsManager.send(player, Component.text("Player already has access to this subclaim.", NamedTextColor.RED))
        }
    }

    private fun handleRemove(player: Player, args: Array<out String>) {
        if (args.size < 3) {
            plugin.commsManager.send(player, Component.text("Usage: /subclaim remove <id> <player>", NamedTextColor.RED))
            return
        }

        val id = args[1].toIntOrNull()
        if (id == null) {
            plugin.commsManager.send(player, Component.text("Invalid subclaim ID.", NamedTextColor.RED))
            return
        }

        val subclaim = plugin.claimManager.getSubclaim(id)
        if (subclaim == null) {
            plugin.commsManager.send(player, Component.text("Subclaim #$id not found.", NamedTextColor.RED))
            return
        }

        if (!canManageSubclaim(player, subclaim)) {
            plugin.commsManager.send(player, Component.text("You do not have permission to manage this subclaim.", NamedTextColor.RED))
            return
        }

        val target = Bukkit.getOfflinePlayer(args[2])
        val removed = plugin.claimManager.removeSubclaimAccess(id, target.uniqueId)
        if (removed) {
            plugin.commsManager.send(
                player,
                Component.text("Removed ${target.name ?: args[2]} from subclaim #$id.", NamedTextColor.GREEN)
            )
        } else {
            plugin.commsManager.send(player, Component.text("Player does not have access to this subclaim.", NamedTextColor.RED))
        }
    }

    private fun handleDelete(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /subclaim delete <id>", NamedTextColor.RED))
            return
        }

        val id = args[1].toIntOrNull()
        if (id == null) {
            plugin.commsManager.send(player, Component.text("Invalid subclaim ID.", NamedTextColor.RED))
            return
        }

        val deleted = plugin.claimManager.deleteSubclaim(id, player)
        if (deleted) {
            plugin.commsManager.send(player, Component.text("Subclaim #$id deleted.", NamedTextColor.GREEN))
        } else {
            plugin.commsManager.send(player, Component.text("Could not delete subclaim #$id. Not found or no permission.", NamedTextColor.RED))
        }
    }

    private fun handleList(player: Player) {
        val claim = plugin.claimManager.getClaimAt(player.location)
        if (claim == null) {
            plugin.commsManager.send(player, Component.text("This chunk is not claimed.", NamedTextColor.RED))
            return
        }

        val subclaims = plugin.claimManager.getSubclaimsInClaim(claim)
        if (subclaims.isEmpty()) {
            plugin.commsManager.send(player, Component.text("No subclaims in this chunk.", NamedTextColor.RED))
            return
        }

        plugin.commsManager.send(player, Component.text("Subclaims in this claim (#${claim.id}):", NamedTextColor.GREEN))
        for (sc in subclaims) {
            val ownerName = Bukkit.getOfflinePlayer(sc.ownerUuid).name ?: sc.ownerUuid.toString()
            val accessCount = sc.accessList.size
            plugin.commsManager.send(
                player,
                Component.text("  #${sc.id} ", NamedTextColor.GRAY)
                    .append(Component.text("(${sc.x1},${sc.y1},${sc.z1}) to (${sc.x2},${sc.y2},${sc.z2})", NamedTextColor.WHITE))
                    .append(Component.text(" by $ownerName", NamedTextColor.DARK_GRAY))
                    .append(Component.text(" [$accessCount access]", NamedTextColor.DARK_GRAY))
            )
        }
    }

    private fun sendHelp(player: Player) {
        plugin.commsManager.send(player, Component.text("Subclaim Commands:", NamedTextColor.GREEN))
        val commands = listOf(
            "/subclaim wand" to "Get the claim selection wand",
            "/subclaim create" to "Create subclaim from selection",
            "/subclaim add <id> <player>" to "Add player access to subclaim",
            "/subclaim remove <id> <player>" to "Remove player access from subclaim",
            "/subclaim delete <id>" to "Delete a subclaim",
            "/subclaim list" to "List subclaims in current chunk",
            "/subclaim help" to "Show this help"
        )
        for ((cmd, desc) in commands) {
            plugin.commsManager.send(
                player,
                Component.text("  $cmd ", NamedTextColor.GRAY)
                    .append(Component.text("- $desc", NamedTextColor.DARK_GRAY))
            )
        }
    }

    private fun canManageSubclaim(player: Player, subclaim: ClaimManager.Subclaim): Boolean {
        if (player.hasPermission("joshymc.claim.admin")) return true
        if (subclaim.ownerUuid == player.uniqueId) return true

        val claim = plugin.claimManager.getClaimById(subclaim.parentClaimId)
        return claim != null && plugin.claimManager.canManageClaim(player, claim)
    }

    // ══════════════════════════════════════════════════════════
    //  CLAIM WAND LISTENER
    // ══════════════════════════════════════════════════════════

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val item = event.item ?: return
        val meta = item.itemMeta ?: return
        if (!meta.persistentDataContainer.has(wandKey, PersistentDataType.INTEGER)) return
        if (meta.persistentDataContainer.getOrDefault(wandKey, PersistentDataType.INTEGER, 0) != 1) return

        val block = event.clickedBlock ?: return
        val player = event.player
        val loc = block.location

        event.isCancelled = true

        val selection = plugin.claimManager.selections.getOrPut(player.uniqueId) {
            ClaimManager.SubclaimSelection()
        }

        when (event.action) {
            Action.LEFT_CLICK_BLOCK -> {
                selection.pos1 = loc
                plugin.commsManager.send(
                    player,
                    Component.text("Position 1 set (${loc.blockX}, ${loc.blockY}, ${loc.blockZ})", NamedTextColor.GREEN)
                )
            }
            Action.RIGHT_CLICK_BLOCK -> {
                selection.pos2 = loc
                plugin.commsManager.send(
                    player,
                    Component.text("Position 2 set (${loc.blockX}, ${loc.blockY}, ${loc.blockZ})", NamedTextColor.GREEN)
                )
            }
            else -> return
        }
    }

    // ══════════════════════════════════════════════════════════
    //  TAB COMPLETION
    // ══════════════════════════════════════════════════════════

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (sender !is Player) return emptyList()

        return when (args.size) {
            1 -> listOf("wand", "create", "add", "remove", "delete", "list", "help")
                .filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> when (args[0].lowercase()) {
                "add", "remove", "delete" -> {
                    val claim = plugin.claimManager.getClaimAt(sender.location)
                    if (claim != null) {
                        plugin.claimManager.getSubclaimsInClaim(claim)
                            .map { it.id.toString() }
                            .filter { it.startsWith(args[1], ignoreCase = true) }
                    } else emptyList()
                }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "add", "remove" -> {
                    Bukkit.getOnlinePlayers()
                        .map { it.name }
                        .filter { it.startsWith(args[2], ignoreCase = true) }
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
